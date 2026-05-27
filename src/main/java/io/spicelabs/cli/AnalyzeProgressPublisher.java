// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.UUID;

import io.spicelabs.goatrodeo.ProgressListener;

/**
 * Translates goat-rodeo's {@code (current, total)} ticks into RUNNING progress publishes
 * against the ANALYZE sub-job daikon minted at {@code initSurvey}, so the dashboard's
 * phase strip moves during long surveys.
 *
 * <p>Layout of the 0..100 band:
 * <ul>
 *   <li>0% — {@link #start()} fires once before goat-rodeo begins.</li>
 *   <li>5..95% — {@code onProgress(current, total)} maps linearly into this range.</li>
 *   <li>95% — {@link #building()} fires after goat-rodeo returns, while ginger-j wraps
 *       the bundle.</li>
 *   <li>100% / COMPLETED — {@link #complete()} fires when the wrap is done and UPLOAD is
 *       about to take over.</li>
 * </ul>
 *
 * <p>Throttling: emits on the first call, then only when the percent moves by at least 1%
 * or 2 seconds have passed since the last emit. Goat-rodeo's per-run Notifier serializes
 * onProgress calls so no extra locking is required for the throttle state.
 */
final class AnalyzeProgressPublisher implements ProgressListener {

    /** What this publisher knows how to invoke. Real code passes {@code ginger::publishStatus}. */
    @FunctionalInterface
    interface StatusPublisher {
        void publish(UUID subJobId, String status, Integer progress, String message);
    }

    private static final long MIN_INTERVAL_MS = 2000L;
    private static final int MIN_PERCENT_DELTA = 1;
    private static final int START_PERCENT = 5;
    private static final int BUILDING_PERCENT = 95;
    private static final int COMPLETE_PERCENT = 100;

    private final StatusPublisher publisher;
    private final UUID subJobId;

    private long lastEmitMillis = 0L;
    private int lastEmitPercent = -1;
    private boolean terminated = false;

    AnalyzeProgressPublisher(StatusPublisher publisher, UUID subJobId) {
        this.publisher = publisher;
        this.subJobId = subJobId;
    }

    /** Opening tick: RUNNING / 0 / "Analyzing source". */
    void start() {
        publishRunning(0, "Analyzing source");
    }

    @Override
    public void onProgress(long current, long total) {
        int percent;
        if (total <= 0) {
            percent = START_PERCENT;
        } else {
            double fraction = Math.min(1.0, (double) current / (double) total);
            percent = START_PERCENT
                    + (int) Math.floor(fraction * (BUILDING_PERCENT - START_PERCENT));
            if (percent < START_PERCENT) percent = START_PERCENT;
            if (percent > BUILDING_PERCENT) percent = BUILDING_PERCENT;
        }
        long now = System.currentTimeMillis();
        boolean firstCall = lastEmitPercent < 0;
        boolean percentMoved = (percent - lastEmitPercent) >= MIN_PERCENT_DELTA;
        boolean intervalElapsed = (now - lastEmitMillis) >= MIN_INTERVAL_MS;
        if (!firstCall && !percentMoved && !intervalElapsed) {
            return;
        }
        publishRunning(percent, "Processing artifacts (" + current + "/" + total + ")");
    }

    /** Goat-rodeo returned; ginger-j is about to wrap the bundle. */
    void building() {
        publishRunning(BUILDING_PERCENT, "Building bundle");
    }

    /**
     * Wrap done; ANALYZE is fully complete. Idempotent — wired into ginger-j's
     * {@code afterBundleWrapped} hook, so a subsequent {@link #fail(String)} from an upload
     * exception silently no-ops instead of trying to drag a finished sub-job backwards.
     */
    void complete() {
        publishTerminal(COMPLETE_PERCENT, "COMPLETED", "Analysis complete");
    }

    /**
     * Something blew up during analyze. Reports the last known percent. No-op once the
     * sub-job has already reached a terminal state.
     */
    void fail(String reason) {
        int percent = Math.max(0, lastEmitPercent);
        publishTerminal(percent, "FAILED", "Analysis failed: " + reason);
    }

    private void publishRunning(int percent, String message) {
        if (terminated) {
            return;
        }
        lastEmitMillis = System.currentTimeMillis();
        lastEmitPercent = percent;
        publisher.publish(subJobId, "RUNNING", percent, message);
    }

    private void publishTerminal(int percent, String status, String message) {
        if (terminated) {
            return;
        }
        terminated = true;
        lastEmitMillis = System.currentTimeMillis();
        lastEmitPercent = percent;
        publisher.publish(subJobId, status, percent, message);
    }
}
