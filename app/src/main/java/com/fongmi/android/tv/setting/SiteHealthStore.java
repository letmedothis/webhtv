package com.fongmi.android.tv.setting;

import android.graphics.Color;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.SiteHealthRanking.Availability;
import com.fongmi.android.tv.setting.SiteHealthRanking.Facts;
import com.fongmi.android.tv.setting.SiteHealthRanking.Verdict;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SiteHealthStore {

    private static final String KEY = "site_health";
    private static final long SAVE_DELAY = 2000;
    private static final long RETAIN_MS = TimeUnit.DAYS.toMillis(90);
    private static final double ALPHA = 0.3;
    private static final int COLOR_GOOD = Color.parseColor("#0B8043");
    private static final int COLOR_WARN = Color.parseColor("#FFD54F");
    private static final int COLOR_BAD = Color.parseColor("#FF5252");
    private static final int COLOR_UNKNOWN = Color.parseColor("#80FFFFFF");
    private static final Type TYPE = new TypeToken<Map<String, Health>>() {}.getType();
    private static final Runnable SAVE = () -> Task.execute(SiteHealthStore::saveNow);

    private static final Map<String, Health> items = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;

    public static void recordSearch(Site site, boolean success, int count, long cost, String error) {
        if (skip(site)) return;
        synchronized (SiteHealthStore.class) {
            Health health = get(site.getKey());
            health.updatedAt = System.currentTimeMillis();
            health.lastSearchCost = Math.max(0, cost);
            health.lastSearchCount = Math.max(0, count);
            if (success) {
                health.searchSuccess++;
                health.lastSearchSuccessAt = health.updatedAt;
            } else {
                health.searchFail++;
                health.lastSearchFailAt = health.updatedAt;
                health.lastSearchError = trim(error);
            }
            markDirty();
        }
    }

    public static void recordDetail(String key, boolean success, long cost, String error) {
        if (skip(key)) return;
        synchronized (SiteHealthStore.class) {
            Health health = get(key);
            health.updatedAt = System.currentTimeMillis();
            health.lastDetailCost = Math.max(0, cost);
            if (success) {
                health.detailSuccess++;
                health.lastDetailSuccessAt = health.updatedAt;
            } else {
                health.detailFail++;
                health.lastDetailFailAt = health.updatedAt;
                health.lastDetailError = trim(error);
            }
            markDirty();
        }
    }

    public static void recordPlay(String key, boolean success, String error) {
        if (skip(key)) return;
        synchronized (SiteHealthStore.class) {
            Health health = get(key);
            health.updatedAt = System.currentTimeMillis();
            if (success) {
                health.playSuccess++;
                health.lastPlaySuccessAt = health.updatedAt;
            } else {
                health.playFail++;
                health.lastPlayFailAt = health.updatedAt;
                health.lastPlayError = trim(error);
            }
            markDirty();
        }
    }

    /** Records one active probe. Only successful samples update latency / throughput. */
    public static void recordProbe(Site site, boolean success, long costMs, long bytes, String error) {
        if (skip(site)) return;
        double speed = success && costMs > 0 && bytes > 0 ? bytes / 1024.0 / (costMs / 1000.0) : 0;
        synchronized (SiteHealthStore.class) {
            Health health = get(site.getKey());
            long now = System.currentTimeMillis();
            health.updatedAt = now;
            health.probed = true;
            health.probeOk = success;
            health.lastProbeAt = now;
            if (success) {
                health.probeFailStreak = 0;
                health.lastProbeError = "";
                long sample = Math.max(1, costMs);
                health.probeRttMs = health.probeRttMs <= 0 ? sample : Math.round(health.probeRttMs * (1 - ALPHA) + sample * ALPHA);
                if (speed > 0) health.probeSpeedKBs = health.probeSpeedKBs <= 0 ? speed : health.probeSpeedKBs * (1 - ALPHA) + speed * ALPHA;
            } else {
                health.probeFailStreak++;
                health.lastProbeError = trim(error);
            }
            markDirty();
        }
    }

    public static void sortSites(List<Site> sites) {
        if (!Setting.isSiteHealthSort()) return;
        if (sites == null || sites.size() < 2) return;
        Map<String, Double> scores = new HashMap<>();
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            for (Site site : sites) {
                String siteKey = site == null ? null : site.getKey();
                Health health = skip(siteKey) ? null : items.get(key(siteKey));
                scores.put(siteKey, health == null ? 0d : health.score());
            }
        }
        sites.sort((a, b) -> Double.compare(scores.getOrDefault(b == null ? null : b.getKey(), 0d), scores.getOrDefault(a == null ? null : a.getKey(), 0d)));
    }

    /** Ranking used by the site picker: availability tier, then EWMA latency, then success rate. */
    public static void sortBySpeed(List<Site> sites) {
        if (sites == null || sites.size() < 2) return;
        List<String> keys = new ArrayList<>(sites.size());
        for (Site site : sites) keys.add(site == null ? null : site.getKey());
        Map<String, Verdict> verdicts = snapshot(keys);
        SiteHealthRanking.sortBySpeed(sites, site -> site == null ? null : site.getKey(), verdicts);
    }

    public static void sortVods(List<Vod> vods) {
        if (!Setting.isSiteHealthSort()) return;
        if (vods == null || vods.size() < 2) return;
        vods.sort(SiteHealthStore::compareVods);
    }

    public static int compareVods(Vod a, Vod b) {
        if (!Setting.isSiteHealthSort()) return 0;
        return Double.compare(score(b.getSiteKey()), score(a.getSiteKey()));
    }

    public static void clear() {
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            items.clear();
            dirty = false;
        }
        App.removeCallbacks(SAVE);
        Prefers.remove(KEY);
    }

    public static void preload() {
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
        }
    }

    public static int getColor(Site site) {
        Status status = getStatus(site);
        if (status == Status.GOOD) return COLOR_GOOD;
        if (status == Status.WARN) return COLOR_WARN;
        if (status == Status.BAD) return COLOR_BAD;
        return COLOR_UNKNOWN;
    }

    public static Verdict getVerdict(Site site) {
        if (skip(site)) return Verdict.unknown();
        synchronized (SiteHealthStore.class) {
            Health health = find(site.getKey());
            return health == null ? Verdict.unknown() : SiteHealthRanking.evaluate(facts(health, System.currentTimeMillis()));
        }
    }

    /** Short human readable label for the site picker, e.g. "可用 120ms · 340KB/s". */
    public static String getLabel(Site site) {
        Verdict verdict = getVerdict(site);
        int labelId = R.string.site_health_untested;
        if (verdict.stale) labelId = R.string.site_health_retest;
        else switch (verdict.availability) {
            case AVAILABLE:
                labelId = R.string.site_health_available;
                break;
            case UNSTABLE:
                labelId = R.string.site_health_unstable;
                break;
            case UNAVAILABLE:
                labelId = R.string.site_health_unavailable;
                break;
            default:
                labelId = R.string.site_health_untested;
                break;
        }
        String label = ResUtil.getString(labelId);
        if (verdict.rttMs <= 0 || verdict.availability == Availability.UNAVAILABLE) return label;
        if (verdict.speedKBs >= 1) return ResUtil.getString(R.string.site_health_value_speed, label, verdict.rttMs, formatSpeed(verdict.speedKBs));
        return ResUtil.getString(R.string.site_health_value, label, verdict.rttMs);
    }

    public static void flush() {
        App.removeCallbacks(SAVE);
        Task.execute(SiteHealthStore::saveNow);
    }

    private static String formatSpeed(double kbs) {
        if (kbs >= 1024) return String.format(Locale.getDefault(), "%.1fMB/s", kbs / 1024);
        return String.format(Locale.getDefault(), "%.0fKB/s", kbs);
    }

    private static Status getStatus(Site site) {
        if (skip(site)) return Status.UNKNOWN;
        synchronized (SiteHealthStore.class) {
            Health health = find(site.getKey());
            if (health == null) return Status.UNKNOWN;
            if (health.probed) return statusOf(SiteHealthRanking.evaluate(facts(health, System.currentTimeMillis())).availability);
            if (health.total() == 0) return Status.UNKNOWN;
            double score = health.score();
            if (health.lastPlayFailAt > health.lastPlaySuccessAt && health.playFail >= 3 && score < 0) return Status.BAD;
            if (score >= 20 || health.lastPlaySuccessAt >= health.lastPlayFailAt && health.playSuccess > 0) return Status.GOOD;
            if (score <= -20) return Status.BAD;
            return Status.WARN;
        }
    }

    private static Status statusOf(Availability availability) {
        if (availability == Availability.AVAILABLE) return Status.GOOD;
        if (availability == Availability.UNSTABLE) return Status.WARN;
        if (availability == Availability.UNAVAILABLE) return Status.BAD;
        return Status.UNKNOWN;
    }

    private static Map<String, Verdict> snapshot(Collection<String> keys) {
        Map<String, Verdict> result = new HashMap<>();
        long now = System.currentTimeMillis();
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            for (String siteKey : keys) {
                if (skip(siteKey)) {
                    result.put(siteKey, Verdict.unknown());
                    continue;
                }
                Health health = items.get(key(siteKey));
                result.put(siteKey, health == null ? Verdict.unknown() : SiteHealthRanking.evaluate(facts(health, now)));
            }
        }
        return result;
    }

    private static Facts facts(Health health, long now) {
        Facts facts = new Facts();
        facts.probed = health.probed;
        facts.probeOk = health.probeOk;
        facts.failStreak = health.probeFailStreak;
        facts.lastProbeAt = health.lastProbeAt;
        facts.probeRttMs = health.probeRttMs;
        facts.probeSpeedKBs = health.probeSpeedKBs;
        facts.bizRttMs = health.lastSearchCost > 0 ? health.lastSearchCost : health.lastDetailCost;
        facts.bizSuccess = health.searchSuccess + health.detailSuccess * 2 + health.playSuccess * 5;
        facts.bizFail = health.searchFail + health.detailFail * 2 + health.playFail * 5;
        facts.now = now;
        return facts;
    }

    private static double score(String key) {
        if (skip(key)) return 0;
        synchronized (SiteHealthStore.class) {
            Health health = find(key);
            return health == null ? 0 : health.score();
        }
    }

    private static Health get(String siteKey) {
        ensureLoaded();
        String key = key(siteKey);
        Health health = items.get(key);
        if (health == null) items.put(key, health = new Health());
        return health;
    }

    private static Health find(String siteKey) {
        ensureLoaded();
        return items.get(key(siteKey));
    }

    private static String key(String siteKey) {
        return VodConfig.getCid() + ":" + siteKey;
    }

    private static boolean skip(Site site) {
        return site == null || skip(site.getKey());
    }

    private static boolean skip(String key) {
        return TextUtils.isEmpty(key) || SiteApi.PUSH.equals(key);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        try {
            Map<String, Health> restored = App.gson().fromJson(Prefers.getString(KEY), TYPE);
            if (restored != null) items.putAll(restored);
        } catch (Throwable ignored) {
        } finally {
            loaded = true;
        }
    }

    private static void markDirty() {
        dirty = true;
        App.post(SAVE, SAVE_DELAY);
    }

    private static void saveNow() {
        Map<String, Health> copy;
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            if (!dirty) return;
            prune();
            copy = new LinkedHashMap<>(items);
            dirty = false;
        }
        Prefers.put(KEY, App.gson().toJson(copy));
    }

    private static void prune() {
        long expire = System.currentTimeMillis() - RETAIN_MS;
        items.entrySet().removeIf(entry -> entry.getValue().updatedAt > 0 && entry.getValue().updatedAt < expire);
    }

    private static String trim(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private enum Status {GOOD, WARN, BAD, UNKNOWN}

    public static class Health {

        private int searchSuccess;
        private int searchFail;
        private int detailSuccess;
        private int detailFail;
        private int playSuccess;
        private int playFail;
        private int lastSearchCount;
        private long lastSearchCost;
        private long lastDetailCost;
        private long lastSearchSuccessAt;
        private long lastSearchFailAt;
        private long lastDetailSuccessAt;
        private long lastDetailFailAt;
        private long lastPlaySuccessAt;
        private long lastPlayFailAt;
        private long updatedAt;
        private String lastSearchError;
        private String lastDetailError;
        private String lastPlayError;

        private boolean probed;
        private boolean probeOk;
        private int probeFailStreak;
        private long lastProbeAt;
        private long probeRttMs;
        private double probeSpeedKBs;
        private String lastProbeError;

        private int total() {
            return searchSuccess + searchFail + detailSuccess + detailFail + playSuccess + playFail;
        }

        private double score() {
            int success = searchSuccess + detailSuccess * 2 + playSuccess * 5;
            int fail = searchFail + detailFail * 2 + playFail * 5;
            if (success + fail == 0) return 0;
            double score = 60.0 * (success - fail) / (success + fail + 4.0);
            score += Math.min(lastSearchCount, 20) * 1.2;
            score -= Math.min(lastSearchCost, TimeUnit.SECONDS.toMillis(8)) / 1000.0;
            score -= Math.min(lastDetailCost, TimeUnit.SECONDS.toMillis(8)) / 1500.0;
            if (lastPlaySuccessAt > lastPlayFailAt) score += 18;
            if (lastPlayFailAt > lastPlaySuccessAt) score -= 18;
            return score;
        }
    }
}
