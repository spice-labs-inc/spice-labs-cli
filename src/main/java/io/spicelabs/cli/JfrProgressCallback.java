// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

/**
 * Progress sink for the JFR parse pass. Mirrors the shape of goat-rodeo's
 * {@code ProgressListener} without dragging the goat-rodeo dependency into
 * {@link JfrEventExtractor} — runtime surveys don't use goat-rodeo.
 *
 * <p>{@code current} is the number of JFR recordings whose parse has finished; {@code total}
 * is the number of recordings handed to {@link JfrEventExtractor#extract}. Liveness ticks
 * within a single long recording fire with the same {@code current/total}, so callers that
 * just want a "still working" pulse get one.
 */
@FunctionalInterface
interface JfrProgressCallback {

    void onProgress(long current, long total);
}
