// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * Unit tests for SurveyRuntimeCommand — command parsing, validation, and
 * JAVA_TOOL_OPTIONS construction.
 */
class SurveyRuntimeCommandTest {

    private CommandLine cli;

    @BeforeEach
    void setUp() {
        cli = new CommandLine(new SurveyRuntimeCommand());
    }

    // ── Command parsing ─────────────────────────────────────────────────

    @Test
    void missingJfrFlag_fails() {
        int code = cli.execute("my-app", "--", "java", "-jar", "app.jar");
        assertNotEquals(0, code);
    }

    @Test
    void missingSubject_fails() {
        int code = cli.execute("--jfr", "--", "java", "-jar", "app.jar");
        assertNotEquals(0, code);
    }

    // ── extractCommand ──────────────────────────────────────────────────

    @Test
    void extractCommand_separatorPresent() {
        var cmd = new SurveyRuntimeCommand();
        cmd.unmatched = List.of("--", "java", "-jar", "app.jar");
        assertEquals(List.of("java", "-jar", "app.jar"), cmd.extractCommand());
    }

    @Test
    void extractCommand_noSeparator_treatsAllAsCommand() {
        var cmd = new SurveyRuntimeCommand();
        cmd.unmatched = List.of("java", "-jar", "app.jar");
        assertEquals(List.of("java", "-jar", "app.jar"), cmd.extractCommand());
    }

    @Test
    void extractCommand_emptyUnmatched() {
        var cmd = new SurveyRuntimeCommand();
        cmd.unmatched = List.of();
        assertEquals(List.of(), cmd.extractCommand());
    }

    @Test
    void extractCommand_null() {
        var cmd = new SurveyRuntimeCommand();
        cmd.unmatched = null;
        assertEquals(List.of(), cmd.extractCommand());
    }

    // ── buildJavaToolOptions ────────────────────────────────────────────

    @Test
    void buildJavaToolOptions_nativeOnly_jdk21() throws Exception {
        var cmd = new SurveyRuntimeCommand();
        var jdk = new JdkVersionDetector.JdkVersion(21, "21.0.1", true);
        var tempDir = java.nio.file.Files.createTempDirectory("test-");
        var jfcPath = tempDir.resolve("spice-jfr.jfc");
        java.nio.file.Files.writeString(jfcPath, "<config/>");

        String opts = cmd.buildJavaToolOptions(jdk, tempDir, jfcPath, null, null);

        assertTrue(opts.contains("-XX:StartFlightRecording="));
        assertTrue(opts.contains("settings="));
        assertTrue(opts.contains("dumponexit=true"));
        assertTrue(opts.contains("filename="), "JDK 21 supports %p PID substitution");
        assertTrue(opts.contains("%p.jfr"));
        assertFalse(opts.contains("-javaagent"), "native-only should not include agent");
        assertFalse(opts.contains("--add-opens"), "native-only should not include --add-opens");

        SurveyInventoryCommand.deleteRecursively(tempDir);
    }

    @Test
    void buildJavaToolOptions_fullMode_jdk21() throws Exception {
        var cmd = new SurveyRuntimeCommand();
        var jdk = new JdkVersionDetector.JdkVersion(21, "21.0.1", true);
        var tempDir = java.nio.file.Files.createTempDirectory("test-");
        var jfcPath = tempDir.resolve("spice-jfr.jfc");
        var agentPath = tempDir.resolve("ancho.jar");
        var probePath = tempDir.resolve("probes.json");
        java.nio.file.Files.writeString(jfcPath, "<config/>");
        java.nio.file.Files.writeString(agentPath, "fake-agent");
        java.nio.file.Files.writeString(probePath, "{}");

        String opts = cmd.buildJavaToolOptions(jdk, tempDir, jfcPath, agentPath, probePath);

        assertTrue(opts.contains("-javaagent:"), "Full mode includes agent");
        assertTrue(opts.contains("ancho.jar="));
        assertTrue(opts.contains("probes.json"));
        assertTrue(opts.contains("--add-opens=java.base/com.sun.crypto.provider=ALL-UNNAMED"));
        assertTrue(opts.contains("--add-opens=java.base/javax.crypto=ALL-UNNAMED"));
        assertTrue(opts.contains("-XX:StartFlightRecording="));

        SurveyInventoryCommand.deleteRecursively(tempDir);
    }

