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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import io.spicelabs.ginger.Ginger;
import io.spicelabs.goatrodeo.GoatRodeo;
import io.spicelabs.goatrodeo.GoatRodeoBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

@Command(
    name = "spice",
    mixinStandardHelpOptions = true,
    description = "Spice Labs Surveyor CLI",
    versionProvider = SpiceLabsCLI.VersionProvider.class
)
public class SpiceLabsCLI implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(SpiceLabsCLI.class);

  @Option(names = "--command", description = "run[default - surveys and uploads adgs]|survey-artifacts|upload-adgs|decode-spice-pass",
      converter = Command.Converter.class)
  Command command;

  @Option(names = "--input", description = "Input path")
  Path input;

  @Option(names = "--output", description = "Output path")
  Path output;

  @Option(names = "--ci", description = "CI mode")
  boolean ci;

  @Option(names = "--log-level", description = "all|trace|debug|info[default]|warn|error|fatal|off")
  String logLevel;

  @Option(names = "--threads", description = "Number of threads to use (default: half of available CPU cores)")
  Integer threads;

  @Option(names = "--use-static-metadata", description = "Augment Goat Rodeo information with other static metadata")
  boolean useStaticMetadata = true;

  @Option(names = "--max-records", description = "Max records to process per batch (default: 5000)")
  int maxRecords = 5000;

  @Option(names = "--tag", description = "Tag all top level artifacts (files) with the current date and the text of the tag")
  String tag;

  @Option(names = "--tag-json", description = "Add JSON to any tags")
  String tagJson;

  @Option(
      names = "--goat-rodeo-args",
      description = "Additional GoatRodeo builder args in key=value format (e.g. --goat-rodeo-args blockList=ignored,tempDir=/tmp)",
      split = ","
  )
  List<String> goatRodeoArgsRaw;

  protected Map<String, String> goatRodeoArgs = Map.of();

  String spicePass;

  public static void main(String[] args) {
    int exitCode;
    try {
      CommandLine cmd = new CommandLine(new SpiceLabsCLI())
          .setCaseInsensitiveEnumValuesAllowed(true);

      cmd.setParameterExceptionHandler(new CommandLine.IParameterExceptionHandler() {
        @Override
        public int handleParseException(CommandLine.ParameterException ex, String[] args) {
          log.error("❌ {}", ex.getMessage());
          log.info("Use -h or --help for usage information.");
          return cmd.getCommandSpec().exitCodeOnInvalidInput();
        }
      });

      exitCode = cmd.execute(args);
    } catch (Exception e) {
      log.error("Fatal error: {}", e.getMessage(), e);
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  public static SpiceLabsCLI builder() {
    return new SpiceLabsCLI();
  }

  // ── Java SDK API ─────────────────────────────

  public SpiceLabsCLI tag(String tag) {
    this.tag = tag;
    return this;
  }
  
  public SpiceLabsCLI command(Command cmd) {
    this.command = cmd;
    return this;
  }

  public SpiceLabsCLI input(Path input) {
    this.input = input;
    return this;
  }

  public SpiceLabsCLI output(Path output) {
    this.output = output;
    return this;
  }

  public SpiceLabsCLI ci(boolean ci) {
    this.ci = ci;
    return this;
  }

  public SpiceLabsCLI logLevel(String logLevel) {
    this.logLevel = logLevel;
    return this;
  }

  public SpiceLabsCLI spicePass(String spicePass) {
    this.spicePass = spicePass;
    return this;
  }

  public SpiceLabsCLI goatRodeoArgs(Map<String, String> args) {
    this.goatRodeoArgs = args;
    return this;
  }

  // ── CLI Entrypoint ───────────────────────────

  @Override
  public Integer call() throws Exception {
    // Configure logging only when used from CLI
    configureLogging();

    if (goatRodeoArgsRaw != null && !goatRodeoArgsRaw.isEmpty()) {
      goatRodeoArgs = goatRodeoArgsRaw.stream()
          .map(s -> s.split("=", 2))
          .filter(kv -> kv.length == 2)
          .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
    }

    try {
      run();
      return 0;
    } catch (IllegalArgumentException ex) {
      log.error("❌ {}", ex.getMessage());
      log.info("Use -h or --help for usage information.");
      return 1;
    } catch (Exception ex) {
      log.error("❌ {}", ex.getMessage());
      if (log.isDebugEnabled()) {
        log.error("Stack trace:", ex);
      }
      return 1;
    }
  }

  // ── Unified Runner ───────────────────────────

  public void run() throws Exception {
    if (command == null) {
      command = Command.run;
    }

    // Tag is only required for run and survey_artifacts commands
    if ((command == Command.run || command == Command.survey_artifacts) && (tag == null || tag.isBlank())) {
      throw new IllegalArgumentException("--tag= must be set for command: " + command);
    }

    if (threads == null) {
      int availableCores = Runtime.getRuntime().availableProcessors();
      threads = Math.max(1, Math.round(availableCores / 2.0f));
      log.info("Using {} threads (half of {} available CPU cores)", threads, availableCores);
    }

    if (input == null)
      input = Paths.get(System.getProperty("user.dir"));

    if ((command == Command.run || command == Command.survey_artifacts) && output == null)
      output = Files.createTempDirectory("spice-output-");

    if (spicePass == null || spicePass.isBlank())
      spicePass = getSpicePassEnv();

    if (command != Command.survey_artifacts && (spicePass == null || spicePass.isBlank()))
      throw new IllegalArgumentException("SPICE_PASS must be set via SPICE_PASS env var for command: " + command);

    // Log project info for run and upload_adgs commands
    if ((command == Command.run || command == Command.upload_adgs) && spicePass != null && !spicePass.isBlank()) {
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

    switch (command) {
      case survey_artifacts -> doSurvey();
      case upload_adgs -> doUploadAdgs(Optional.empty());
      case upload_deployment_events -> doUploadDeploymentEvents();
      case decode_spice_pass -> doDecodeSpicePass();
      case run -> doRunAll();
    }
  }

  // ── Internal Logic ───────────────────────────

  protected String getSpicePassEnv() {
    return System.getenv("SPICE_PASS");
  }

  private void configureLogging() {
    String levelStr = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
    Level level = Level.toLevel(levelStr, Level.INFO);

    ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    // Change log pattern based on level
    ch.qos.logback.classic.LoggerContext loggerContext = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.core.ConsoleAppender<?> consoleAppender = (ch.qos.logback.core.ConsoleAppender<?>) rootLogger.getAppender("STDOUT");

    if (consoleAppender != null && consoleAppender.getEncoder() instanceof ch.qos.logback.classic.encoder.PatternLayoutEncoder) {
      ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = (ch.qos.logback.classic.encoder.PatternLayoutEncoder) consoleAppender.getEncoder();
      encoder.stop();

      if (level.levelInt <= Level.DEBUG.levelInt) {
        // Debug or lower: show timestamp and abbreviated logger
        encoder.setPattern("%d{HH:mm:ss.SSS} %highlight(%-5level) %logger{36} - %msg%n");
      } else {
        // Info or higher: simple format
        encoder.setPattern("%highlight(%-5level) %msg%n");
      }

      encoder.setContext(loggerContext);
      encoder.start();
    }

    rootLogger.setLevel(level);

    // Only log the level if it was explicitly set (not default)
    if (logLevel != null) {
      log.info("Logging level set to {}", level);
    }

    System.setProperty("scala.logging.level", levelStr);
  }

  protected void doSurvey() throws Exception {
    log.info("📦 Surveying artifacts with GoatRodeo...");

    String originalScalaLevel = System.getProperty("scala.logging.level");
    String originalSlf4jLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");

    try {
      String level = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
      System.setProperty("scala.logging.level", level);
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);

      GoatRodeoBuilder builder = GoatRodeo.builder()
          .withPayload(input.toString())
          .withOutput(output.toString())
          .withThreads(threads)
          .withMaxRecords(maxRecords)
          .withStatisMetadata(useStaticMetadata)
          .withTag(tag)
          .withExtraArgs(goatRodeoArgs);

      if (tagJson != null && !tagJson.isBlank()) {
        builder.withTagJson(tagJson);
      }

      builder.run();
    } finally {
      if (originalScalaLevel != null)
        System.setProperty("scala.logging.level", originalScalaLevel);
      if (originalSlf4jLevel != null)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", originalSlf4jLevel);
    }
  }

  private void doUploadAdgs(Optional<Path> gingerInputDir) throws Exception {
    log.info("📦 Uploading ADGs...");
    Ginger ginger = Ginger.builder()
        .jwt(spicePass)
        .adgDir(gingerInputDir.orElse(input));

    if (output != null)
      ginger.outputDir(output);

    ginger.run();
  }

  private void doUploadDeploymentEvents() throws Exception {
    log.info("📦 Uploading deployment events...");
    Path tmp = Files.createTempFile("deploy-events-", ".json");
    try (OutputStream os = Files.newOutputStream(tmp)) {
      new BufferedInputStream(System.in).transferTo(os);
    }
    try {
      Ginger.builder()
          .jwt(spicePass)
          .deploymentEventsFile(tmp)
          .run();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private void doRunAll() throws Exception {
    doSurvey();
    doUploadAdgs(Optional.of(output));
  }

  private void doDecodeSpicePass() throws Exception {
    SpicePassDecoder decoder = new SpicePassDecoder(spicePass);
    decoder.printFullInfo();
  }

  // ── Enums ────────────────────────────────────

  public enum Command {
    run,
    survey_artifacts,
    upload_adgs,
    upload_deployment_events,
    decode_spice_pass;

    @Override
    public String toString() {
      return name().replace('_', '-');
    }

    public static class Converter implements ITypeConverter<Command> {
      @Override
      public Command convert(String value) {
        try {
          return Command.valueOf(value.toLowerCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
          throw new TypeConversionException("Invalid command: " + value + ", expected one of " +
              Arrays.stream(Command.values()).map(Command::toString).toList());
        }
      }
    }
  }

  /**
   * Provides the CLI version from pom.properties.
   */
  public static class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
      return new String[] { getVersionString() };
    }

    private static String getVersionString() {
      try (InputStream is = SpiceLabsCLI.class.getResourceAsStream(
              "/META-INF/maven/io.spicelabs/spice-labs-cli/pom.properties")) {
        if (is != null) {
          Properties props = new Properties();
          props.load(is);
          return props.getProperty("version", "unknown");
        }
      } catch (IOException ignored) {}
      return "unknown";
    }
  }
}
