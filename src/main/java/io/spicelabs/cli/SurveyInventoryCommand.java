// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import io.spicelabs.ginger.Ginger;
import io.spicelabs.goatrodeo.GoatRodeo;
import io.spicelabs.goatrodeo.GoatRodeoBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Survey artifact inventory and optionally upload ADGs.
 *
 * Usage:
 *   spice survey inventory &lt;subject&gt; &lt;input&gt; [options]
 */
@Command(
    name = "inventory",
    description = "Survey artifact inventory and upload ADGs to Spice Labs",
    mixinStandardHelpOptions = true
)
public class SurveyInventoryCommand implements java.util.concurrent.Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(SurveyInventoryCommand.class);

  @Parameters(index = "0", description = "Label identifying the system being surveyed")
  String subject;

  @Parameters(index = "1", description = "Path to artifacts (directory or single file)")
  Path input;

  @Option(names = "--output", description = "Output directory for survey results")
  Path output;

  @Option(names = "--no-upload", description = "Survey only, skip upload")
  boolean noUpload;

  @Option(names = "--upload-only", description = "Upload previously-generated ADGs (skip survey)")
  boolean uploadOnly;

  @Option(names = "--tag-json", description = "Additional JSON metadata for tags")
  String tagJson;

  @Option(names = "--threads", description = "Number of threads to use (default: half of available CPU cores)")
  Integer threads;

  @Option(names = "--max-records", description = "Max records to process per batch (default: 5000)")
  int maxRecords = 5000;

  @Option(names = "--chunk-size", description = "Target chunk size in MB for uploads (default: 64)")
  Integer chunkSizeMB;

  @Option(names = "--log-level", description = "Log level: debug|info|warn|error (default: info)")
  String logLevel;

  @Option(names = "--log-file", description = "Path to log file (output appended to both console and file)")
  String logFile;

  @Option(
      names = "--goat-rodeo-args",
      description = "Additional GoatRodeo args in key=value format",
      split = ","
  )
  List<String> goatRodeoArgsRaw;

  @Option(
      names = "--ginger-args",
      description = "Additional Ginger args in key=value format",
      split = ","
  )
  List<String> gingerArgsRaw;

  // Parsed maps
  Map<String, String> goatRodeoArgs = Map.of();
  Map<String, String> gingerArgs = Map.of();

  // For testing — allow injection
  String spicePassOverride;

  @Override
  public Integer call() throws Exception {
    configureLogging();

    if (goatRodeoArgsRaw != null && !goatRodeoArgsRaw.isEmpty()) {
      goatRodeoArgs = ArgParser.parseKeyValueList(goatRodeoArgsRaw);
    }
    if (gingerArgsRaw != null && !gingerArgsRaw.isEmpty()) {
      gingerArgs = ArgParser.parseKeyValueList(gingerArgsRaw);
    }

    try {
      run();
      return 0;
    } catch (IllegalArgumentException ex) {
      log.error("❌ {}", ex.getMessage());
      log.info("Use --help for usage information.");
      return 1;
    } catch (Exception ex) {
      log.error("❌ {}", ex.getMessage());
      if (log.isDebugEnabled()) {
        log.error("Stack trace:", ex);
      }
      return 1;
    }
  }

  void run() throws Exception {
    log.info("🌶️  Spice Labs Surveyor CLI v{}", SpiceLabsCLI.VersionProvider.getVersionString());

    if (noUpload && uploadOnly) {
      throw new IllegalArgumentException("Cannot use both --no-upload and --upload-only");
    }

    if (threads == null) {
      int availableCores = Runtime.getRuntime().availableProcessors();
      threads = Math.max(1, Math.round(availableCores / 2.0f));
      log.info("Using {} threads (half of {} available CPU cores)", threads, availableCores);
    }

    // Resolve output directory
    if (output == null) {
      String userHome = System.getProperty("user.home");
      Path base;
      if (userHome != null && !userHome.isBlank() && !userHome.equals("/")) {
        base = Paths.get(userHome, ".spicelabs");
      } else {
        Path varTmp = Paths.get("/var/tmp", ".spicelabs");
        if (Files.isDirectory(Paths.get("/var/tmp")) || Files.exists(Paths.get("/var/tmp"))) {
          base = varTmp;
          log.warn("user.home not available, using /var/tmp/.spicelabs");
        } else {
          base = Paths.get("/tmp", ".spicelabs");
          log.warn("user.home and /var/tmp not available, using /tmp/.spicelabs");
        }
      }
      Files.createDirectories(base);
      output = base;
    }

    output = output.resolve("surveyor");
    Files.createDirectories(output);
    output = Files.createTempDirectory(output, "survey-");
    log.debug("Using output directory: {}", output);

    String spicePass = resolveSpicePass();

    if (!noUpload && (spicePass == null || spicePass.isBlank())) {
      throw new IllegalArgumentException(
          "SPICE_PASS must be set via SPICE_PASS env var for upload. Use --no-upload to skip upload.");
    }

    // Log project info for upload commands
    if (!noUpload && spicePass != null && !spicePass.isBlank()) {
      logProjectInfo(spicePass);
    }

    if (uploadOnly) {
      doUpload(spicePass, Optional.of(input));
    } else if (noUpload) {
      doSurvey();
    } else {
      doSurvey();
      doUpload(spicePass, Optional.of(output));
    }
  }

  protected void doSurvey() throws Exception {
    log.info("📦 Surveying artifacts with GoatRodeo...");

    String originalScalaLevel = System.getProperty("scala.logging.level");
    String originalSlf4jLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");

    Path surveyOutput = output.resolve("survey");
    Path tmpDir = output.resolve("tmp");
    Files.createDirectories(surveyOutput);
    Files.createDirectories(tmpDir);

    // If input is a single file, wrap it in a temp directory.
    Path payloadDir = input;
    Path singleFileDir = null;

    try {
      if (Files.isRegularFile(input)) {
        log.info("Single file input detected: {}", input.getFileName());
        singleFileDir = Files.createTempDirectory(input.toAbsolutePath().getParent(), "spice-single-file-");
        Path target = singleFileDir.resolve(input.getFileName());
        try {
          Files.createLink(target, input.toAbsolutePath());
        } catch (Exception e) {
          log.debug("Hard link failed ({}), falling back to copy", e.getMessage());
          Files.copy(input, target);
        }
        payloadDir = singleFileDir;
      }

      String level = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
      System.setProperty("scala.logging.level", level);
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);

      GoatRodeoBuilder builder = GoatRodeo.builder()
          .withPayload(payloadDir.toString())
          .withOutput(surveyOutput.toString())
          .withThreads(threads)
          .withMaxRecords(maxRecords)
          .withStaticMetadata(true)
          .withFsFilePaths(true)
          .withTag(subject)
          .withTempDir(tmpDir.toString())
          .withExtraArgs(goatRodeoArgs);

      if (tagJson != null && !tagJson.isBlank()) {
        builder.withTagJson(tagJson);
      }

      builder.run();
    } finally {
      if (singleFileDir != null) {
        deleteRecursively(singleFileDir);
      }
      if (originalScalaLevel != null) {
        System.setProperty("scala.logging.level", originalScalaLevel);
      }
      if (originalSlf4jLevel != null) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", originalSlf4jLevel);
      }
      deleteRecursively(tmpDir);
    }
  }

  private void doUpload(String spicePass, Optional<Path> gingerInputDir) throws Exception {
    log.info("📦 Uploading ADGs...");

    Map<String, String> gingerArgsMap = new HashMap<>(gingerArgs);
    if (chunkSizeMB != null && chunkSizeMB > 0) {
      gingerArgsMap.put("--target-chunk-size", chunkSizeMB.toString());
      log.info("Using target chunk size: {}MB", chunkSizeMB);
    }

    Ginger ginger = Ginger.builder()
        .jwt(spicePass)
        .adgDir(gingerInputDir.orElse(input))
        .extraArgs(gingerArgsMap);

    if (output != null)
      ginger.outputDir(output);

    ginger.run();
  }

  private String resolveSpicePass() {
    if (spicePassOverride != null && !spicePassOverride.isBlank()) {
      return spicePassOverride;
    }
    return System.getenv("SPICE_PASS");
  }

  private void logProjectInfo(String spicePass) {
    try {
      SpicePassDecoder decoder = new SpicePassDecoder(spicePass);
      String projectId = decoder.getProjectId();
      if (projectId != null) {
        log.info("Project ID: {}", projectId);
      }
      java.time.Instant expiresAt = decoder.getExpiresAt();
      if (expiresAt != null) {
        log.info("Spice Pass Expires At: {}", expiresAt);
      }
      log.info("Spice Pass Status: {}", decoder.getStatus());
    } catch (Exception e) {
      log.warn("Failed to decode SPICE_PASS: {}", e.getMessage());
    }
  }

  private void configureLogging() {
    String levelStr = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
    Level level = Level.toLevel(levelStr, Level.INFO);

    ch.qos.logback.classic.Logger rootLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    ch.qos.logback.classic.LoggerContext loggerContext =
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.core.ConsoleAppender<?> consoleAppender =
        (ch.qos.logback.core.ConsoleAppender<?>) rootLogger.getAppender("STDOUT");

    if (consoleAppender != null &&
        consoleAppender.getEncoder() instanceof ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder) {
      encoder.stop();
      if (level.levelInt <= Level.DEBUG.levelInt) {
        encoder.setPattern("%d{HH:mm:ss.SSS} %highlight(%-5level) %logger{36} - %msg%n");
      } else {
        encoder.setPattern("%highlight(%-5level) %msg%n");
      }
      encoder.setContext(loggerContext);
      encoder.start();
    }

    rootLogger.setLevel(level);

    // Libraries now use appropriate log levels at source:
    // - GoatRodeo: progress/internal chatter is DEBUG, milestones are INFO
    // - Ginger-J: upload progress/status is INFO
    // No blanket suppression needed.

    if (logLevel != null) {
      log.info("Logging level set to {}", level);
    }

    System.setProperty("scala.logging.level", levelStr);
  }

  static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) return;
    Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.deleteIfExists(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) throw exc;
        Files.deleteIfExists(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
