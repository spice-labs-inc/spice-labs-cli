// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the JDK version of the target JVM by running {@code java -version},
 * {@code mvn -version}, or {@code gradle --version} and parsing the output.
 *
 * <p>Used to determine the correct JAVA_TOOL_OPTIONS strategy for JFR recording:
 * <ul>
 *   <li>JDK 8 (Oracle): needs {@code -XX:+UnlockCommercialFeatures}
 *   <li>JDK 8 (OpenJDK 8u262+): JFR without commercial flag
 *   <li>JDK 9-10: {@code --add-opens} supported, older JFR syntax
 *   <li>JDK 11-17: modern JFR, no {@code %p} PID substitution in filename
 *   <li>JDK 18+: {@code %p} PID substitution in JFR filename
 * </ul>
 */
public class JdkVersionDetector {

    private static final Logger log = LoggerFactory.getLogger(JdkVersionDetector.class);

    // Matches: openjdk version "21.0.1" or java version "1.8.0_362" etc.
    private static final Pattern JAVA_VERSION_PATTERN =
            Pattern.compile("(?:openjdk|java)\\s+version\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // Matches: Java version: 21.0.1 (from mvn -version output)
    private static final Pattern MVN_JAVA_VERSION_PATTERN =
            Pattern.compile("Java\\s+version:\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    // Matches: JVM: 21.0.1 (from gradle --version output)
    private static final Pattern GRADLE_JVM_PATTERN =
            Pattern.compile("JVM:\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    // Matches version strings like 21.0.1, 1.8.0_362, 17, 11.0.22+7
    private static final Pattern VERSION_NUMBER_PATTERN =
            Pattern.compile("^(?:1\\.)?(\\d+)");

    /**
     * Result of JDK version detection.
     */
    public record JdkVersion(int major, String fullVersion, boolean isOpenJdk) {

        /** Whether this JDK supports the {@code --add-opens} flag (JDK 9+). */
        public boolean supportsAddOpens() {
            return major >= 9;
        }

        /** Whether this JDK supports {@code %p} PID substitution in JFR filenames (JDK 18+). */
        public boolean supportsPidSubstitution() {
            return major >= 18;
        }

        /** Whether this JDK needs {@code -XX:+UnlockCommercialFeatures} for JFR (Oracle JDK 8). */
        public boolean needsCommercialFlag() {
            return major == 8 && !isOpenJdk;
        }

        /** Whether the built-in JFR parser in the CLI can process recordings from this JDK. */
        public boolean supportedByCliParser() {
            return major >= 11;
        }

        /** Whether native JDK security events (SecurityProviderService) are available. */
        public boolean hasNativeSecurityEvents() {
            return major >= 12;
        }
    }

    /**
     * Detect JDK version by running the appropriate version command.
     *
     * @param command the first word of the user's command (e.g., "java", "mvn", "gradle")
     * @return detected JDK version, or null if detection fails
     */
    public static JdkVersion detect(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }

        String baseCommand = command.contains("/") ? command.substring(command.lastIndexOf('/') + 1) : command;

        try {
            return switch (baseCommand.toLowerCase()) {
                case "java" -> detectFromJava(command);
                case "mvn", "mvnw", "./mvnw" -> detectFromMaven(command);
                case "gradle", "gradlew", "./gradlew" -> detectFromGradle(command);
                default -> {
                    // Try java -version as fallback (the command might be a wrapper script)
                    log.debug("Unknown command '{}', trying 'java -version' fallback", baseCommand);
                    yield detectFromJava("java");
                }
            };
        } catch (Exception e) {
            log.warn("Failed to detect JDK version from '{}': {}", command, e.getMessage());
            return null;
        }
    }

    /**
     * Parse a version string directly (for testing or when version output is already available).
     *
     * @param versionOutput the raw output from {@code java -version} (all lines concatenated)
     * @return detected JDK version, or null if parsing fails
     */
    public static JdkVersion parseJavaVersionOutput(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return null;
        }

        boolean isOpenJdk = versionOutput.toLowerCase().contains("openjdk");

        Matcher matcher = JAVA_VERSION_PATTERN.matcher(versionOutput);
        if (matcher.find()) {
            String fullVersion = matcher.group(1);
            int major = parseMajorVersion(fullVersion);
            if (major > 0) {
                return new JdkVersion(major, fullVersion, isOpenJdk);
            }
        }

        return null;
    }

    static JdkVersion detectFromJava(String javaCommand) throws Exception {
        String output = runCommand(javaCommand, "-version");
        return parseJavaVersionOutput(output);
    }

    static JdkVersion detectFromMaven(String mvnCommand) throws Exception {
        String output = runCommand(mvnCommand, "-version");
        if (output == null) return null;

        boolean isOpenJdk = output.toLowerCase().contains("openjdk");

        Matcher matcher = MVN_JAVA_VERSION_PATTERN.matcher(output);
        if (matcher.find()) {
            String fullVersion = matcher.group(1);
            int major = parseMajorVersion(fullVersion);
            if (major > 0) {
                return new JdkVersion(major, fullVersion, isOpenJdk);
            }
        }

        // Fallback: try java -version
        return detectFromJava("java");
    }

    static JdkVersion detectFromGradle(String gradleCommand) throws Exception {
        String output = runCommand(gradleCommand, "--version");
        if (output == null) return null;

        boolean isOpenJdk = output.toLowerCase().contains("openjdk");

        Matcher matcher = GRADLE_JVM_PATTERN.matcher(output);
        if (matcher.find()) {
            String fullVersion = matcher.group(1);
            int major = parseMajorVersion(fullVersion);
            if (major > 0) {
                return new JdkVersion(major, fullVersion, isOpenJdk);
            }
        }

        // Fallback: try java -version
        return detectFromJava("java");
    }

    /**
     * Parse the major version number from a version string.
     * Handles both old-style (1.8.0_362) and new-style (21.0.1) formats.
     */
    static int parseMajorVersion(String versionString) {
        if (versionString == null || versionString.isBlank()) {
            return -1;
        }
        Matcher m = VERSION_NUMBER_PATTERN.matcher(versionString);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Run a command and capture stderr + stdout (java -version writes to stderr).
     */
    static String runCommand(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        log.debug("Command '{}' exited with code {}", String.join(" ", args), exitCode);
        return output.toString();
    }
}
