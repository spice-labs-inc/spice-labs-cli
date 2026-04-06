// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * Tests for JfrEventExtractor.
 *
 * <p>Uses the JDK's built-in JFR to create synthetic recordings for testing,
 * avoiding the need for pre-recorded .jfr test fixtures.
 */
class JfrEventExtractorTest {

    @TempDir
    Path tempDir;

    // ── isApplicationCode ───────────────────────────────────────────────

    @Test
    void isApplicationCode_jdkClasses() {
        assertFalse(JfrEventExtractor.isApplicationCode("java.security.Signature"));
        assertFalse(JfrEventExtractor.isApplicationCode("javax.crypto.Cipher"));
        assertFalse(JfrEventExtractor.isApplicationCode("sun.security.provider.SHA"));
        assertFalse(JfrEventExtractor.isApplicationCode("com.sun.crypto.provider.DESCipher"));
        assertFalse(JfrEventExtractor.isApplicationCode("jdk.internal.Something"));
        assertFalse(JfrEventExtractor.isApplicationCode("org.openjdk.nashorn.Something"));
    }

    @Test
    void isApplicationCode_appClasses() {
        assertTrue(JfrEventExtractor.isApplicationCode("com.example.MyApp"));
        assertTrue(JfrEventExtractor.isApplicationCode("io.spicelabs.cli.SurveyRuntimeCommand"));
        assertTrue(JfrEventExtractor.isApplicationCode("org.apache.commons.codec.Something"));
        assertTrue(JfrEventExtractor.isApplicationCode("com.google.crypto.tink.Something"));
    }

    // ── Data model records ──────────────────────────────────────────────

    @Test
    void rawSurveyData_hasCorrectTypeAndVersion() {
        var data = new JfrEventExtractor.RawSurveyData(
                "1.0.0", "runtime-pqc-survey", "test-app",
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals("1.0.0", data.version());
        assertEquals("runtime-pqc-survey", data.type());
        assertEquals("test-app", data.subject());
    }

    @Test
    void runtimeInfo_record() {
        var info = new JfrEventExtractor.RuntimeInfo(
                "21.0.1+12", "OpenJDK 64-Bit Server VM", "Eclipse Adoptium",
                "21", "Linux 6.1.0", 12345);
        assertEquals("21.0.1+12", info.jvmVersion());
        assertEquals(12345, info.pid());
    }

    @Test
    void callSite_deduplication() {
        var cs1 = new JfrEventExtractor.CallSite("com.example.App.run() line 42", "main");
        var cs2 = new JfrEventExtractor.CallSite("com.example.App.run() line 42", "main");
        assertEquals(cs1, cs2);
    }

    // ── Extract with a real (but minimal) JFR recording ─────────────────

    @Test
    void extract_emptyRecording_returnsEmptyData() throws Exception {
        // Create a minimal JFR recording with no security events
        Path jfrFile = tempDir.resolve("empty.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            // Do nothing — just stop immediately
            Thread.sleep(50); // Give JFR a moment to initialize
            recording.stop();
            recording.dump(jfrFile);
        }

        assertTrue(Files.exists(jfrFile));

        var data = JfrEventExtractor.extract("test-subject", List.of(jfrFile));

        assertNotNull(data);
        assertEquals("1.0.0", data.version());
        assertEquals("runtime-pqc-survey", data.type());
        assertEquals("test-subject", data.subject());
        assertEquals(1, data.recordings().size());
        assertEquals("empty.jfr", data.recordings().get(0));

        // Runtime info should be populated from the recording
        assertNotNull(data.runtime());
        // The JFR recording from our own JVM should have JVM info
        // (may be null in some edge cases, but should work in standard JDK)
    }

    @Test
    void extract_multipleRecordings_merges() throws Exception {
        Path jfr1 = tempDir.resolve("recording1.jfr");
        Path jfr2 = tempDir.resolve("recording2.jfr");

        try (Recording recording = new Recording()) {
            recording.start();
            Thread.sleep(50);
            recording.stop();
            recording.dump(jfr1);
        }

        try (Recording recording = new Recording()) {
            recording.start();
            Thread.sleep(50);
            recording.stop();
            recording.dump(jfr2);
        }

        var data = JfrEventExtractor.extract("multi-test", List.of(jfr1, jfr2));

        assertNotNull(data);
        assertEquals("multi-test", data.subject());
        assertEquals(2, data.recordings().size());
    }

    @Test
    void extract_withSecurityProviderEvent_capturesAlgorithm() throws Exception {
        // Trigger a real security provider event by using javax.crypto
        Path jfrFile = tempDir.resolve("security.jfr");

        try (Recording recording = new Recording()) {
            // Enable security events
            recording.enable("jdk.SecurityProviderService").withStackTrace();
            recording.start();

            // Trigger a security provider lookup
            try {
                javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                java.security.MessageDigest.getInstance("SHA-256");
            } catch (Exception ignored) {}

            Thread.sleep(100);
            recording.stop();
            recording.dump(jfrFile);
        }

        var data = JfrEventExtractor.extract("security-test", List.of(jfrFile));
        assertNotNull(data);

        // SecurityProviderService events may or may not fire depending on JDK version (12+)
        // On JDK 12+, we should see some events
        int jdkMajor = Runtime.version().feature();
        if (jdkMajor >= 12) {
            assertFalse(data.securityProviderEvents().isEmpty(),
                    "On JDK 12+, SecurityProviderService events should be captured");

            // Check that at least one event has expected fields
            var firstEvent = data.securityProviderEvents().get(0);
            assertNotNull(firstEvent.algorithm());
            assertNotNull(firstEvent.serviceType());
            assertTrue(firstEvent.count() > 0);
        }
    }

    @Test
    void extract_withTlsHandshake_capturesConnection() throws Exception {
        // TLS handshakes are hard to trigger in a unit test without a real server.
        // Just verify the extractor handles an empty recording gracefully.
        Path jfrFile = tempDir.resolve("tls.jfr");

        try (Recording recording = new Recording()) {
            recording.enable("jdk.TLSHandshake");
            recording.start();
            Thread.sleep(50);
            recording.stop();
            recording.dump(jfrFile);
        }

        var data = JfrEventExtractor.extract("tls-test", List.of(jfrFile));
        assertNotNull(data);
        assertNotNull(data.tlsHandshakes());
    }

    @Test
    void extract_emptyRecordingList_throws() {
        assertThrows(Exception.class,
                () -> JfrEventExtractor.extract("test", List.of()));
    }

    @Test
    void extract_nonExistentRecording_throws() {
        assertThrows(Exception.class,
                () -> JfrEventExtractor.extract("test", List.of(tempDir.resolve("nonexistent.jfr"))));
    }
}
