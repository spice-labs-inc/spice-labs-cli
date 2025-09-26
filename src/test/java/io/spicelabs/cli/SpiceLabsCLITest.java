package io.spicelabs.cli;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class SpiceLabsCLITest {

  private CommandLine cmd;

  @BeforeEach
  void setup() {
    cmd = new CommandLine(new SpiceLabsCLI())
        .setCaseInsensitiveEnumValuesAllowed(true);
  }

  @Test
  void parse_validCommandAndInput() {
    var parseResult = cmd.parseArgs(
        "--tag", "test-tag",
        "--command", "survey-artifacts",
        "--input", "some/path"
    );
    SpiceLabsCLI app = parseResult.commandSpec().commandLine().getCommand();
    assertEquals(SpiceLabsCLI.Command.survey_artifacts, app.command);
    assertEquals(Path.of("some/path"), app.input);
  }

  @Test
  void parse_unknownCommand_throws() {
    assertThrows(CommandLine.ParameterException.class,
        () -> cmd.parseArgs("--command", "not-a-real"));
  }

  @Test
  void surveyArtifacts_doesNotThrow() throws Exception {
    Path tmpInput = Files.createTempDirectory("fake-input");
    Path dummyJar = tmpInput.resolve("foo.jar");
    Files.createFile(dummyJar);

    Path tmpOutput = Files.createTempDirectory("fake-output");

    SpiceLabsCLI.builder()
        .tag("test-tag")
        .command(SpiceLabsCLI.Command.survey_artifacts)
        .input(tmpInput)
        .output(tmpOutput)
        .run();
  }


  @Test
  void uploadAdgsWithoutSpicePass_throws() throws Exception {
    Path tmpInput = Files.createTempDirectory("fake-adgs");

    SpiceLabsCLI cli = new SpiceLabsCLI() {
      @Override
      protected String getSpicePassEnv() {
        return null;
      }
    };

    cli.command(SpiceLabsCLI.Command.upload_adgs)
        .tag("test-tag")
        .input(tmpInput);

    var ex = assertThrows(IllegalArgumentException.class, cli::run);
    assertTrue(ex.getMessage().contains("SPICE_PASS must be set"));
  }



  @Test
  void privateUploadDeploymentEvents_cleansUpTempFile() throws Exception {
    String json = "[{\"identifier\":\"x\",\"system\":\"y\",\"artifact\":\"z\",\"start_time\":\"2025-01-01T00:00:00Z\"}]";

    System.setIn(new ByteArrayInputStream(json.getBytes()));

    SpiceLabsCLI cli = new SpiceLabsCLI();
    Method m = SpiceLabsCLI.class
        .getDeclaredMethod("doUploadDeploymentEvents");
    m.setAccessible(true);

    Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
    Set<Path> before;
    try (Stream<Path> stream = Files.list(tmpDir)) {
      before = stream
          .filter(p -> p.getFileName().toString().startsWith("deploy-events-"))
          .collect(Collectors.toSet());
    }

    try {
      m.invoke(cli);
    } catch (InvocationTargetException ite) {
    }

    try (Stream<Path> stream = Files.list(tmpDir)) {
      Set<Path> after = stream
          .filter(p -> p.getFileName().toString().startsWith("deploy-events-"))
          .collect(Collectors.toSet());
      assertEquals(before, after, "No deploy-events-* files should remain");
    }
  }

  static class CapturingGoatRodeoBuilder extends io.spicelabs.goatrodeo.GoatRodeoBuilder {
    final java.util.Map<String, String> received = new java.util.HashMap<>();

    @Override
    public io.spicelabs.goatrodeo.GoatRodeoBuilder withExtraArgs(java.util.Map<String, String> args) {
      received.putAll(args);
      return super.withExtraArgs(args); // Optional
    }

    @Override
    public void run() {
      // no-op
    }
  }

  static class TestableCLI extends SpiceLabsCLI {
    final CapturingGoatRodeoBuilder builder = new CapturingGoatRodeoBuilder();

    @Override
    protected void doSurvey() throws Exception {
      builder.withPayload(input.toString())
          .withOutput(output.toString())
          .withThreads(threads)
          .withMaxRecords(maxRecords)
          .withTag(tag)
          .withExtraArgs(goatRodeoArgs);

      builder.run();
      System.out.println("Args passed: " + builder.received);
    }
  }

  @Test
  void goatRodeoArgs_areParsedAndPassed() throws Exception {

    Path inputDir = Files.createTempDirectory("test-input");
    Path outputDir = Files.createTempDirectory("test-output");

    TestableCLI cli = new TestableCLI();
    CommandLine cmd = new CommandLine(cli);
    cmd.execute(
        "--tag", "test-tag",
        "--command", "survey-artifacts",
        "--input", inputDir.toString(),
        "--output", outputDir.toString(),
        "--goat-rodeo-args", "blockList=/etc/blocklist.txt,tempDir=/tmp/test"
    );

    assertEquals("/etc/blocklist.txt", cli.builder.received.get("blockList"));
    assertEquals("/tmp/test", cli.builder.received.get("tempDir"));
    assertEquals(2, cli.builder.received.size());
  }
}

