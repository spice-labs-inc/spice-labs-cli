package io.spicelabs.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SpiceLabsCLILogLevelTest {

  Path payloadDir;
  Path outputDir;

  @BeforeEach
  void setup() throws Exception {
    payloadDir = Files.createTempDirectory("payload");
    outputDir = Files.createTempDirectory("output");
  }

  private List<ILoggingEvent> captureLogs(String logLevel) throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);

    SpiceLabsCLI.builder()
        .tag("test-tag")
        .command(SpiceLabsCLI.Command.scan_artifacts)
        .input(payloadDir)
        .output(outputDir)
        .logLevel(logLevel)
        .run();

    return appender.list;
  }

  @Test
  void logLevelInfo_showsInfoAndAbove() throws Exception {
    List<ILoggingEvent> logs = captureLogs("info");
    assertTrue(
        logs.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO)),
        "Should log info-level messages"
    );
  }

  @Test
  void logLevelError_suppressesInfoAndWarn() throws Exception {
    List<ILoggingEvent> logs = captureLogs("error");
    assertFalse(
        logs.stream().anyMatch(e -> e.getLevel() == Level.INFO || e.getLevel() == Level.WARN),
        "Info logs should be suppressed"
    );
  }

  @Test
  void logLevelDebug_includesVerbose() throws Exception {
    List<ILoggingEvent> logs = captureLogs("debug");
    assertTrue(
        logs.stream().anyMatch(e -> e.getFormattedMessage().toLowerCase().contains("scanning")),
        "Should include verbose GoatRodeo logs at DEBUG level"
    );
  }

  @Test
  void logLevelInfo_stdout_includesGoatRodeoLogs() throws Exception {
    // Save and override System.out
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));

    try {
      new SpiceLabsCLI()
          .tag("test-tag")
          .command(SpiceLabsCLI.Command.scan_artifacts)
          .input(Path.of(System.getProperty("user.dir")))
          .logLevel("info")
          .run();
    } catch (Exception ignored) {
      // Expected if GoatRodeo fails on invalid input
    } finally {
      System.setOut(originalOut);
    }

    String output = buffer.toString();
    assertTrue(output.contains("📦 Scanning artifacts"), "Expected info-level CLI log on stdout");
  }

  @Test
  void logLevelError_stdout_suppressesInfoLogs() throws Exception {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));

    try {
      new SpiceLabsCLI()
          .tag("test-tag")
          .command(SpiceLabsCLI.Command.scan_artifacts)
          .input(Path.of(System.getProperty("user.dir")))
          .logLevel("error")
          .run();
    } catch (Exception ignored) {
      // GoatRodeo may throw; we only care about output
    } finally {
      System.setOut(originalOut);
    }

    String output = buffer.toString();
    assertFalse(output.contains("📦 Scanning artifacts"), "Info logs should not be printed at error level");
  }

}