package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.setting.SiteHealthRanking.Availability;
import com.fongmi.android.tv.setting.SiteHealthRanking.Facts;
import com.fongmi.android.tv.setting.SiteHealthRanking.Verdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SiteHealthRankingTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Facts facts() {
        Facts f = new Facts();
        f.now = NOW;
        f.lastProbeAt = NOW;
        return f;
    }

    private static Facts available(long rttMs) {
        Facts f = facts();
        f.probed = true;
        f.probeOk = true;
        f.probeRttMs = rttMs;
        f.bizSuccess = 5;
        return f;
    }

    @Test
    public void noSignalIsUntested() {
        Verdict verdict = SiteHealthRanking.evaluate(facts());
        assertEquals(Availability.UNTESTED, verdict.availability);
        assertEquals(Availability.UNTESTED.ordinal(), verdict.rank);
        assertEquals(0, verdict.rttMs);
        assertFalse(verdict.stale);
    }

    @Test
    public void singleProbeFailureIsOnlyUnstable() {
        Facts f = available(120);
        f.probeOk = false;
        f.failStreak = 1;
        Verdict verdict = SiteHealthRanking.evaluate(f);
        assertEquals(Availability.UNSTABLE, verdict.availability);
        assertEquals(120, verdict.rttMs);
    }

    @Test
    public void consecutiveProbeFailuresAreUnavailable() {
        Facts f = available(120);
        f.probeOk = false;
        f.failStreak = 2;
        Verdict verdict = SiteHealthRanking.evaluate(f);
        assertEquals(Availability.UNAVAILABLE, verdict.availability);
        assertEquals(Availability.UNAVAILABLE.ordinal(), verdict.rank);
    }

    @Test
    public void businessHistoryAloneStillGivesAvailabilityAndSpeed() {
        Facts f = facts();
        f.bizSuccess = 8;
        f.bizFail = 1;
        f.bizRttMs = 340;
        Verdict verdict = SiteHealthRanking.evaluate(f);
        assertEquals(Availability.AVAILABLE, verdict.availability);
        assertEquals(340, verdict.rttMs);
        assertEquals(89, verdict.successRate);
    }

    @Test
    public void heavyBusinessFailuresDowngradeToUnstable() {
        Facts f = facts();
        f.bizSuccess = 1;
        f.bizFail = 5;
        f.bizRttMs = 900;
        assertEquals(Availability.UNSTABLE, SiteHealthRanking.evaluate(f).availability);
    }

    @Test
    public void probeRttWinsOverBusinessRtt() {
        Facts f = available(120);
        f.bizRttMs = 900;
        f.probeSpeedKBs = 340;
        Verdict verdict = SiteHealthRanking.evaluate(f);
        assertEquals(120, verdict.rttMs);
        assertEquals(340, verdict.speedKBs, 0.001);
    }

    @Test
    public void staleProbeIsMarkedAndDemoted() {
        Facts f = available(120);
        f.lastProbeAt = NOW - SiteHealthRanking.STALE_MS - 1;
        Verdict verdict = SiteHealthRanking.evaluate(f);
        assertTrue(verdict.stale);
        assertEquals(Availability.AVAILABLE, verdict.availability);
        assertEquals(Availability.UNSTABLE.ordinal(), verdict.rank);
    }

    @Test
    public void freshProbeIsNotStale() {
        Facts f = available(120);
        f.lastProbeAt = NOW - 1000;
        assertFalse(SiteHealthRanking.evaluate(f).stale);
    }

    @Test
    public void sortOrdersByTierThenByLatency() {
        Map<String, Verdict> verdicts = new HashMap<>();
        verdicts.put("slow", SiteHealthRanking.evaluate(available(900)));
        verdicts.put("fast", SiteHealthRanking.evaluate(available(80)));
        Facts broken = available(50);
        broken.probeOk = false;
        broken.failStreak = 2;
        verdicts.put("broken", SiteHealthRanking.evaluate(broken));
        verdicts.put("unknown", Verdict.unknown());
        List<String> sites = new ArrayList<>(Arrays.asList("unknown", "broken", "slow", "fast"));
        SiteHealthRanking.sortBySpeed(sites, key -> key, verdicts);
        assertEquals(Arrays.asList("fast", "slow", "unknown", "broken"), sites);
    }

    @Test
    public void unknownSitesKeepTheirOriginalOrder() {
        List<String> sites = new ArrayList<>(Arrays.asList("c", "a", "b"));
        SiteHealthRanking.sortBySpeed(sites, key -> key, new HashMap<>());
        assertEquals(Arrays.asList("c", "a", "b"), sites);
    }

    @Test
    public void equalTierUsesSuccessRateAsTieBreaker() {
        Facts low = available(120);
        low.bizSuccess = 1;
        low.bizFail = 1;
        Facts high = available(120);
        high.bizSuccess = 20;
        high.bizFail = 0;
        Map<String, Verdict> verdicts = new HashMap<>();
        verdicts.put("low", SiteHealthRanking.evaluate(low));
        verdicts.put("high", SiteHealthRanking.evaluate(high));
        List<String> sites = new ArrayList<>(Arrays.asList("low", "high"));
        SiteHealthRanking.sortBySpeed(sites, key -> key, verdicts);
        assertEquals(Arrays.asList("high", "low"), sites);
    }
}
