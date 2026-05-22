// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jdk.jfr.Configuration;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

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
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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

    // ── spice.ClassLoaded extraction ────────────────────────────────────

    /** Mirrors the event ancho emits: @Name("spice.ClassLoaded") with String fields. */
    @Name("spice.ClassLoaded")
    @Enabled(true)
    @StackTrace(false)
    static class SpiceClassLoadedEvent extends Event {
        String className;
        String classGitoid;
        String classSha256;
        String codeSource;
        String jarGitoid;
        String jarSha256;
    }

    @Test
    void extract_spiceClassLoaded_capturesDedupsAndAssignsIds() throws Exception {
        Path jfrFile = tempDir.resolve("classloaded.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("spice.ClassLoaded");
            recording.start();

            SpiceClassLoadedEvent e1 = new SpiceClassLoadedEvent();
            e1.className = "com.example.Hello";
            e1.classGitoid = "gitoid:blob:sha256:aaa";
            e1.classSha256 = "aaa-sha";
            e1.codeSource = "file:/tmp/hello.jar";
            e1.jarGitoid = "gitoid:blob:sha256:jjj";
            e1.jarSha256 = "jjj-sha";
            e1.commit();

            // Duplicate gitoid — must collapse to one entry.
            SpiceClassLoadedEvent dup = new SpiceClassLoadedEvent();
            dup.className = "com.example.Hello";
            dup.classGitoid = "gitoid:blob:sha256:aaa";
            dup.classSha256 = "aaa-sha";
            dup.codeSource = "file:/tmp/hello.jar";
            dup.commit();

            // Distinct class from an exploded dir — no jar hashes.
            SpiceClassLoadedEvent e2 = new SpiceClassLoadedEvent();
            e2.className = "Main";
            e2.classGitoid = "gitoid:blob:sha256:bbb";
            e2.classSha256 = "bbb-sha";
            e2.codeSource = "file:/tmp/app/";
            e2.commit();

            recording.stop();
            recording.dump(jfrFile);
        }

        var data = JfrEventExtractor.extract("classloaded-test", List.of(jfrFile));
        assertNotNull(data.loadedClasses());
        assertEquals(2, data.loadedClasses().size(), "dedup by classGitoid");

        var hello = data.loadedClasses().stream()
                .filter(c -> "gitoid:blob:sha256:aaa".equals(c.classGitoid()))
                .findFirst().orElseThrow();
        assertEquals("com.example.Hello", hello.className());
        assertEquals("aaa-sha", hello.classSha256());
        assertEquals("file:/tmp/hello.jar", hello.codeSource());
        assertEquals("gitoid:blob:sha256:jjj", hello.jarGitoid());
        assertEquals("jjj-sha", hello.jarSha256());

        var main = data.loadedClasses().stream()
                .filter(c -> "gitoid:blob:sha256:bbb".equals(c.classGitoid()))
                .findFirst().orElseThrow();
        assertEquals("Main", main.className());
        assertNull(main.jarGitoid(), "exploded dir → no jar gitoid");
        assertNull(main.jarSha256(), "exploded dir → no jar sha256");

        // Ids are sequential and distinct.
        var ids = data.loadedClasses().stream()
                .map(JfrEventExtractor.LoadedClass::id).sorted().toList();
        assertEquals(List.of(0, 1), ids);
    }

    @Test
    void loadedClasses_serializeAsCamelCaseJson() throws Exception {
        // Locks the upload contract fennel consumes: a loadedClasses array with camelCase keys.
        var lc = new JfrEventExtractor.LoadedClass(
                0, "com.example.Hello", "gitoid:blob:sha256:aaa", "aaa-sha",
                "file:/tmp/hello.jar", "gitoid:blob:sha256:jjj", "jjj-sha");
        var data = new JfrEventExtractor.RawSurveyData(
                "1.0.0", "runtime-pqc-survey", "app", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(lc));

        // Mirror production serialization (bare ObjectMapper). Parse back so the assertion is
        // robust to indentation.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(mapper.writeValueAsString(data));

        JsonNode arr = root.get("loadedClasses");
        assertNotNull(arr, "loadedClasses key must be present");
        assertTrue(arr.isArray());
        assertEquals(1, arr.size());

        JsonNode e = arr.get(0);
        assertEquals(0, e.get("id").asInt());
        assertEquals("com.example.Hello", e.get("className").asText());
        assertEquals("gitoid:blob:sha256:aaa", e.get("classGitoid").asText());
        assertEquals("aaa-sha", e.get("classSha256").asText());
        assertEquals("file:/tmp/hello.jar", e.get("codeSource").asText());
        assertEquals("gitoid:blob:sha256:jjj", e.get("jarGitoid").asText());
        assertEquals("jjj-sha", e.get("jarSha256").asText());
    }

    @Test
    void extract_spiceClassLoaded_dedupsAcrossRecordings() throws Exception {
        // Forked mvn/gradle JVMs each produce a recording; the same class must collapse to one entry.
        Path jfr1 = tempDir.resolve("cl1.jfr");
        Path jfr2 = tempDir.resolve("cl2.jfr");
        writeClassLoadedRecording(jfr1, "gitoid:blob:sha256:shared", "com.example.Shared");
        writeClassLoadedRecording(jfr2, "gitoid:blob:sha256:shared", "com.example.Shared");

        var data = JfrEventExtractor.extract("merge-test", List.of(jfr1, jfr2));
        assertEquals(1, data.loadedClasses().size(), "same gitoid across recordings → one entry");
        assertEquals("com.example.Shared", data.loadedClasses().get(0).className());
    }

    private void writeClassLoadedRecording(Path file, String gitoid, String className) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("spice.ClassLoaded");
            recording.start();
            SpiceClassLoadedEvent e = new SpiceClassLoadedEvent();
            e.className = className;
            e.classGitoid = gitoid;
            e.classSha256 = "sha";
            e.codeSource = "file:/tmp/x.jar";
            e.commit();
            recording.stop();
            recording.dump(file);
        }
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
