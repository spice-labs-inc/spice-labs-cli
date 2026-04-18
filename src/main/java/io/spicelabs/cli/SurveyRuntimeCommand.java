// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ch.qos.logback.classic.Level;
import io.spicelabs.ginger.Ginger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

/**
 * Survey runtime crypto usage via JFR instrumentation.
 *
 * <p>Usage:
 * <pre>
 *   spice survey runtime my-app --jfr -- java -jar app.jar
 *   spice survey runtime my-app --jfr -- mvn test
 *   spice survey runtime my-app --jfr --native-only -- gradle test
 * </pre>
 */
@Command(
    name = "runtime",
    description = "Survey runtime crypto usage via JFR instrumentation",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Examples:",
        "  # Profile a running Java app via JFR and upload results",
        "  spice survey runtime my-app --jfr -- java -jar app.jar",
        "",
        "  # Profile a Maven test run without uploading",
        "  spice survey runtime my-app --jfr --no-upload -- mvn test",
        "",
        "  # Native-only (no Java agent injection)",
        "  spice survey runtime my-app --jfr --native-only -- gradle test",
        "",
        "  # Keep the JFR recording on disk after the run",
        "  spice survey runtime my-app --jfr --keep-recording --output ./rec -- java -jar app.jar",
        "",
        "Everything after `--` is the target command to instrument and run.",
        ""
    }
)
public class SurveyRuntimeCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SurveyRuntimeCommand.class);

    private static final String AGENT_RESOURCE_PATH = "/ancho.jar";
    private static final String JFC_RESOURCE_PATH = "/jfr/spice-jfr.jfc";
    private static final long JFR_SIZE_WARN_THRESHOLD = 1_073_741_824L; // 1 GB

    @Parameters(index = "0", description = "Label identifying the system being surveyed")
    String subject;

    @Option(names = "--jfr", required = true,
            description = "Use Java Flight Recorder instrumentation")
    boolean jfr;

    @Unmatched
    List<String> unmatched;

    @Option(names = "--native-only",
            description = "Use only native JDK security events (no agent)")
    boolean nativeOnly;

    @Option(names = "--keep-recording",
            description = "Don't delete .jfr files after upload")
    boolean keepRecording;

    @Option(names = "--no-upload",
            description = "Analyze locally, don't upload results")
    boolean noUpload;

    @Option(names = "--output",
            description = "Output directory for temp files")
    Path output;

    @Option(names = "--log-level",
            description = "Log level: debug|info|warn|error (default: info)")
    String logLevel;

    @Option(names = "--chunk-size",
            description = "Target chunk size in MB for uploads (default: 64)")
    Integer chunkSizeMB;

    // For testing — allow injection
    String spicePassOverride;

    @Override
    public Integer call() {
        try {
            configureLogging();
            return run();
        } catch (IllegalArgumentException ex) {
            log.error("\u274c {}", ex.getMessage());
            log.info("Use --help for usage information.");
            return 1;
        } catch (Exception ex) {
            log.error("\u274c {}", ex.getMessage());
            if (log.isDebugEnabled()) {
                log.error("Stack trace:", ex);
            }
            return 1;
        }
    }

    int run() throws Exception {
        log.info("\uD83C\uDF36\uFE0F  Spice Labs Runtime Survey v{}", SpiceLabsCLI.VersionProvider.getVersionString());

        // 1. Validate inputs
        List<String> command = extractCommand();
        if (command.isEmpty()) {
            throw new IllegalArgumentException(
                    "No command specified. Use -- to separate the command to execute.\n" +
                    "Example: spice survey runtime my-app --jfr -- java -jar app.jar");
        }

        String spicePass = resolveSpicePass();
        if (!noUpload && !hasSpicePass(spicePass)) {
            throw new IllegalArgumentException(
                    "SPICE_PASS must be set for upload. Use --no-upload to run locally.");
        }

        // 2. Detect target JDK version
        String firstWord = command.get(0);
        log.debug("Detecting JDK version from command: {}", firstWord);
        JdkVersionDetector.JdkVersion jdkVersion = JdkVersionDetector.detect(firstWord);

        if (jdkVersion == null) {
            log.warn("\u26A0\uFE0F  Could not detect JDK version. Assuming JDK 21 defaults.");
            jdkVersion = new JdkVersionDetector.JdkVersion(21, "unknown", true);
        } else {
            log.debug("Detected JDK {}{}", jdkVersion.major(),
                    jdkVersion.isOpenJdk() ? " (OpenJDK)" : " (Oracle)");
        }

        if (!jdkVersion.supportedByCliParser()) {
            throw new IllegalArgumentException(
                    "JDK " + jdkVersion.major() + " is not supported by the JFR parser. " +
                    "JDK 11+ is required for the CLI to parse JFR recordings.");
        }

        // 3. Create temp directory
        Path tempDir = createTempDir();
        log.debug("Using temp directory: {}", tempDir);

        try {
            // 4. Extract bundled resources + download probe config
            Path jfcPath = extractBundledJfc(tempDir);
            Path agentPath = null;
            Path probeConfigPath = null;

            if (!nativeOnly) {
                agentPath = extractBundledAgent(tempDir);
                if (agentPath == null) {
                    log.warn("\u26A0\uFE0F  Agent JAR not found in classpath. Falling back to native-only mode.");
                    nativeOnly = true;
                } else {
                    // Download probe config from server
                    probeConfigPath = tempDir.resolve("probes.json");
                    if (!noUpload && hasSpicePass(spicePass)) {
                        log.debug("Downloading probe config from server...");
                        boolean downloaded = Ginger.builder()
                                .jwt(spicePass)
                                .downloadRuntimeConfig(probeConfigPath);
                        if (!downloaded) {
                            log.warn("\u26A0\uFE0F  Failed to download probe config. Falling back to native-only mode.");
                            nativeOnly = true;
                            agentPath = null;
                        }
                    } else {
                        log.debug("Running in offline mode (--no-upload), skipping probe config download.");
                        log.debug("Using native-only mode for offline operation.");
                        nativeOnly = true;
                        agentPath = null;
                    }
                }
            }

            // 5. Build JAVA_TOOL_OPTIONS
            String javaToolOptions = buildJavaToolOptions(jdkVersion, tempDir, jfcPath, agentPath, probeConfigPath);
            log.debug("JAVA_TOOL_OPTIONS: {}", javaToolOptions);

            // 6. Execute user command
            log.info("\uD83D\uDE80 Executing: {}", String.join(" ", command));
            int exitCode = executeCommand(command, javaToolOptions);
            log.debug("Command exited with code {}", exitCode);

            if (exitCode != 0) {
                log.warn("\u26A0\uFE0F  Target command exited with non-zero code {}. Will still collect recordings.", exitCode);
            }

            // 7. Collect JFR recordings
            List<Path> recordings = collectRecordings(tempDir, jdkVersion);
            if (recordings.isEmpty()) {
                log.error("\u274c No JFR recordings found. The target application may not have produced any.");
                log.error("Troubleshooting:");
                log.error("  - Is the target application a Java process?");
                log.error("  - Did it start and run long enough to produce events?");
                log.error("  - Check for JFR initialization errors in the target's output.");
                return 1;
            }

            long totalSize = recordings.stream().mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
            log.debug("Found {} recording(s), total size: {}",
                    recordings.size(), humanReadableSize(totalSize));
            if (totalSize > JFR_SIZE_WARN_THRESHOLD) {
                log.warn("\u26A0\uFE0F  Total recording size exceeds 1GB. This may indicate excessive instrumentation.");
            }

            // 8. Parse recordings
            log.debug("Parsing JFR recordings...");
            JfrEventExtractor.RawSurveyData data = JfrEventExtractor.extract(subject, recordings);

            // 9. Print summary
            printSummary(data);

            // 10. Upload or save locally
            if (!noUpload) {
                Path jsonPath = tempDir.resolve("survey-data.json");
                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(jsonPath.toFile(), data);

                log.debug("Uploading survey results...");
                doUpload(spicePass, jsonPath);
                log.info("\u2705 Upload complete.");
            } else {
                log.debug("--no-upload specified. Remove --no-upload to send results to Spice Labs for full categorization.");
            }

            return exitCode;

        } finally {
            // 11. Clean up
            if (!keepRecording) {
                SurveyInventoryCommand.deleteRecursively(tempDir);
            } else {
                log.info("Recordings kept in: {}", tempDir);
            }
        }
    }

    // ── Command extraction ──────────────────────────────────────────────

    /**
     * Extract the user's command from unmatched args.
     * PicoCLI puts everything after -- into the unmatched list.
     */
    List<String> extractCommand() {
        if (unmatched == null || unmatched.isEmpty()) {
            return List.of();
        }
        // Filter out the "--" separator if present
        List<String> cmd = new ArrayList<>();
        boolean pastSeparator = false;
        for (String arg : unmatched) {
            if ("--".equals(arg) && !pastSeparator) {
                pastSeparator = true;
                continue;
            }
            if (pastSeparator) {
                cmd.add(arg);
            }
        }
        // If no separator found, treat all unmatched as the command
        if (!pastSeparator) {
            cmd.addAll(unmatched);
        }
        return cmd;
    }

    // ── JAVA_TOOL_OPTIONS construction ──────────────────────────────────

    String buildJavaToolOptions(JdkVersionDetector.JdkVersion jdk, Path tempDir,
                                        Path jfcPath, Path agentPath, Path probeConfigPath) {
        List<String> parts = new ArrayList<>();

        // Agent (if not native-only)
        if (agentPath != null && probeConfigPath != null) {
            parts.add("-javaagent:" + agentPath.toAbsolutePath() + "=" + probeConfigPath.toAbsolutePath());
        }

        // JFR recording
        StringBuilder jfrOpts = new StringBuilder("-XX:StartFlightRecording=");
        jfrOpts.append("settings=").append(jfcPath.toAbsolutePath());
        jfrOpts.append(",dumponexit=true");

        if (jdk.supportsPidSubstitution()) {
            jfrOpts.append(",filename=").append(tempDir.toAbsolutePath()).append("/recording-%p.jfr");
        }
        // For JDK <18 we omit filename and glob later

        parts.add(jfrOpts.toString());

        // Commercial flag for Oracle JDK 8
        if (jdk.needsCommercialFlag()) {
            parts.add(0, "-XX:+UnlockCommercialFeatures");
            parts.add(1, "-XX:+FlightRecorder");
        }

        // --add-opens for agent instrumentation of JDK internal classes (JDK 9+)
        if (jdk.supportsAddOpens() && agentPath != null) {
            parts.add("--add-opens=java.base/com.sun.crypto.provider=ALL-UNNAMED");
            parts.add("--add-opens=java.base/sun.security.provider=ALL-UNNAMED");
            parts.add("--add-opens=java.base/sun.security.ssl=ALL-UNNAMED");
            parts.add("--add-opens=java.base/sun.security.ec=ALL-UNNAMED");
            parts.add("--add-opens=java.base/javax.crypto=ALL-UNNAMED");
            parts.add("--add-opens=java.base/java.security=ALL-UNNAMED");
        }

        return String.join(" ", parts);
    }

    // ── Process execution ───────────────────────────────────────────────

    int executeCommand(List<String> command, String javaToolOptions) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();

        // Set JAVA_TOOL_OPTIONS, preserving any existing value
        Map<String, String> env = pb.environment();
        String existing = env.get("JAVA_TOOL_OPTIONS");
        if (existing != null && !existing.isBlank()) {
            env.put("JAVA_TOOL_OPTIONS", javaToolOptions + " " + existing);
        } else {
            env.put("JAVA_TOOL_OPTIONS", javaToolOptions);
        }

        Process process = pb.start();
        return process.waitFor();
    }

    // ── Recording collection ────────────────────────────────────────────

    List<Path> collectRecordings(Path tempDir, JdkVersionDetector.JdkVersion jdk) throws IOException {
        List<Path> recordings = new ArrayList<>();

        // Always check temp dir
        collectJfrFiles(tempDir, recordings);

        // For JDK <18, also check working directory and build output dirs
        if (!jdk.supportsPidSubstitution()) {
            Path cwd = Paths.get("").toAbsolutePath();
            collectJfrFiles(cwd, recordings);

            // Common build output dirs
            for (String dir : List.of("target", "build", "build/reports")) {
                Path buildDir = cwd.resolve(dir);
                if (Files.isDirectory(buildDir)) {
                    collectJfrFiles(buildDir, recordings);
                }
            }
        }

        return recordings;
    }

    private void collectJfrFiles(Path dir, List<Path> target) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".jfr"))
                 .filter(Files::isRegularFile)
                 .forEach(target::add);
        }
    }

    // ── Resource extraction ─────────────────────────────────────────────

    Path extractBundledJfc(Path tempDir) throws IOException {
        Path jfcPath = tempDir.resolve("spice-jfr.jfc");
        try (InputStream is = getClass().getResourceAsStream(JFC_RESOURCE_PATH)) {
            if (is != null) {
                Files.copy(is, jfcPath, StandardCopyOption.REPLACE_EXISTING);
                return jfcPath;
            }
        }
        // Create a default JFC if not bundled
        log.debug("Bundled JFC not found, creating default configuration");
        String defaultJfc = createDefaultJfc();
        Files.writeString(jfcPath, defaultJfc);
        return jfcPath;
    }

    Path extractBundledAgent(Path tempDir) throws IOException {
        Path agentPath = tempDir.resolve("ancho.jar");
        try (InputStream is = getClass().getResourceAsStream(AGENT_RESOURCE_PATH)) {
            if (is != null) {
                Files.copy(is, agentPath, StandardCopyOption.REPLACE_EXISTING);
                return agentPath;
            }
        }
        log.debug("Agent JAR not found on classpath at {}", AGENT_RESOURCE_PATH);
        return null;
    }

    // ── Upload ──────────────────────────────────────────────────────────

    private void doUpload(String spicePass, Path rawEventsJson) throws Exception {
        Map<String, String> gingerArgsMap = new HashMap<>();
        if (chunkSizeMB != null) {
            gingerArgsMap.put("--target-chunk-size", chunkSizeMB.toString());
        }

        Ginger.builder()
                .jwt(spicePass)
                .runtimeSurveyFile(rawEventsJson)
                .extraArgs(gingerArgsMap)
                .run();
    }

    // ── Console output ──────────────────────────────────────────────────

    void printSummary(JfrEventExtractor.RawSurveyData data) {
        log.debug("");
        log.debug("\uD83C\uDF36\uFE0F  Spice Labs Runtime Survey \u2014 JFR Analysis");
        log.debug("");

        if (data.runtime() != null) {
            var rt = data.runtime();
            if (rt.jvmVersion() != null) log.debug("Runtime: {} ({})", rt.jvmVersion(), rt.jvmVendor());
            if (rt.os() != null) log.debug("OS:      {}", rt.os());
            log.debug("");
        }

        int totalEvents = data.probeEvents().size() + data.securityProviderEvents().size();
        log.debug("Recordings: {} file(s), {} distinct events",
                data.recordings().size(), totalEvents);
        log.debug("");

        if (!data.securityProviderEvents().isEmpty()) {
            log.debug("JDK Security Provider Events:");
            for (var evt : data.securityProviderEvents()) {
                log.debug("  {}: {}  {}x", evt.serviceType(), evt.algorithm(), evt.count());
            }
            log.debug("");
        }

        if (!data.probeEvents().isEmpty()) {
            log.debug("Probe Events:");
            for (var evt : data.probeEvents()) {
                log.debug("  {}.{}  {}x", evt.classFqn(), evt.methodName(), evt.count());
            }
            log.debug("");
        }

        if (!data.tlsHandshakes().isEmpty()) {
            log.debug("TLS Connections:");
            for (var tls : data.tlsHandshakes()) {
                log.debug("  {}:{}  {} / {}  {}x",
                        tls.peerHost(), tls.peerPort(), tls.protocol(), tls.cipherSuite(), tls.count());
            }
            log.debug("");
        }

        if (!data.certificates().isEmpty()) {
            log.debug("Certificates:");
            for (var cert : data.certificates()) {
                log.debug("  {}  {}-{} {}  expires {}",
                        cert.subject(), cert.keyType(), cert.keyLength(), cert.sigAlgo(), cert.validUntil());
            }
            log.debug("");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Path createTempDir() throws IOException {
        Path base;
        if (output != null) {
            base = output;
        } else {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank() && !userHome.equals("/")) {
                base = Paths.get(userHome, ".spicelabs", "runtime-survey");
            } else {
                base = Paths.get("/tmp", ".spicelabs", "runtime-survey");
            }
        }
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "survey-");
    }

    private String resolveSpicePass() {
        if (spicePassOverride != null && !spicePassOverride.isBlank()) {
            return spicePassOverride;
        }
        return System.getenv("SPICE_PASS");
    }

    private static boolean hasSpicePass(String spicePass) {
        return spicePass != null && !spicePass.isBlank();
    }

    private void configureLogging() {
        Level level = LogLevelParser.parse(logLevel);
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(level);
    }

    static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Create a default JFC settings file that enables security-relevant events.
     */
    static String createDefaultJfc() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0" label="Spice Labs PQC Survey" provider="Spice Labs">
                  <event name="jdk.SecurityProviderService">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">true</setting>
                  </event>
                  <event name="jdk.TLSHandshake">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">true</setting>
                  </event>
                  <event name="jdk.X509Certificate">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">false</setting>
                  </event>
                  <event name="jdk.X509Validation">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">false</setting>
                  </event>
                  <event name="jdk.SecurityPropertyModification">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">true</setting>
                  </event>
                  <event name="jdk.InitialSecurityProperty">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">false</setting>
                  </event>
                  <event name="jdk.JVMInformation">
                    <setting name="enabled">true</setting>
                    <setting name="period">beginChunk</setting>
                  </event>
                  <event name="jdk.OSInformation">
                    <setting name="enabled">true</setting>
                    <setting name="period">beginChunk</setting>
                  </event>
                  <event name="jdk.InitialSystemProperty">
                    <setting name="enabled">true</setting>
                    <setting name="period">beginChunk</setting>
                  </event>
                </configuration>
                """;
    }
}
