package com.fongmi.android.tv.setting;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure JVM policy for site availability and speed ranking.
 * Kept free of Android dependencies so the ranking rules can be unit tested directly.
 */
public final class SiteHealthRanking {

    /** A probe result older than this is shown as "recheck" and demoted by one tier. */
    public static final long STALE_MS = 6L * 60L * 60L * 1000L;

    private SiteHealthRanking() {
    }

    /** Declaration order is also the sort order: better tiers first. */
    public enum Availability {
        AVAILABLE, UNSTABLE, UNTESTED, UNAVAILABLE
    }

    /** Inputs collected from one {@code SiteHealthStore.Health} record. Public fields keep callers simple. */
    public static final class Facts {
        public boolean probed;
        public boolean probeOk;
        public int failStreak;
        public long lastProbeAt;
        public long probeRttMs;
        public double probeSpeedKBs;
        public long bizRttMs;
        public int bizSuccess;
        public int bizFail;
        public long now;
    }

    public static final class Verdict {
        public final Availability availability;
        public final long rttMs;
        public final double speedKBs;
        public final int successRate;
        public final boolean stale;
        public final int rank;

        private Verdict(Availability availability, long rttMs, double speedKBs, int successRate, boolean stale, int rank) {
            this.availability = availability;
            this.rttMs = rttMs;
            this.speedKBs = speedKBs;
            this.successRate = successRate;
            this.stale = stale;
            this.rank = rank;
        }

        public static Verdict unknown() {
            return new Verdict(Availability.UNTESTED, 0, 0, 0, false, Availability.UNTESTED.ordinal());
        }
    }

    public static Verdict evaluate(Facts f) {
        long now = f.now > 0 ? f.now : System.currentTimeMillis();
        int success = Math.max(0, f.bizSuccess);
        int fail = Math.max(0, f.bizFail);
        int total = success + fail;
        boolean hasBiz = total > 0;
        boolean hasSignal = f.probed || hasBiz;
        long rtt = f.probeRttMs > 0 ? f.probeRttMs : Math.max(0, f.bizRttMs);
        double speed = Math.max(0, f.probeSpeedKBs);
        int rate = total == 0 ? 0 : Math.round(100f * success / total);
        boolean stale = f.probed && f.lastProbeAt > 0 && now - f.lastProbeAt > STALE_MS;

        Availability availability;
        if (!hasSignal) availability = Availability.UNTESTED;
        else if (f.probed && f.failStreak >= 2) availability = Availability.UNAVAILABLE;
        else if (f.probed && !f.probeOk) availability = Availability.UNSTABLE;
        else if (hasBiz && fail >= 3 && fail > success) availability = Availability.UNSTABLE;
        else availability = Availability.AVAILABLE;

        int rank = availability.ordinal();
        if (stale && rank < Availability.UNAVAILABLE.ordinal()) rank++;
        return new Verdict(availability, rtt, speed, rate, stale, rank);
    }

    /**
     * Stable ordering: availability tier first, then EWMA latency ascending, then business success rate.
     * Sites without any measurement keep their original relative order.
     */
    public static <T> void sortBySpeed(List<T> items, Function<T, String> key, Map<String, Verdict> verdicts) {
        if (items == null || items.size() < 2) return;
        Verdict unknown = Verdict.unknown();
        items.sort((a, b) -> {
            String ka = key.apply(a);
            String kb = key.apply(b);
            Verdict va = verdicts == null || ka == null ? unknown : verdicts.getOrDefault(ka, unknown);
            Verdict vb = verdicts == null || kb == null ? unknown : verdicts.getOrDefault(kb, unknown);
            int result = Integer.compare(va.rank, vb.rank);
            if (result != 0) return result;
            result = Long.compare(effectiveRtt(va), effectiveRtt(vb));
            if (result != 0) return result;
            return Integer.compare(vb.successRate, va.successRate);
        });
    }

    private static long effectiveRtt(Verdict verdict) {
        return verdict.rttMs > 0 ? verdict.rttMs : Long.MAX_VALUE;
    }
}
