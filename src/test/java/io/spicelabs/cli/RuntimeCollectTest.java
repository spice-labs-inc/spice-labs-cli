// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;

/** The options the wrapper hands RuntimeCollect from {@code spice survey runtime}. */
class RuntimeCollectTest {

    @Test
    void defaults_whenNoOptionsGiven() {
        RuntimeCollect.Options options = RuntimeCollect.parseOptions(new String[] {"my-app", "/work"}, 2);
        assertFalse(options.noUpload());
        assertNull(options.anchor());
        assertEquals(Level.INFO, options.logLevel());
        assertNull(options.chunkSizeMB());
    }

    @Test
    void readsEveryOption() {
        RuntimeCollect.Options options = RuntimeCollect.parseOptions(new String[] {
            "my-app", "/work", "--no-upload", "--anchor", "/build/app.jar",
            "--log-level", "debug", "--chunk-size", "8"}, 2);
        assertTrue(options.noUpload());
        assertEquals(Path.of("/build/app.jar"), options.anchor());
        assertEquals(Level.DEBUG, options.logLevel());
        assertEquals(8, options.chunkSizeMB());
    }

    @Test
    void readsOptionsAfterTheLicenseCheckFlag() {
        RuntimeCollect.Options options = RuntimeCollect.parseOptions(new String[] {
            "--check-license", "--log-level", "WARN", "--chunk-size", "16"}, 1);
        assertEquals(Level.WARN, options.logLevel());
        assertEquals(16, options.chunkSizeMB());
    }

    @Test
    void refusesAnUnknownLogLevel() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RuntimeCollect.parseOptions(new String[] {"my-app", "/work", "--log-level", "loud"}, 2));
        assertTrue(e.getMessage().contains("loud"), e.getMessage());
    }

    @Test
    void refusesAChunkSizeThatIsNotAPositiveWholeNumber() {
        for (String bad : new String[] {"0", "-1", "big", "1.5"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeCollect.parseOptions(new String[] {"my-app", "/work", "--chunk-size", bad}, 2));
            assertTrue(e.getMessage().contains("--chunk-size"), e.getMessage());
        }
    }

    @Test
    void refusesAnOptionWithoutItsValue() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeCollect.parseOptions(new String[] {"my-app", "/work", "--chunk-size"}, 2));
    }
}
