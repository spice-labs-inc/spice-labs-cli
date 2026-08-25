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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.spicelabs.config.LogbackLogging;
import io.spicelabs.config.Logging;
import io.spicelabs.config.Names;
import io.spicelabs.config.Resolution;
import io.spicelabs.config.Setting;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import io.spicelabs.cli.spi.SpicePassClaims;
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
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Examples:",
        "  # Survey a directory of artifacts and upload",
        "  spice survey inventory my-app ./build/libs",
        "",
        "  # Survey a single file, skip upload, write output to ./out",
        "  spice survey inventory my-app ./app.jar --no-upload --output ./out",
        "",
        "  # Attach JSON tag metadata",
        "  spice survey inventory my-app ./dist --tag-json='{\"env\":\"prod\"}'",
        "",
        "  # Upload previously-surveyed ADGs",
        "  spice survey inventory my-app ./out --upload-only",
        "",
        "SPICE_PASS must be set in the environment for upload.",
        ""
    }
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
  Integer maxRecords;

  @Option(names = "--chunk-size", description = "Target chunk size in MB for uploads (default: 64)")
  Integer chunkSizeMB;

  @Option(names = "--log-level", description = "Log level: debug|info|warn|error (default: info)")
  String logLevel;

  @Option(names = "--log-file", description = "Path to log file (output appended to both console and file)")
  String logFile;

  @Option(
      names = "--analysis-args",
      description = "Additional analysis args in key=value format",
      split = ","
  )
  List<String> goatRodeoArgsRaw;

  @Option(
      names = "--upload-args",
      description = "Additional upload args in key=value format",
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
    try {
      configureLogging();

      if (goatRodeoArgsRaw != null) {
        goatRodeoArgs = ArgParser.parseKeyValueList(goatRodeoArgsRaw);
      }
      if (gingerArgsRaw != null) {
        gingerArgs = ArgParser.parseKeyValueList(gingerArgsRaw);
      }

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

    if (!Files.exists(input)) {
      throw new IllegalArgumentException("Input path does not exist: " + input);
    }

    if (Files.isDirectory(input)) {
      try (var entries = Files.list(input)) {
        if (entries.findFirst().isEmpty()) {
          throw new IllegalArgumentException("Input directory is empty: " + input);
        }
      }
    }

    if (threads != null && threads < 1) {
      throw new IllegalArgumentException("--threads must be at least 1, got: " + threads);
    }

    if (tagJson != null && !tagJson.isBlank()) {
      validateTagJson(tagJson);
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

    if (!noUpload && !hasSpicePass(spicePass)) {
      throw new IllegalArgumentException(
          "SPICE_PASS must be set via SPICE_PASS env var for upload. Use --no-upload to skip upload.");
    }

    // Log project info for upload commands
    if (!noUpload && hasSpicePass(spicePass)) {
      logProjectInfo(spicePass);
    }

    // Register the survey with the server before any local work, so we get the
    // server-minted submission timestamp (the authoritative bundle date) up front.
    SurveyRegistration.Context survey = null;
    if (!noUpload && !isEncryptOnly() && hasSpicePass(spicePass)) {
      survey = initSurvey(spicePass);
    }

    // Progress publisher for the ANALYZE sub-job. Only wired when we ran a real initSurvey
    // and daikon minted an analyzeSubJobId; the upload-only / no-upload paths skip it
    // because no local analyze runs (upload-only) or no survey was registered (no-upload).
    AnalyzeProgressPublisher analyzeProgress = null;
    if (survey != null && survey.analyzeSubJobId() != null) {
      Ginger statusPublisher = Ginger.builder()
          .jwt(spicePass)
          .parentId(survey.parentId())
          .idempotencyKey(survey.idempotencyKey())
          .userAgent(survey.userAgent());
      analyzeProgress = new AnalyzeProgressPublisher(statusPublisher::publishStatus, survey.analyzeSubJobId());
    }

    if (uploadOnly) {
      doUpload(spicePass, Optional.of(input), survey, null);
    } else if (noUpload) {
      doSurvey(null, null);
    } else {
      doSurvey(survey, analyzeProgress);
      doUpload(spicePass, Optional.of(output), survey, analyzeProgress);
    }
  }

  /**
   * Register the survey with the server before any local work, then keep the server-minted
   * submission timestamp for use as the bundle date. Fails the run if registration fails.
   */
  private SurveyRegistration.Context initSurvey(String spicePass) throws Exception {
    Map<String, Object> jsonTags = null;
    if (tagJson != null && !tagJson.isBlank()) {
      jsonTags = new ObjectMapper().readValue(tagJson, new TypeReference<Map<String, Object>>() {});
    }
    log.info("Registering survey with Spice Labs...");
    SurveyRegistration.Context survey =
        SurveyRegistration.register(spicePass, "INVENTORY_SURVEY", subject, jsonTags);
    log.info("Survey registered (submission time {})", survey.submissionTimestamp());
    return survey;
  }

  /**
   * The configuration groups this command reads.
   *
   * <p>Named for the job rather than the component: {@code analysis} is the same group
   * {@code spice registry} claims, so {@code [analysis] threads = 16} written once in the
   * config file governs both. Declared here for the same reason a plugin declares its
   * claims — it is what lets a table nobody reads be reported as a probable typo.
   */
  static final List<String> GROUPS = List.of("analysis", "upload", "logging");

  /** Where this command's command-scoped overrides live: {@code [survey.inventory.*]}. */
  static final List<String> COMMAND_PATH = List.of("survey", "inventory");

  /**
   * Decide every setting for this run.
   *
   * <p>Defaults, then the shared group, then the command-scoped group, then the
   * environment, then the flags — and any disagreement between two of those is reported as
   * it is resolved, because this is the only place they meet.
   *
   * <p>The flags are <em>bindings onto group keys</em>, not values of their own. That is the
   * point of the whole exercise: before this, {@code --threads} and
   * {@code [survey.inventory.analysis] threads} were separate routes to the same engine
   * setting, and nothing reconciled them.
   */
  Resolution resolveSettings() {
    int cores = Runtime.getRuntime().availableProcessors();
    long halfTheCores = Math.max(1, Math.round(cores / 2.0f));
    return RunConfiguration.current()
        .resolverFor(COMMAND_PATH, GROUPS)
        .withDefaults(Map.of(
            "analysis", Map.of("threads", halfTheCores, "max_records", 5000L),
            "upload", Map.of("target_chunk_size", 64L),
            "logging", Map.of("level", "INFO")))
        .withFlag("analysis", "threads", threads, "--threads")
        .withFlag("analysis", "max_records", maxRecords, "--max-records")
        .withFlag("upload", "target_chunk_size", chunkSizeMB, "--chunk-size")
        .withFlag("logging", "level", logLevel, "--log-level")
        .withFlag("logging", "file", logFile, "--log-file")
        .resolve();
  }

  protected void doSurvey(SurveyRegistration.Context survey, AnalyzeProgressPublisher analyzeProgress)
      throws Exception {
    log.info("📦 Surveying artifacts...");

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

      // Every setting this command's components read, from every source, decided in one
      // place. `--threads` and `--max-records` are bindings onto [analysis] keys rather
      // than values of their own, so there is one `threads` and not one per route.
      Resolution settings = resolveSettings();

      String level = settings.setting("logging", "level").map(Setting::asString)
          .map(String::toUpperCase).orElse("INFO");
      System.setProperty("scala.logging.level", level);
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);

      GoatRodeoBuilder builder = GoatRodeo.builder()
          .withPayload(payloadDir.toString())
          .withOutput(surveyOutput.toString())
          .withStaticMetadata(true)
          .withFsFilePaths(true)
          .withTag(subject)
          .withTempDir(tmpDir.toString())
          .withExtraArgs(goatRodeoArgs);

      // The [analysis] group, handed over without spice knowing what is in it — the
      // analysis engine owns that schema and rejects a key it does not have, which is why
      // this command carries no list of the engine's settings.
      Map<String, Object> analysis = settings.group("analysis");
      if (!analysis.isEmpty()) {
        builder.withConfiguration(analysis, "analysis");
      }

      if (tagJson != null && !tagJson.isBlank()) {
        builder.withTagJson(tagJson);
      }

      // Artifacts published after the pass's cutoff are out of scope: GoatRodeo drops any entry
      // modified after this instant, along with everything that transitively contains it.
      //
      // This is one of the two analyses the cutoff constrains. Discovery -- the Allspice
      // registry plugin -- is the other, and was the use case the cutoff was minted for; it
      // reads the same claim through SpiceContext.passClaims(). Scoping only one of them would
      // leave a run whose halves disagreed about which artifacts exist, so they land together.
      //
      // This is new behaviour, and it is visible to whoever reads the inventory. The CLI has
      // never honoured `x-cutoff` before, so a pass that carries one now yields a smaller
      // inventory than the same pass did yesterday, with no flag involved. Hence the INFO line:
      // a survey that silently covered less than the caller expected would be very hard to
      // account for after the fact. It is documented for users in README.md and FAQ.md, and for
      // plugin authors in docs/PLUGINS.md.
      passCutoff().ifPresent(cutoff -> {
        log.info("Ignoring artifacts published after {}", cutoff);
        builder.withCutoff(cutoff);
      });

      if (survey != null) {
        builder.withTagDate(survey.submissionTimestamp().toString());
      }

      if (analyzeProgress != null) {
        builder.withProgressListener(analyzeProgress);
        analyzeProgress.start();
      }

      try {
        builder.run();
      } catch (Exception e) {
        if (analyzeProgress != null) {
          analyzeProgress.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        throw e;
      }
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

  private void doUpload(String spicePass, Optional<Path> gingerInputDir, SurveyRegistration.Context survey,
      AnalyzeProgressPublisher analyzeProgress) throws Exception {
    log.info("📦 Uploading ADGs...");

    // `--upload-args` takes raw uploader flags: the escape hatch for something this
    // schema does not model yet. It goes through `extraArgs`, which the uploader applies
    // *inside* run(), after everything set here — so it wins, as an escape hatch must.
    Map<String, String> gingerArgsMap = new HashMap<>(gingerArgs);

    Ginger ginger = Ginger.builder()
        .jwt(spicePass)
        .adgDir(gingerInputDir.orElse(input))
        .extraArgs(gingerArgsMap);

    // The [upload] group, applied through the uploader's typed setters rather than as
    // flag strings. Two reasons, and the second is the important one.
    //
    // The uploader models these settings properly — `targetChunkSizeMB(Integer)`, not
    // `--target-chunk-size=64` — so going through the typed API is checked at compile
    // time and cannot depend on a key name deriving a flag that happens to exist.
    //
    // And `extraArgs` is applied inside the uploader's run() and assigns its `jwt` and
    // `uuid` fields, so anything reaching it overrides the Spice Pass. Forwarding a
    // config-file group there wholesale would have let `[upload] jwt = "…"` replace the
    // credential the platform issued — the one thing configuration must never do.
    applyUploadSettings(ginger, resolveSettings());

    if (survey != null) {
      ginger.parentId(survey.parentId())
          .submissionTimestamp(survey.submissionTimestamp())
          .idempotencyKey(survey.idempotencyKey())
          .userAgent(survey.userAgent());
    }

    if (output != null)
      ginger.outputDir(output);

    if (analyzeProgress != null) {
      analyzeProgress.building();
      ginger.afterBundleWrapped(analyzeProgress::complete);
    }
    try {
      ginger.run();
    } catch (Exception e) {
      if (analyzeProgress != null) {
        analyzeProgress.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      }
      throw e;
    }
  }

  private String resolveSpicePass() {
    if (spicePassOverride != null && !spicePassOverride.isBlank()) {
      return spicePassOverride;
    }
    return DefaultSpiceContext.current().spicePass().orElse(null);
  }

  /**
   * The artifact cutoff in force for this survey, read from the claims of the pass actually in
   * use. Taking it from {@link #resolveSpicePass()} rather than from the environment means a
   * {@code --spice-pass} override carries its own cutoff instead of silently inheriting the
   * ambient pass's.
   *
   * <p>Package-private so a test can exercise the path that consumes the cutoff, rather than
   * only the claims it is consumed from.
   */
  Optional<Instant> passCutoff() {
    return PassClaims.cutoff(passClaims());
  }

  /**
   * The claims of the pass this survey will actually use.
   *
   * <p>In the usual case that is the ambient pass, and these are the claims
   * {@link DefaultSpiceContext} decoded once at startup — nothing decodes it again here. A
   * {@code --spice-pass} override is decoded, because by definition the context holds the
   * claims of a different credential; that is one decode of a pass the context never saw, not
   * a second decode of the same one.
   */
  private SpicePassClaims passClaims() {
    String pass = resolveSpicePass();
    if (pass == null || pass.isBlank()) {
      return SpicePassClaims.EMPTY;
    }
    DefaultSpiceContext context = DefaultSpiceContext.current();
    return pass.equals(context.spicePass().orElse(null))
        ? context.passClaims()
        : PassClaims.of(pass);
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

  private static void validateTagJson(String value) {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode node;
    try {
      node = mapper.readTree(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "--tag-json is not valid JSON: " + ex.getOriginalMessage() +
          ". On PowerShell 5.1, assign the JSON to a variable first " +
          "(e.g. $json = '{\"env\":\"dev\"}'; spice ... --tag-json=$json) " +
          "— PS5.1 strips outer single quotes before passing the arg.");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(
          "--tag-json must be a JSON object (e.g. '{\"env\":\"dev\"}'), got: " + value);
    }
  }

  void configureLogging() {
    Resolution settings = resolveSettings();
    Level level = Level.toLevel(Logging.level(settings), Level.INFO);
    String levelStr = level.toString();

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

    // A log file, if one was asked for.
    //
    // `--log-file` belongs to the wrapper, which tees the whole run to it on the *host*
    // and strips the flag before the container ever sees it — so this only fires for a
    // direct `java -jar` run, where there is no wrapper to do it. The two therefore
    // cannot both write: whichever is running is the only one that sees the flag.
    //
    // A path from the *config file* is refused, in `rejectConfiguredLogFile`, because the
    // wrapper cannot see inside a TOML table to mount it.
    rejectConfiguredLogFile();
    LogbackLogging.apply(settings, Logger.ROOT_LOGGER_NAME);

    // An *output*, not an input: the Scala components read this property, and it is
    // written once here from the resolved level rather than being a channel anyone
    // configures through.
    System.setProperty("scala.logging.level", levelStr);
  }

  /**
   * Refuse {@code [logging] file} written in a configuration file.
   *
   * <p>The wrapper mounts the paths it can see on the command line — that is what the path
   * manifest is for — and it deliberately does not parse TOML, so a path written in a config
   * file is invisible to it. Under Docker such a file would be written inside the container
   * and lost when it exits, which is the silent-configuration failure this whole arrangement
   * exists to prevent.
   *
   * <p>Refused rather than warned: a log nobody can read is not a partial success, and the
   * flag that does work is one word away.
   */
  private void rejectConfiguredLogFile() {
    boolean fromConfigFile =
        RunConfiguration.current()
            .root()
            .containsKey(Logging.GROUP)
            && RunConfiguration.current().root().get(Logging.GROUP) instanceof Map<?, ?> group
            && group.containsKey("file");
    if (fromConfigFile) {
      throw new IllegalArgumentException(
          "[logging] file cannot be set in a configuration file — the wrapper mounts only the "
              + "paths named on the command line, so a file named here would be written inside "
              + "the container and lost. Use --log-file instead.");
    }
  }

  /**
   * The settings a user may write in {@code [upload]}, and where each one goes.
   *
   * <p>A closed list, deliberately. The uploader accepts other arguments — the pass, the
   * ADG directory, the output path — but those are the run's own, decided by this command
   * from the pass and the command line, and a config file has no business supplying them.
   * Anything else in the group is an error naming the key, rather than a flag quietly
   * forwarded to a program that will warn about it in a log nobody reads.
   */
  void applyUploadSettings(Ginger ginger, Resolution settings) {
    List<String> unknown = new ArrayList<>();
    settings
        .group("upload")
        .forEach(
            (key, value) -> {
              Setting setting = settings.setting("upload", key).orElseThrow();
              switch (key) {
                case "target_chunk_size" -> ginger.targetChunkSizeMB((int) setting.asLong());
                case "encrypt_only" -> ginger.encryptOnly(setting.asBoolean());
                case "skip_key" -> ginger.skipKey(setting.asBoolean());
                case "comment" -> ginger.comment(setting.asString());
                case "bundle_format_version" -> ginger.bundleFormatVersion((int) setting.asLong());
                default -> unknown.add(key);
              }
            });
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "[upload] has no setting called " + String.join(", ", unknown));
    }
  }

  private static boolean hasSpicePass(String spicePass) {
    return spicePass != null && !spicePass.isBlank();
  }

  /** Encrypt-only runs (via --upload-args) never contact a server, so we skip survey registration. */
  boolean isEncryptOnly() {
    // Both ways of saying it: `[upload] encrypt_only = true` and the raw
    // `--upload-args=--encrypt-only`. A run that never contacts a server must skip
    // registration however that was said, and the raw flag wins for the same reason it
    // wins everywhere else.
    if (gingerArgs.containsKey("--encrypt-only")) {
      return !"false".equalsIgnoreCase(gingerArgs.get("--encrypt-only"));
    }
    return resolveSettings()
        .setting("upload", "encrypt_only")
        .map(Setting::asBoolean)
        .orElse(false);
  }

  static void deleteRecursively(Path path) {
    if (path == null || !Files.exists(path)) return;
    try {
      Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          try {
            Files.deleteIfExists(file);
          } catch (IOException e) {
            log.warn("Failed to delete file {}: {}", file, e.getMessage());
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
          try {
            Files.deleteIfExists(dir);
          } catch (IOException e) {
            log.warn("Failed to delete directory {}: {}", dir, e.getMessage());
          }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      log.warn("Failed to clean up {}: {}", path, e.getMessage());
    }
  }
}
