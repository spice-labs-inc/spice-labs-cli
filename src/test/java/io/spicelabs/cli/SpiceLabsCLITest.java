package io.spicelabs.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

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
        "--command", "scan-artifacts",
        "--input", "some/path"
    );
    SpiceLabsCLI app = parseResult.commandSpec().commandLine().getCommand();
    assertEquals(SpiceLabsCLI.Command.scan_artifacts, app.command);
    assertEquals(Path.of("some/path"), app.input);
  }

  @Test
  void parse_unknownCommand_throws() {
    assertThrows(CommandLine.ParameterException.class,
        () -> cmd.parseArgs("--command", "not-a-real"));
  }

  @Test
  void scanArtifacts_doesNotThrow() throws Exception {
    Path tmpInput = Files.createTempDirectory("fake-input");
    Path dummyJar = tmpInput.resolve("foo.jar");
    Files.createFile(dummyJar);

    Path tmpOutput = Files.createTempDirectory("fake-output");

    SpiceLabsCLI.builder()
        .command(SpiceLabsCLI.Command.scan_artifacts)
        .input(tmpInput)
        .output(tmpOutput)
        .run();
  }


  @Test
  void uploadAdgsWithoutSpicePass_throws() {
    var ex = assertThrows(RuntimeException.class, () ->
        SpiceLabsCLI.builder()
            .command(SpiceLabsCLI.Command.upload_adgs)
            .input(Path.of("ignored"))
            .run());
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
}