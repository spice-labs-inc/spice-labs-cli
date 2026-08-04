// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeProgressPublisherTest {

    private record Call(UUID subJobId, String status, Integer percent, String message,
            Map<String, Object> analyzeStats) {}

    private List<Call> calls;
    private UUID subJobId;
    private AnalyzeProgressPublisher publisher;

    private Call call(UUID sid, String status, Integer percent, String message) {
        return new Call(sid, status, percent, message, null);
    }

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        subJobId = UUID.randomUUID();
        publisher = new AnalyzeProgressPublisher(
                (sid, status, percent, message, stats) ->
                        calls.add(new Call(sid, status, percent, message, stats)),
                subJobId);
    }

    @Test
    void start_emitsOpeningTickAtZero() {
        publisher.start();
        assertEquals(1, calls.size());
        assertEquals(call(subJobId, "RUNNING", 0, "Analyzing source"), calls.get(0));
    }

    @Test
    void onProgress_mapsLinearlyIntoFiveToNinetyFive() {
        publisher.onProgress(0, 100);
        publisher.onProgress(50, 100);
        publisher.onProgress(100, 100);

        assertEquals(3, calls.size());
        assertEquals(5, calls.get(0).percent());
        assertEquals(50, calls.get(1).percent());
        assertEquals(95, calls.get(2).percent());
    }

    @Test
    void onProgress_dropsTicksWithinOnePercentAndUnderTwoSeconds() {
        publisher.onProgress(0, 1000);   // first call → 5%
        publisher.onProgress(1, 1000);   // still 5% → throttled
        publisher.onProgress(5, 1000);   // still 5% → throttled
        publisher.onProgress(12, 1000);  // 6% → passes the 1% delta gate

        assertEquals(2, calls.size());
        assertEquals(5, calls.get(0).percent());
        assertEquals(6, calls.get(1).percent());
    }

    @Test
    void onProgress_messageIncludesCurrentAndTotal() {
        publisher.onProgress(7, 42);
        assertEquals("Processing artifacts (7/42)", calls.get(0).message());
    }

    @Test
    void complete_emitsCompletedTerminalAtOneHundred() {
        publisher.complete();
        assertEquals(call(subJobId, "COMPLETED", 100, "Analysis complete"), calls.get(0));
    }

    @Test
    void fail_emitsFailedAtLastSeenPercent() {
        publisher.onProgress(50, 100); // pushes lastPercent to 50%
        publisher.fail("boom");

        assertEquals(2, calls.size());
        assertEquals(call(subJobId, "FAILED", 50, "Analysis failed: boom"), calls.get(1));
    }

    @Test
    void fail_emitsZeroWhenNothingPublishedYet() {
        publisher.fail("never started");
        assertEquals(call(subJobId, "FAILED", 0, "Analysis failed: never started"), calls.get(0));
    }

    @Test
    void building_pinsAtNinetyFive() {
        publisher.building();
        assertEquals(call(subJobId, "RUNNING", 95, "Building bundle"), calls.get(0));
    }

    @Test
    void terminalCallsAreIdempotent() {
        publisher.complete();
        publisher.fail("late upload failure");
        publisher.onProgress(50, 100);
        publisher.complete();

        assertEquals(1, calls.size(), "only the first terminal call should publish");
        assertEquals("COMPLETED", calls.get(0).status());
    }

    @Test
    void onProgress_handlesZeroTotalAsBaseline() {
        // total=0 happens at the very start of a goat-rodeo run, before the filesystem walk
        // completes.
        publisher.onProgress(0, 0);
        assertTrue(calls.size() >= 1);
        assertEquals(5, calls.get(0).percent());
    }

    private AnalyzeProgressPublisher withStats(AnalyzeStats stats) {
        return new AnalyzeProgressPublisher(
                (sid, status, percent, message, analyzeStats) ->
                        calls.add(new Call(sid, status, percent, message, analyzeStats)),
                subJobId, stats);
    }

    @Test
    void complete_attachesStatsPayloadOnTerminalOnly() {
        AnalyzeProgressPublisher p = withStats(new AnalyzeStats());
        p.onProgress(40, 100);
        p.onProgress(100, 100);
        p.complete();

        assertEquals(3, calls.size());
        assertNull(calls.get(0).analyzeStats(), "running ticks carry no stats");
        assertNull(calls.get(1).analyzeStats(), "running ticks carry no stats");
        Map<String, Object> stats = calls.get(2).analyzeStats();
        assertNotNull(stats);
        assertEquals(100L, stats.get("itemsSeen"));
        assertEquals(100L, stats.get("itemsProcessed"));
    }

    @Test
    void fail_attachesStatsPayload() {
        AnalyzeProgressPublisher p = withStats(new AnalyzeStats());
        p.onProgress(30, 90);
        p.fail("boom");

        Map<String, Object> stats = calls.get(calls.size() - 1).analyzeStats();
        assertNotNull(stats);
        assertEquals(90L, stats.get("itemsSeen"));
        assertEquals(30L, stats.get("itemsProcessed"));
    }

    @Test
    void complete_withoutStatsOrTicksAttachesNothing() {
        publisher.complete();
        assertNull(calls.get(0).analyzeStats());

        calls.clear();
        AnalyzeProgressPublisher p = withStats(new AnalyzeStats());
        p.complete();
        assertNull(calls.get(0).analyzeStats(), "empty stats publish no payload");
    }
}