    @Test
    void buildJavaToolOptions_oracleJdk8() throws Exception {
        var cmd = new SurveyRuntimeCommand();
        var jdk = new JdkVersionDetector.JdkVersion(8, "1.8.0_362", false);
        var tempDir = java.nio.file.Files.createTempDirectory("test-");
        var jfcPath = tempDir.resolve("spice-jfr.jfc");
        java.nio.file.Files.writeString(jfcPath, "<config/>");

        String opts = cmd.buildJavaToolOptions(jdk, tempDir, jfcPath, null, null);

        assertTrue(opts.contains("-XX:+UnlockCommercialFeatures"),
                "Oracle JDK 8 needs commercial features flag");
        assertTrue(opts.contains("-XX:+FlightRecorder"));
        assertFalse(opts.contains("filename="), "JDK 8 doesn't support %p");
        assertFalse(opts.contains("--add-opens"), "JDK 8 doesn't support --add-opens");

        SurveyInventoryCommand.deleteRecursively(tempDir);
    }

    @Test
    void buildJavaToolOptions_jdk11_noFilename() throws Exception {
        var cmd = new SurveyRuntimeCommand();
        var jdk = new JdkVersionDetector.JdkVersion(11, "11.0.22", true);
        var tempDir = java.nio.file.Files.createTempDirectory("test-");
        var jfcPath = tempDir.resolve("spice-jfr.jfc");
        java.nio.file.Files.writeString(jfcPath, "<config/>");

        String opts = cmd.buildJavaToolOptions(jdk, tempDir, jfcPath, null, null);

        assertFalse(opts.contains("filename="), "JDK <18 omits filename");
        assertTrue(opts.contains("dumponexit=true"));

        SurveyInventoryCommand.deleteRecursively(tempDir);
    }

    @Test
    void buildJavaToolOptions_jdk18_hasFilenameWithPid() throws Exception {
        var cmd = new SurveyRuntimeCommand();
        var jdk = new JdkVersionDetector.JdkVersion(18, "18.0.2", true);
        var tempDir = java.nio.file.Files.createTempDirectory("test-");
        var jfcPath = tempDir.resolve("spice-jfr.jfc");
        java.nio.file.Files.writeString(jfcPath, "<config/>");

        String opts = cmd.buildJavaToolOptions(jdk, tempDir, jfcPath, null, null);

        assertTrue(opts.contains("filename="), "JDK 18+ has filename");
        assertTrue(opts.contains("%p.jfr"), "JDK 18+ uses %p PID substitution");

        SurveyInventoryCommand.deleteRecursively(tempDir);
    }

    // ── humanReadableSize ───────────────────────────────────────────────

    @Test
    void humanReadableSize_variousSizes() {
        assertEquals("0 B", SurveyRuntimeCommand.humanReadableSize(0));
        assertEquals("512 B", SurveyRuntimeCommand.humanReadableSize(512));
        assertEquals("1.0 KB", SurveyRuntimeCommand.humanReadableSize(1024));
        assertEquals("1.5 MB", SurveyRuntimeCommand.humanReadableSize(1572864));
        assertEquals("1.0 GB", SurveyRuntimeCommand.humanReadableSize(1073741824));
    }

    // ── createDefaultJfc ────────────────────────────────────────────────

    @Test
    void createDefaultJfc_validXml() {
        String jfc = SurveyRuntimeCommand.createDefaultJfc();
        assertNotNull(jfc);
        assertTrue(jfc.contains("jdk.SecurityProviderService"));
        assertTrue(jfc.contains("jdk.TLSHandshake"));
        assertTrue(jfc.contains("jdk.X509Certificate"));
        assertTrue(jfc.contains("jdk.JVMInformation"));
        assertTrue(jfc.contains("jdk.SecurityPropertyModification"));
    }
}
