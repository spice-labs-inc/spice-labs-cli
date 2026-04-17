package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class SpiceLabsCLITest {

  // ── Subcommand parsing ────────────────────────────────────────────────────

  @Test
  void topLevel_noArgs_printsHelp() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute();
    assertEquals(0, rc);
  }

  @Test
  void topLevel_help() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("--help");
    assertEquals(0, rc);
  }

  @Test
  void survey_noSubtype_printsHelp() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey");
    assertEquals(0, rc);
  }

  @Test
  void survey_help() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "--help");
    assertEquals(0, rc);
  }

  @Test
  void surveyInventory_help() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory", "--help");
    assertEquals(0, rc);
  }

  @Test
  void passDecode_help() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("pass", "decode", "--help");
    assertEquals(0, rc);
  }

  @Test
  void pass_noSubcommand_printsHelp() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("pass");
    assertEquals(0, rc);
  }

  // ── Survey inventory: survey only ─────────────────────────────────────────

  @Test
  void surveyInventory_noUpload() throws Exception {
    Path inputDir = Files.createTempDirectory("test-input");
    createFilesInDir(inputDir, 10);
    Path outputDir = Files.createTempDirectory("test-output");

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputDir.toString(),
        "--no-upload", "--output", outputDir.toString());

    assertEquals(0, rc);

    // Verify survey output was produced
    Path surveyorDir = findSingleSubdir(outputDir.resolve("surveyor"));
    assertNotNull(surveyorDir, "Expected a survey-* directory");
    assertSurveyFilesExist(surveyorDir);
  }

  @Test
  void surveyInventory_singleFileInput_noUpload() throws Exception {
    Path tmpDir = Files.createTempDirectory("single-file-test");
    Path inputFile = tmpDir.resolve("foo.jar");
    Files.createFile(inputFile);
    Path outputDir = Files.createTempDirectory("single-file-output");

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputFile.toString(),
        "--no-upload", "--output", outputDir.toString());

    assertEquals(0, rc);

    Path surveyorDir = findSingleSubdir(outputDir.resolve("surveyor"));
    assertNotNull(surveyorDir, "Expected a survey-* directory");
    assertSurveyFilesExist(surveyorDir);
  }

  @Test
  void surveyInventory_singleFileInput_cleansUpTempDir() throws Exception {
    Path tmpDir = Files.createTempDirectory("cleanup-test");
    Path inputFile = tmpDir.resolve("test.jar");
    Files.createFile(inputFile);
    Path outputDir = Files.createTempDirectory("cleanup-output");

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    cmd.execute("survey", "inventory",
        "test-subject", inputFile.toString(),
        "--no-upload", "--output", outputDir.toString());

    // No spice-single-file-* dirs should remain
    try (Stream<Path> s = Files.list(tmpDir)) {
      Set<Path> leftover = s.filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith("spice-single-file-"))
          .collect(Collectors.toSet());
      assertEquals(Set.of(), leftover);
    }
  }

  @Test
  void surveyInventory_conflictingFlags_fails() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", "/tmp/fake",
        "--no-upload", "--upload-only");
    assertNotEquals(0, rc);
  }

  @Test
  void surveyInventory_nonExistentInput_fails() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", "/tmp/this-path-does-not-exist-at-all",
        "--no-upload");
    assertNotEquals(0, rc);
  }

  @Test
  void surveyInventory_emptyInputDir_fails() throws Exception {
    Path emptyDir = Files.createTempDirectory("empty-input-test");
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", emptyDir.toString(),
        "--no-upload");
    assertNotEquals(0, rc);
  }

  @Test
  void surveyInventory_nonExistentInput_uploadOnly_fails() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.subject = "test-subject";
    cmd.input = Path.of("/tmp/this-path-does-not-exist-at-all");
    cmd.uploadOnly = true;
    cmd.spicePassOverride = "dummy-pass";

    int rc;
    try { rc = cmd.call(); } catch (Exception e) { rc = 1; }
    assertNotEquals(0, rc);
  }

  @Test
  void surveyInventory_threadsZero_fails() throws Exception {
    Path inputDir = Files.createTempDirectory("threads-zero-test");
    Files.createFile(inputDir.resolve("dummy.jar"));
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputDir.toString(),
        "--no-upload", "--threads", "0");
    assertNotEquals(0, rc);
  }

  @Test
  void surveyInventory_threadsNegative_fails() throws Exception {
    Path inputDir = Files.createTempDirectory("threads-neg-test");
    Files.createFile(inputDir.resolve("dummy.jar"));
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputDir.toString(),
        "--no-upload", "--threads", "-1");
    assertNotEquals(0, rc);
  }

  // ── #545: --log-level validation ──────────────────────────────────────────

  @Test
  void surveyInventory_invalidLogLevel_fails() throws Exception {
    Path inputDir = Files.createTempDirectory("bad-log-level");
    Files.createFile(inputDir.resolve("dummy.jar"));
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputDir.toString(),
        "--no-upload", "--log-level", "verbose");
    assertNotEquals(0, rc, "Invalid --log-level value should fail");
  }

  @Test
  void surveyInventory_validLogLevels_accepted() throws Exception {
    Path inputDir = Files.createTempDirectory("ok-log-level");
    createFilesInDir(inputDir, 3);
    Path outputDir = Files.createTempDirectory("ok-log-level-out");
    for (String lvl : new String[] { "debug", "INFO", "Warn", "error" }) {
      CommandLine cmd = new CommandLine(new SpiceLabsCLI());
      int rc = cmd.execute("survey", "inventory",
          "test-subject", inputDir.toString(),
          "--no-upload", "--output", outputDir.toString(),
          "--log-level", lvl);
      assertEquals(0, rc, "Expected --log-level=" + lvl + " to be accepted");
    }
  }

  @Test
  void invalidLogLevel_errorMessageListsValidValues() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> LogLevelParser.parse("verbose"));
    String msg = ex.getMessage();
    assertTrue(msg.contains("verbose"), "Error should quote the bad value: " + msg);
    assertTrue(msg.contains("debug") && msg.contains("info") &&
               msg.contains("warn") && msg.contains("error"),
        "Error should list valid values: " + msg);
  }

  // ── #531: missing-args prints detailed usage ─────────────────────────────

  @Test
  void surveyInventory_missingArgs_printsDetailedUsage() {
    StringWriter err = new StringWriter();
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    cmd.setErr(new PrintWriter(err));
    int rc = cmd.execute("survey", "inventory");
    assertNotEquals(0, rc, "Missing required params should produce non-zero exit");

    String output = err.toString();
    assertTrue(output.contains("Usage:"),
        "Expected detailed 'Usage:' block in output, got:\n" + output);
    assertTrue(output.contains("inventory"),
        "Expected subcommand name in usage, got:\n" + output);
    // Spot-check that flag descriptions made it through — these only appear
    // in the full usage block, not in the short error line.
    assertTrue(output.contains("--no-upload"),
        "Expected --no-upload in detailed usage, got:\n" + output);
  }

  @Test
  void topLevel_unknownOption_suppressesDetailedUsage() {
    // Unknown flags are likely typos — full usage dump is excessive.
    // The short "Use --help for usage information." hint is enough.
    StringWriter err = new StringWriter();
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    cmd.setErr(new PrintWriter(err));
    int rc = cmd.execute("--nope");
    assertNotEquals(0, rc);
    assertFalse(err.toString().contains("Usage:"),
        "Did not expect full 'Usage:' block for unknown flag, got:\n" + err);
  }

  // ── Survey inventory: full pipeline (survey + upload) ─────────────────────

  @Test
  void surveyInventory_fullPipeline() throws Exception {
    Path inputDir = Files.createTempDirectory("run-input");
    createFilesInDir(inputDir, 50);

    Path surveyorRoot = Path.of(System.getProperty("user.home"), ".spicelabs", "surveyor");
    Set<Path> before = snapshotDirectories(surveyorRoot);

    // Use a subclass that provides a fake SPICE_PASS
    SurveyInventoryCommand surveyCmd = new SurveyInventoryCommand();
    surveyCmd.subject = "test-subject";
    surveyCmd.input = inputDir;
    surveyCmd.spicePassOverride = "dummy-pass-for-testing";
    surveyCmd.gingerArgs = Map.of("--skip-key", "true", "--encrypt-only", "true");
    surveyCmd.goatRodeoArgs = Map.of("maxRecords", "50");
    surveyCmd.call();

    Set<Path> after = snapshotDirectories(surveyorRoot);
    Set<Path> diff = after.stream().filter(p -> !before.contains(p)).collect(Collectors.toSet());
    assertEquals(1, diff.size(), "Expected exactly one new surveyor directory");

    Path newDir = diff.iterator().next();
    assertSurveyFilesExist(newDir);
    assertGingerOutputValid(newDir);
  }

  @Test
  void surveyInventory_fullPipeline_singleFile() throws Exception {
    Path tmpDir = Files.createTempDirectory("run-single-file");
    Path inputFile = tmpDir.resolve("test.jar");
    Files.createFile(inputFile);

    Path surveyorRoot = Path.of(System.getProperty("user.home"), ".spicelabs", "surveyor");
    Set<Path> before = snapshotDirectories(surveyorRoot);

    SurveyInventoryCommand surveyCmd = new SurveyInventoryCommand();
    surveyCmd.subject = "test-subject";
    surveyCmd.input = inputFile;
    surveyCmd.spicePassOverride = "dummy-pass-for-testing";
    surveyCmd.gingerArgs = Map.of("--skip-key", "true", "--encrypt-only", "true");
    surveyCmd.call();

    Set<Path> after = snapshotDirectories(surveyorRoot);
    Set<Path> diff = after.stream().filter(p -> !before.contains(p)).collect(Collectors.toSet());
    assertEquals(1, diff.size());

    Path newDir = diff.iterator().next();
    assertGingerOutputValid(newDir);
  }

  // ── Upload only ───────────────────────────────────────────────────────────

  @Test
  void surveyInventory_uploadOnly_withoutSpicePass_fails() throws Exception {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.subject = "test-subject";
    cmd.input = Path.of("/tmp/fake");
    cmd.uploadOnly = true;
    cmd.spicePassOverride = "";

    int rc = cmd.call();
    assertNotEquals(0, rc);
  }

  // ── Flags anywhere ────────────────────────────────────────────────────────

  @Test
  void surveyInventory_flagsBeforePositionals() throws Exception {
    Path inputDir = Files.createTempDirectory("flags-test");
    createFilesInDir(inputDir, 5);
    Path outputDir = Files.createTempDirectory("flags-output");

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "--no-upload", "--log-level=debug",
        "test-subject", inputDir.toString(),
        "--output", outputDir.toString());

    assertEquals(0, rc);
  }

  // ── GoatRodeo args pass-through ───────────────────────────────────────────

  @Test
  void surveyInventory_goatRodeoArgs() throws Exception {
    Path inputDir = Files.createTempDirectory("gr-args-test");
    createFilesInDir(inputDir, 5);
    Path outputDir = Files.createTempDirectory("gr-args-output");

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    int rc = cmd.execute("survey", "inventory",
        "test-subject", inputDir.toString(),
        "--no-upload", "--output", outputDir.toString(),
        "--goat-rodeo-args=maxRecords=10,tempDir=/tmp/test");

    assertEquals(0, rc);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static void createFilesInDir(Path dir, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      Files.writeString(dir.resolve("file" + i + ".txt"), "contents " + i);
    }
  }

  private static Set<Path> snapshotDirectories(Path dir) throws Exception {
    if (Files.exists(dir)) {
      try (Stream<Path> s = Files.list(dir)) {
        return s.filter(Files::isDirectory).collect(Collectors.toSet());
      }
    }
    return Set.of();
  }

  private static Path findSingleSubdir(Path parent) throws Exception {
    if (!Files.exists(parent)) return null;
    try (Stream<Path> s = Files.list(parent)) {
      List<Path> dirs = s.filter(Files::isDirectory).collect(Collectors.toList());
      return dirs.size() == 1 ? dirs.get(0) : null;
    }
  }

  /**
   * Assert that a survey output directory contains at least one survey subdirectory
   * with the expected file types.
   */
  private static void assertSurveyFilesExist(Path surveyParent) throws Exception {
    // Find survey or survey_N subdirs
    Set<Path> surveyDirs;
    try (Stream<Path> s = Files.list(surveyParent)) {
      surveyDirs = s.filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith("survey"))
          .collect(Collectors.toSet());
    }
    assertFalse(surveyDirs.isEmpty(), "Expected survey subdirectories in " + surveyParent);

    for (Path sd : surveyDirs) {
      assertFileWithExtensionExists(sd, ".grc");
      assertFileWithExtensionExists(sd, ".grd");
      assertFileWithExtensionExists(sd, ".gri");
      assertTrue(Files.exists(sd.resolve("history.jsonl")), "Missing history.jsonl in " + sd);
      assertTrue(Files.exists(sd.resolve("purls.txt")), "Missing purls.txt in " + sd);
    }
  }

  private static void assertFileWithExtensionExists(Path dir, String ext) throws Exception {
    try (Stream<Path> s = Files.list(dir)) {
      assertTrue(s.anyMatch(p -> p.getFileName().toString().endsWith(ext)),
          "Missing " + ext + " file in " + dir);
    }
  }

  private static void assertGingerOutputValid(Path surveyParent) throws Exception {
    Path gingerOutput = surveyParent.resolve("ginger-output");
    assertTrue(Files.exists(gingerOutput) && Files.isDirectory(gingerOutput),
        "Missing ginger-output dir");

    List<Path> zips;
    try (Stream<Path> s = Files.list(gingerOutput)) {
      zips = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
          .collect(Collectors.toList());
    }
    assertEquals(1, zips.size(), "Expected exactly one zip in ginger-output");

    // Extract and verify payload.enc
    Path zipFile = zips.get(0);
    String zipBase = zipFile.getFileName().toString().replace(".zip", "");
    Path unzipDest = gingerOutput.resolve(zipBase);

    try (ZipFile zf = new ZipFile(zipFile.toFile())) {
      Enumeration<? extends ZipEntry> entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Path out = unzipDest.resolve(entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(out);
        } else {
          Files.createDirectories(out.getParent());
          try (InputStream in = zf.getInputStream(entry);
               java.io.OutputStream os = Files.newOutputStream(out)) {
            in.transferTo(os);
          }
        }
      }
    }

    Path payloadEnc = unzipDest.resolve("payload.enc");
    assertTrue(Files.exists(payloadEnc), "Missing payload.enc");

    // Untar
    ProcessBuilder pb = new ProcessBuilder("tar", "-xf", "payload.enc");
    pb.directory(unzipDest.toFile());
    assertEquals(0, pb.start().waitFor(), "tar extraction failed");

    // Should contain survey dirs
    try (Stream<Path> s = Files.list(unzipDest)) {
      boolean hasSurvey = s.filter(Files::isDirectory)
          .anyMatch(p -> p.getFileName().toString().startsWith("survey"));
      assertTrue(hasSurvey, "Extracted payload should contain survey directories");
    }
  }
}
