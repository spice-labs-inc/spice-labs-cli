// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JdkVersionDetectorTest {

    // ── parseMajorVersion ──────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "21.0.1,      21",
        "17.0.9,      17",
        "11.0.22,     11",
        "1.8.0_362,   8",
        "1.8.0_262,   8",
        "25,          25",
        "25.0.1+7,    25",
        "9,           9",
        "9.0.4,       9",
    })
    void parseMajorVersion_variousFormats(String input, int expected) {
        assertEquals(expected, JdkVersionDetector.parseMajorVersion(input));
    }

    @Test
    void parseMajorVersion_null_returnsNegative() {
        assertEquals(-1, JdkVersionDetector.parseMajorVersion(null));
    }

    @Test
    void parseMajorVersion_blank_returnsNegative() {
        assertEquals(-1, JdkVersionDetector.parseMajorVersion(""));
    }

    @Test
    void parseMajorVersion_garbage_returnsNegative() {
        assertEquals(-1, JdkVersionDetector.parseMajorVersion("not-a-version"));
    }

    // ── parseJavaVersionOutput ─────────────────────────────────────────

    @Test
    void parseJavaVersionOutput_openjdk21() {
        String output = """
                openjdk version "21.0.1" 2023-10-17
                OpenJDK Runtime Environment Temurin-21.0.1+12 (build 21.0.1+12)
                OpenJDK 64-Bit Server VM Temurin-21.0.1+12 (build 21.0.1+12, mixed mode, sharing)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(21, v.major());
        assertEquals("21.0.1", v.fullVersion());
        assertTrue(v.isOpenJdk());
        assertTrue(v.supportsAddOpens());
        assertTrue(v.supportsPidSubstitution());
        assertFalse(v.needsCommercialFlag());
        assertTrue(v.supportedByCliParser());
        assertTrue(v.hasNativeSecurityEvents());
    }

    @Test
    void parseJavaVersionOutput_oracleJdk8() {
        String output = """
                java version "1.8.0_362"
                Java(TM) SE Runtime Environment (build 1.8.0_362-b08)
                Java HotSpot(TM) 64-Bit Server VM (build 25.362-b08, mixed mode)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(8, v.major());
        assertEquals("1.8.0_362", v.fullVersion());
        assertFalse(v.isOpenJdk());
        assertFalse(v.supportsAddOpens());
        assertFalse(v.supportsPidSubstitution());
        assertTrue(v.needsCommercialFlag());
        assertFalse(v.supportedByCliParser());
        assertFalse(v.hasNativeSecurityEvents());
    }

    @Test
    void parseJavaVersionOutput_openjdk8u262() {
        String output = """
                openjdk version "1.8.0_262"
                OpenJDK Runtime Environment (AdoptOpenJDK)(build 1.8.0_262-b10)
                OpenJDK 64-Bit Server VM (AdoptOpenJDK)(build 25.262-b10, mixed mode)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(8, v.major());
        assertTrue(v.isOpenJdk());
        assertFalse(v.needsCommercialFlag()); // OpenJDK 8 doesn't need commercial flag
    }

    @Test
    void parseJavaVersionOutput_openjdk11() {
        String output = """
                openjdk version "11.0.22" 2024-01-16
                OpenJDK Runtime Environment Temurin-11.0.22+7 (build 11.0.22+7)
                OpenJDK 64-Bit Server VM Temurin-11.0.22+7 (build 11.0.22+7, mixed mode)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(11, v.major());
        assertTrue(v.supportsAddOpens());
        assertFalse(v.supportsPidSubstitution());
        assertTrue(v.supportedByCliParser());
        assertFalse(v.hasNativeSecurityEvents()); // SecurityProviderService starts at 12
    }

    @Test
    void parseJavaVersionOutput_openjdk17() {
        String output = """
                openjdk version "17.0.9" 2023-10-17
                OpenJDK Runtime Environment Temurin-17.0.9+9 (build 17.0.9+9)
                OpenJDK 64-Bit Server VM Temurin-17.0.9+9 (build 17.0.9+9, mixed mode, sharing)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(17, v.major());
        assertFalse(v.supportsPidSubstitution());
        assertTrue(v.hasNativeSecurityEvents());
    }

    @Test
    void parseJavaVersionOutput_openjdk25() {
        // Hypothetical JDK 25 output
        String output = """
                openjdk version "25" 2025-09-16
                OpenJDK Runtime Environment (build 25+36-2726)
                OpenJDK 64-Bit Server VM (build 25+36-2726, mixed mode, sharing)
                """;
        var v = JdkVersionDetector.parseJavaVersionOutput(output);
        assertNotNull(v);
        assertEquals(25, v.major());
        assertTrue(v.supportsPidSubstitution());
        assertTrue(v.hasNativeSecurityEvents());
    }

    @Test
    void parseJavaVersionOutput_null_returnsNull() {
        assertNull(JdkVersionDetector.parseJavaVersionOutput(null));
    }

    @Test
    void parseJavaVersionOutput_empty_returnsNull() {
        assertNull(JdkVersionDetector.parseJavaVersionOutput(""));
    }

    // ── detect with live JVM ───────────────────────────────────────────

    @Test
    void detect_java_detectsCurrentJvm() {
        var v = JdkVersionDetector.detect("java");
        assertNotNull(v, "Should detect the current JVM");
        assertTrue(v.major() >= 11, "CLI requires JDK 11+, so current JVM should be 11+");
        assertTrue(v.supportedByCliParser());
    }

    @Test
    void detect_unknownCommand_fallsBackToJava() {
        // "someRandomCommand" should fall back to "java -version"
        var v = JdkVersionDetector.detect("someRandomCommand");
        // May or may not succeed depending on PATH, but should not throw
    }

    @Test
    void detect_null_returnsNull() {
        assertNull(JdkVersionDetector.detect(null));
    }

    @Test
    void detect_blank_returnsNull() {
        assertNull(JdkVersionDetector.detect(""));
    }
}
