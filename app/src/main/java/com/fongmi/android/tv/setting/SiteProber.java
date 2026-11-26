package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bounded active probe for VOD sites. Measures HTTP availability, elapsed time and throughput,
 * then feeds the samples into {@link SiteHealthStore}. Never touches playback or business caches.
 */
public final class SiteProber {

    private static final String TAG = "site_probe";
    private static final long TIMEOUT_MS = 4000;
    private static final long MAX_BYTES = 64 * 1024;
    private static final long AUTO_INTERVAL_MS = 30L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "site-prober");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile long generation;
    private static volatile OkHttpClient client;
    private static volatile Callback callback;

    private SiteProber() {
    }

    public interface Callback {

        void onProgress(int done, int total);

        void onFinished();
    }

    /** Manual probes always run; automatic probes are gated by a setting and a per-config throttle. */
    public static void probe(List<Site> sites, boolean manual, Callback callback) {
        if (sites == null || sites.isEmpty()) return;
        long now = System.currentTimeMillis();
        String cid = String.valueOf(VodConfig.getCid());
        if (!manual) {
            if (!Setting.isSiteProbeAuto()) return;
            if (now - Setting.getSiteProbeAt(cid) < AUTO_INTERVAL_MS) return;
        }
        List<Site> targets = new ArrayList<>();
        for (Site site : sites) if (probeable(site)) targets.add(site);
        if (targets.isEmpty()) {
            App.post(() -> {
                if (callback != null) callback.onFinished();
            });
            return;
        }
        OkHttpClient current;
        long gen;
        synchronized (LOCK) {
            gen = ++generation;
            SiteProber.callback = callback;
            current = OkHttp.client(TIMEOUT_MS);
            client = current;
        }
        Setting.putSiteProbeAt(cid, now);
        POOL.execute(SiteHealthStore::preload);
        AtomicInteger done = new AtomicInteger();
        int total = targets.size();
        for (Site site : targets) {
            POOL.execute(() -> {
                Result result = probeOne(site, current);
                if (gen != generation) return;
                SiteHealthStore.recordProbe(site, result.ok, result.costMs, result.bytes, result.error);
                int value = done.incrementAndGet();
                App.post(() -> {
                    if (gen != generation) return;
                    Callback active = SiteProber.callback;
                    if (active == null) return;
                    active.onProgress(value, total);
                    if (value == total) finish(gen);
                });
            });
        }
    }

    /** Stops an in-flight batch; late results are dropped by generation check. */
    public static void cancel() {
        OkHttpClient current;
        synchronized (LOCK) {
            generation++;
            callback = null;
            current = client;
            client = null;
        }
        if (current != null) OkHttp.cancel(current, TAG);
    }

    private static void finish(long gen) {
        synchronized (LOCK) {
            if (gen != generation) return;
        }
        SiteHealthStore.flush();
        Callback active = SiteProber.callback;
        if (active != null) active.onFinished();
        synchronized (LOCK) {
            if (gen == generation) SiteProber.callback = null;
        }
    }

    private static boolean probeable(Site site) {
        if (site == null || site.isEmpty()) return false;
        if (SiteApi.PUSH.equals(site.getKey())) return false;
        if (site.getType() == 3) return false;
        String api = site.getApi();
        return !TextUtils.isEmpty(api) && (api.startsWith("http://") || api.startsWith("https://"));
    }

    private static Result probeOne(Site site, OkHttpClient current) {
        long start = System.currentTimeMillis();
        String url = site.getApi();
        if (site.getType() == 4) {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed != null) url = parsed.newBuilder().setQueryParameter("filter", "true").build().toString();
        }
        Headers headers = headers(site.getHeader());
        try {
            return get(current, url, headers, start);
        } catch (Throwable error) {
            try {
                Request request = new Request.Builder().url(url).headers(headers).head().tag(TAG).build();
                try (Response response = current.newCall(request).execute()) {
                    int code = response.code();
                    boolean ok = code >= 200 && code < 400;
                    return new Result(ok, System.currentTimeMillis() - start, 0, ok ? "" : "HTTP " + code);
                }
            } catch (Throwable fallback) {
                return new Result(false, System.currentTimeMillis() - start, 0, describe(error));
            }
        }
    }

    private static Result get(OkHttpClient current, String url, Headers headers, long start) throws Exception {
        Request request = new Request.Builder().url(url).headers(headers).get().tag(TAG).build();
        try (Response response = current.newCall(request).execute()) {
            ResponseBody body = response.body();
            long bytes = 0;
            if (body != null) {
                try (InputStream input = body.byteStream()) {
                    byte[] buffer = new byte[8192];
                    while (bytes < MAX_BYTES) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, MAX_BYTES - bytes));
                        if (read < 0) break;
                        bytes += read;
                    }
                }
            }
            int code = response.code();
            boolean ok = code >= 200 && code < 400;
            return new Result(ok, System.currentTimeMillis() - start, bytes, ok ? "" : "HTTP " + code);
        }
    }

    private static Headers headers(Map<String, String> values) {
        Headers.Builder builder = new Headers.Builder();
        if (values == null) return builder.build();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            builder.add(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class Result {
        private final boolean ok;
        private final long costMs;
        private final long bytes;
        private final String error;

        private Result(boolean ok, long costMs, long bytes, String error) {
            this.ok = ok;
            this.costMs = costMs;
            this.bytes = bytes;
            this.error = error;
        }
    }
}
