package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests for bash and PowerShell wrapper scripts.
 *
 * Both wrappers are invoked with a mock docker that captures all arguments.
 * We then assert that both produce identical docker command lines.
 *
 * These tests verify that the arg-walking logic (finding input paths, output
 * paths, rewriting for container mounts) is identical across both scripts.
 */
class WrapperParityTest {

  static Path projectDir;
  static boolean hasPwsh;

  @BeforeAll
  static void setup() {
    projectDir = Path.of(System.getProperty("user.dir"));
    hasPwsh = checkCommand("pwsh");
  }

  static boolean checkCommand(String cmd) {
    try {
      Process p = new ProcessBuilder("which", cmd)
          .redirectErrorStream(true).start();
      return p.waitFor() == 0;
    } catch (Exception e) { return false; }
  }

  static boolean pwshAvailable() { return hasPwsh; }

  // ── Test cases ────────────────────────────────────────────────────────────

  @Test
  void surveyInventory_directoryInput() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-dir-input");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString());
  }

  @Test
  void surveyInventory_singleFileInput() throws Exception {
    Path tmpDir = Files.createTempDirectory("parity-file-input");
    Path inputFile = tmpDir.resolve("app.jar");
    Files.writeString(inputFile, "fake jar");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputFile.toString());
  }

  @Test
  void surveyInventory_withOutput() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-with-output-in");
    Files.writeString(inputDir.resolve("file.txt"), "test");
    Path outputDir = Files.createTempDirectory("parity-with-output-out");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString(),
        "--output=" + outputDir);
  }

  @Test
  void surveyInventory_outputSpaceSeparated() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-output-space-in");
    Files.writeString(inputDir.resolve("file.txt"), "test");
    Path outputDir = Files.createTempDirectory("parity-output-space-out");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString(),
        "--output", outputDir.toString());
  }

  @Test
  void surveyInventory_noUpload() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-no-upload");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString(), "--no-upload");
  }

  @Test
  void surveyInventory_uploadOnly() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-upload-only");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString(), "--upload-only");
  }

  @Test
  void surveyInventory_flagsBeforePositionals() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-flags-before");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    assertParityOrBashOnly(
        "survey", "inventory", "--log-level", "debug",
        "my-app", inputDir.toString(), "--no-upload");
  }

  @Test
  void surveyInventory_absolutePath() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-abs-path");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toAbsolutePath().toString());
  }

  @Test
  void surveyInventory_allFlags() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-all-flags");
    Files.writeString(inputDir.resolve("file.txt"), "test");
    Path outputDir = Files.createTempDirectory("parity-all-flags-out");

    assertParityOrBashOnly(
        "survey", "inventory", "my-app", inputDir.toString(),
        "--output", outputDir.toString(),
        "--no-upload", "--threads", "4",
        "--max-records=1000", "--chunk-size=128");
  }

  @Test
  void passDecode() throws Exception {
    assertParityOrBashOnly("pass", "decode");
  }

  @Test
  void version() throws Exception {
    assertParityOrBashOnly("--version");
  }

  @Test
  void help() throws Exception {
    assertParityOrBashOnly("--help");
  }

  @Test
  void surveyHelp() throws Exception {
    assertParityOrBashOnly("survey", "--help");
  }

  // ── Infra ─────────────────────────────────────────────────────────────────

  /**
   * Run both wrappers and assert identical docker args (after normalizing
   * known platform differences).
   * If pwsh is not available, only test bash.
   */
  private void assertParityOrBashOnly(String... cliArgs) throws Exception {
    String bashDockerArgs = normalizeDockerArgs(runWrapper("bash", cliArgs));

    if (hasPwsh) {
      String pwshDockerArgs = normalizeDockerArgs(runWrapper("pwsh", cliArgs));
      assertEquals(bashDockerArgs, pwshDockerArgs,
          "Bash and PowerShell wrappers produced different docker args for: " +
              String.join(" ", cliArgs));
    }

    // Basic sanity: docker args should not be empty
    assertFalse(bashDockerArgs.isBlank(), "Docker args should not be empty");
  }

  /**
   * Normalize known platform differences so we can compare bash vs pwsh output.
   *
   * Docker run args before the image are platform-dependent (ordering, --user).
   * We extract just the CLI args (everything after the image:tag) and compare those,
   * plus verify the volume mounts match.
   */
  private static String normalizeDockerArgs(String args) {
    // Split on the image:tag to separate docker flags from CLI args
    // Image is always spicelabs/spice-labs-cli:latest
    String image = "spicelabs/spice-labs-cli:latest";
    int imageIdx = args.indexOf(image);
    if (imageIdx < 0) return args.trim();

    String dockerFlags = args.substring(0, imageIdx).trim();
    String cliArgs = args.substring(imageIdx + image.length()).trim();

    // Extract volume mounts (order-independent) and sort them
    java.util.List<String> volumes = new java.util.ArrayList<>();
    String remaining = dockerFlags;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("-v (\\S+)").matcher(dockerFlags);
    while (m.find()) {
      volumes.add(m.group(1));
    }
    java.util.Collections.sort(volumes);

    return "VOLUMES=" + String.join(",", volumes) + " ARGS=" + cliArgs;
  }

  /**
   * Run a wrapper script with a mock docker that captures all args.
   * Returns the captured docker arguments as a single string.
   */
  private String runWrapper(String shell, String... cliArgs) throws Exception {
    Path mockBin = Files.createTempDirectory("mock-docker-" + shell);
    Path argsFile = Files.createTempFile("docker-args-" + shell, ".txt");

    // Create mock docker that captures args
    if (shell.equals("bash")) {
      Files.writeString(mockBin.resolve("docker"),
          "#!/bin/bash\necho \"$@\" > " + argsFile + "\n");
      mockBin.resolve("docker").toFile().setExecutable(true);
    } else {
      // For pwsh tests on Linux, we still need a bash mock docker
      Files.writeString(mockBin.resolve("docker"),
          "#!/bin/bash\necho \"$@\" > " + argsFile + "\n");
      mockBin.resolve("docker").toFile().setExecutable(true);
    }

    ProcessBuilder pb;
    if (shell.equals("bash")) {
      String[] cmd = new String[cliArgs.length + 1];
      cmd[0] = "./spice";
      System.arraycopy(cliArgs, 0, cmd, 1, cliArgs.length);
      pb = new ProcessBuilder(cmd);
    } else {
      String[] cmd = new String[cliArgs.length + 2];
      cmd[0] = "pwsh";
      cmd[1] = "-File";
      // Build the full command — pwsh -File ./spice.ps1 <args>
      String[] fullCmd = new String[cliArgs.length + 3];
      fullCmd[0] = "pwsh";
      fullCmd[1] = "-File";
      fullCmd[2] = "./spice.ps1";
      System.arraycopy(cliArgs, 0, fullCmd, 3, cliArgs.length);
      pb = new ProcessBuilder(fullCmd);
    }

    pb.directory(projectDir.toFile());
    pb.environment().put("PATH", mockBin + ":" + System.getenv("PATH"));
    pb.environment().put("SPICE_LABS_CLI_SKIP_PULL", "1");
    pb.environment().put("SPICE_PASS", "dummy");
    pb.redirectErrorStream(true);

    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes());
    p.waitFor();

    return Files.readString(argsFile).trim();
  }
}
