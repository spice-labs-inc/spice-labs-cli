// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeProgressPublisherTest {

    private record Call(UUID subJobId, String status, Integer percent, String message) {}

    private List<Call> calls;
    private UUID subJobId;
    private AnalyzeProgressPublisher publisher;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        subJobId = UUID.randomUUID();
        publisher = new AnalyzeProgressPublisher(
                (sid, status, percent, message) -> calls.add(new Call(sid, status, percent, message)),
                subJobId);
    }

    @Test
    void start_emitsOpeningTickAtZero() {
        publisher.start();
        assertEquals(1, calls.size());
        assertEquals(new Call(subJobId, "RUNNING", 0, "Analyzing source"), calls.get(0));
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
        assertEquals(new Call(subJobId, "COMPLETED", 100, "Analysis complete"), calls.get(0));
    }

    @Test
    void fail_emitsFailedAtLastSeenPercent() {
        publisher.onProgress(50, 100); // pushes lastPercent to 50%
        publisher.fail("boom");

        assertEquals(2, calls.size());
        assertEquals(new Call(subJobId, "FAILED", 50, "Analysis failed: boom"), calls.get(1));
    }

    @Test
    void fail_emitsZeroWhenNothingPublishedYet() {
        publisher.fail("never started");
        assertEquals(new Call(subJobId, "FAILED", 0, "Analysis failed: never started"), calls.get(0));
    }

    @Test
    void building_pinsAtNinetyFive() {
        publisher.building();
        assertEquals(new Call(subJobId, "RUNNING", 95, "Building bundle"), calls.get(0));
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
}
