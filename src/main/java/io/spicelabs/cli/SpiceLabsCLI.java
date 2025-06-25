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

import ch.qos.logback.classic.Level;
import io.spicelabs.ginger.Ginger;
import io.spicelabs.goatrodeo.GoatRodeo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Callable;

@Command(name = "spice", mixinStandardHelpOptions = true,
    description = "Spice Labs unified CLI",
    version = "1.0")
public class SpiceLabsCLI implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(SpiceLabsCLI.class);

  @Option(names = "--command", description = "run[default - scans and uploads adgs]|scan-artifacts|upload-adgs|upload-deployment-events",
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

  @Option(names = "--jwt", description = "JWT token or path to token file")
  String jwt;

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = new CommandLine(new SpiceLabsCLI())
          .setCaseInsensitiveEnumValuesAllowed(true)
          .execute(args);
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

  public SpiceLabsCLI jwt(String jwt) {
    this.jwt = jwt;
    return this;
  }

  // ── CLI Entrypoint ───────────────────────────

  @Override
  public Integer call() throws Exception {
    try {
      run();
      return 0;
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
    configureLogging();

    if (input == null)
      input = Paths.get(System.getProperty("user.dir"));

    if ((command == Command.run || command == Command.scan_artifacts) && output == null)
      output = Files.createTempDirectory("spice-output-");

    if (command != Command.scan_artifacts && (jwt == null || jwt.isBlank()))
      throw new IllegalArgumentException("SPICE_PASS must be set via --jwt for command: " + command);

    switch (command) {
      case scan_artifacts -> doScan();
      case upload_adgs -> doUploadAdgs();
      case upload_deployment_events -> doUploadDeploymentEvents();
      case run -> doRunAll();
    }
  }

  // ── Internal Logic ───────────────────────────

  private void configureLogging() {
    String levelStr = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
    Level level = Level.toLevel(levelStr, Level.INFO);

    ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(level);

    // Also propagate to Scala logging
    System.setProperty("scala.logging.level", levelStr);
  }

  private void doScan() throws Exception {
    log.info("📦 Scanning artifacts with GoatRodeo...");

    // Save original log level values
    String originalScalaLevel = System.getProperty("scala.logging.level");
    String originalSlf4jLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");

    try {
      // Map log level to Scala logging expectations  
      String level = (logLevel == null) ? "INFO" : logLevel.toUpperCase();
      System.setProperty("scala.logging.level", level);
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);

      GoatRodeo.builder()
          .withPayload(input.toString())
          .withOutput(output.toString())
          .run();
    } finally {
      // Restore original log level values
      if (originalScalaLevel != null)
        System.setProperty("scala.logging.level", originalScalaLevel);
      if (originalSlf4jLevel != null)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", originalSlf4jLevel);
    }
  }

  private void doUploadAdgs() throws Exception {
    log.info("📦 Uploading ADGs...");
    Ginger.builder()
        .jwt(jwt)
        .adgDir(input)
        .run();
  }

  private void doUploadDeploymentEvents() throws Exception {
    log.info("📦 Uploading deployment events...");
    Path tmp = Files.createTempFile("deploy-events-", ".json");
    try (OutputStream os = Files.newOutputStream(tmp)) {
      new BufferedInputStream(System.in).transferTo(os);
    }
    try {
      Ginger.builder()
          .jwt(jwt)
          .deploymentEventsFile(tmp)
          .run();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private void doRunAll() throws Exception {
    doScan();
    doUploadAdgs();
  }

  // ── Enums ────────────────────────────────────

  public enum Command {
    run,
    scan_artifacts,
    upload_adgs,
    upload_deployment_events;

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
}
