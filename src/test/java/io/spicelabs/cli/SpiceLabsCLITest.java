package io.spicelabs.cli;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

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

    SpiceLabsCLI cli = SpiceLabsCLI.builder().build();
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
      return super.withExtraArgs(args);
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
    // Use the instance fluent setters instead of CommandLine parsing
    cli.command(SpiceLabsCLI.Command.survey_artifacts)
        .tag("test-tag")
        .input(inputDir)
        .output(outputDir)
        .goatRodeoArgs(java.util.Map.of("blockList","/etc/blocklist.txt","tempDir","/tmp/test"));
    cli.run();

    assertEquals("/etc/blocklist.txt", cli.builder.received.get("blockList"));
    assertEquals("/tmp/test", cli.builder.received.get("tempDir"));
    assertEquals(2, cli.builder.received.size());
  }

  @Test
  void runCommand_createsSurveyorSubdir() throws Exception {
    
    // Create input dir with 100 small unique text files
    Path inputDir = Files.createTempDirectory("run-test-input");
    createFilesInDir(inputDir, 100);

    Path surveyorRoot = Path.of(System.getProperty("user.home"), ".spicelabs", "surveyor");

    Set<Path> before;
    before = snapshotDirectories(surveyorRoot);

    // Use the builder to configure and run the CLI (replaces CommandLine.execute)
    SpiceLabsCLI.builder()
        .tag("test-tag")
        .command(SpiceLabsCLI.Command.run)
        .input(inputDir)
        .gingerArgs(Map.of("--skip-key","true", "--encrypt-only","true"))
        .goatRodeoArgs(Map.of("maxRecords","10"))
        .run();

    Set<Path> after;
    after = snapshotDirectories(surveyorRoot);

    // There should be exactly one new directory
    // exactly one new directory created under surveyorRoot
    Set<Path> diff = after.stream().filter(p -> !before.contains(p)).collect(Collectors.toSet());
    assertEquals(1, diff.size(), "Expected exactly one new surveyor directory");

    Path newSurveyorDir = diff.iterator().next();

    // Expect exactly 10 subdirectories named survey_0 .. survey_9
    Set<String> expectedNames = java.util.stream.IntStream.range(0, 10)
        .mapToObj(i -> "survey_" + i)
        .collect(Collectors.toSet());

    Set<Path> subdirs;
    try (Stream<Path> s = Files.list(newSurveyorDir)) {
      subdirs = s.filter(Files::isDirectory).collect(Collectors.toSet());
    }

    // check count and names
    Set<String> actualNames = subdirs.stream()
        .map(p -> p.getFileName().toString())
        .filter(p -> p.startsWith("survey_"))
        .collect(Collectors.toSet());
    assertEquals(expectedNames, actualNames, "Expected survey_0..survey_9 directories");

    // For each survey_N ensure the required files exist
    for (int i = 0; i < 10; i++) {
      Path sd = newSurveyorDir.resolve("survey_" + i);
      // required files: *.grc, *.grd, *.gri, history.jsonl, purls.txt

      // .grc file: must exist and be > 0 bytes
      Path grc;
      try (Stream<Path> s = Files.list(sd)) {
        grc = s.filter(p -> p.getFileName().toString().endsWith(".grc"))
               .findFirst().orElse(null);
      }
      assertTrue(grc != null, "Missing .grc in " + sd);
      assertTrue(Files.size(grc) > 0, "Empty .grc in " + sd);

      // .grd file: must exist and be > 0 bytes
      Path grd;
      try (Stream<Path> s = Files.list(sd)) {
        grd = s.filter(p -> p.getFileName().toString().endsWith(".grd"))
               .findFirst().orElse(null);
      }
      assertTrue(grd != null, "Missing .grd in " + sd);
      assertTrue(Files.size(grd) > 0, "Empty .grd in " + sd);

      // .gri file: must exist and be > 0 bytes
      Path gri;
      try (Stream<Path> s = Files.list(sd)) {
        gri = s.filter(p -> p.getFileName().toString().endsWith(".gri"))
               .findFirst().orElse(null);
      }
      assertTrue(gri != null, "Missing .gri in " + sd);
      assertTrue(Files.size(gri) > 0, "Empty .gri in " + sd);

      // history.jsonl: must exist and be > 0 bytes
      Path history = sd.resolve("history.jsonl");
      assertTrue(Files.exists(history), "Missing history.jsonl in " + sd);
      assertTrue(Files.size(history) > 0, "Empty history.jsonl in " + sd);

      // purls.txt: must exist (may be empty)
      Path purls = sd.resolve("purls.txt");
      assertTrue(Files.exists(purls), "Missing purls.txt in " + sd);
    }

    // ---- New checks: verify ginger-output zip -> unzip -> payload.enc -> untar -> payload contains survey_* ----

    Path gingerOutputDir = newSurveyorDir.resolve("ginger-output");
    assertTrue(Files.exists(gingerOutputDir) && Files.isDirectory(gingerOutputDir),
        "Missing ginger-output dir");

    List<Path> zips;
    try (Stream<Path> s = Files.list(gingerOutputDir)) {
      zips = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
               .collect(Collectors.toList());
    }
    assertEquals(1, zips.size(), "Expected exactly one zip in ginger-output");

    Path zipFile = zips.get(0);
    Path unzipDest = extractZipToDir(zipFile, gingerOutputDir);

    // Ensure payload.enc exists
    Path payloadEnc = unzipDest.resolve("payload.enc");
    assertTrue(Files.exists(payloadEnc), "Missing payload.enc in extracted zip");

    // Untar payload.enc into the unzipDest (requires 'tar' on PATH)
    int rc = untarFileInDir(unzipDest, "payload.enc");
    assertEquals(0, rc, "tar extraction failed (rc=" + rc + ")");

    Set<String> payloadNames;
    try (Stream<Path> s = Files.list(unzipDest)) {
      payloadNames = s.filter(Files::isDirectory)
          .map(pth -> pth.getFileName().toString())
          .filter(n -> n.startsWith("survey_"))
          .collect(Collectors.toSet());
    }

    // expectedNames was computed earlier for survey_0..survey_9
    assertEquals(expectedNames, payloadNames, "Payload survey dirs do not match");

    // ---- New checks: recursively hash each survey_* in the surveyor output and compare
    // to the corresponding survey_* in the extracted payload.

    java.util.Map<String, String> createdHashes = computeHashes(newSurveyorDir);
    java.util.Map<String, String> payloadHashes = computeHashes(unzipDest);

    // Ensure the sets of survey_* keys match
    assertEquals(createdHashes.keySet(), payloadHashes.keySet(), "Survey dir sets differ");

    // Compare hashes for each survey_*
    for (String k : createdHashes.keySet()) {
      assertEquals(createdHashes.get(k), payloadHashes.get(k),
          "Hash mismatch for " + k);
    }
  }

  @Test
  void runCommand_singleSurveyWhenLessThanMaxRecords() throws Exception {
    // Create input dir with 50 small unique text files (less than or equal to maxRecords=50)
    Path inputDir = Files.createTempDirectory("run-test-input-few");
    createFilesInDir(inputDir, 50);

    Path surveyorRoot = Path.of(System.getProperty("user.home"), ".spicelabs", "surveyor");

    Set<Path> before = snapshotDirectories(surveyorRoot);

    // Use the builder to configure and run the CLI with maxRecords=50
    SpiceLabsCLI.builder()
        .tag("test-tag")
        .command(SpiceLabsCLI.Command.run)
        .input(inputDir)
        .gingerArgs(Map.of("--skip-key","true", "--encrypt-only","true"))
        .goatRodeoArgs(Map.of("maxRecords","50"))
        .run();

    Set<Path> after = snapshotDirectories(surveyorRoot);

    // There should be exactly one new directory created under surveyorRoot
    Set<Path> diff = after.stream().filter(p -> !before.contains(p)).collect(Collectors.toSet());
    assertEquals(1, diff.size(), "Expected exactly one new surveyor directory");

    Path newSurveyorDir = diff.iterator().next();

    // Expect exactly one subdirectory named "survey"
    Set<Path> subdirs;
    try (Stream<Path> s = Files.list(newSurveyorDir)) {
      subdirs = s.filter(Files::isDirectory)
                 .filter(p -> p.getFileName().toString().startsWith("survey"))
                 .collect(Collectors.toSet());
    }

    // check count and name
    assertEquals(1, subdirs.size(), "Expected exactly one survey subdir");
    Path surveyDir = subdirs.iterator().next();
    assertEquals("survey", surveyDir.getFileName().toString(), "Expected single directory named 'survey'");

    // For the single survey ensure the required files exist
    Path sd = surveyDir;
    // required files: *.grc, *.grd, *.gri, history.jsonl, purls.txt

    // .grc file: must exist and be > 0 bytes
    Path grc;
    try (Stream<Path> s = Files.list(sd)) {
      grc = s.filter(p -> p.getFileName().toString().endsWith(".grc"))
             .findFirst().orElse(null);
    }
    assertTrue(grc != null, "Missing .grc in " + sd);
    assertTrue(Files.size(grc) > 0, "Empty .grc in " + sd);

    // .grd file: must exist and be > 0 bytes
    Path grd;
    try (Stream<Path> s = Files.list(sd)) {
      grd = s.filter(p -> p.getFileName().toString().endsWith(".grd"))
             .findFirst().orElse(null);
    }
    assertTrue(grd != null, "Missing .grd in " + sd);
    assertTrue(Files.size(grd) > 0, "Empty .grd in " + sd);

    // .gri file: must exist and be > 0 bytes
    Path gri;
    try (Stream<Path> s = Files.list(sd)) {
      gri = s.filter(p -> p.getFileName().toString().endsWith(".gri"))
             .findFirst().orElse(null);
    }
    assertTrue(gri != null, "Missing .gri in " + sd);
    assertTrue(Files.size(gri) > 0, "Empty .gri in " + sd);

    // history.jsonl: must exist and be > 0 bytes
    Path history = sd.resolve("history.jsonl");
    assertTrue(Files.exists(history), "Missing history.jsonl in " + sd);
    assertTrue(Files.size(history) > 0, "Empty history.jsonl in " + sd);

    // purls.txt: must exist (may be empty)
    Path purls = sd.resolve("purls.txt");
    assertTrue(Files.exists(purls), "Missing purls.txt in " + sd);

    // ---- Check ginger-output zip -> unzip -> payload.enc -> untar -> payload contains single survey ----

    Path gingerOutputDir = newSurveyorDir.resolve("ginger-output");
    assertTrue(Files.exists(gingerOutputDir) && Files.isDirectory(gingerOutputDir),
        "Missing ginger-output dir");

    List<Path> zips;
    try (Stream<Path> s = Files.list(gingerOutputDir)) {
      zips = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
               .collect(Collectors.toList());
    }
    assertEquals(1, zips.size(), "Expected exactly one zip in ginger-output");

    Path zipFile = zips.get(0);
    Path unzipDest = extractZipToDir(zipFile, gingerOutputDir);

    // Ensure payload.enc exists
    Path payloadEnc = unzipDest.resolve("payload.enc");
    assertTrue(Files.exists(payloadEnc), "Missing payload.enc in extracted zip");

    // Untar payload.enc into the unzipDest (requires 'tar' on PATH)
    int rc = untarFileInDir(unzipDest, "payload.enc");
    assertEquals(0, rc, "tar extraction failed (rc=" + rc + ")");

    // Ensure extracted payload contains a single "survey" directory
    Set<String> payloadNames;
    try (Stream<Path> s = Files.list(unzipDest)) {
      payloadNames = s.filter(Files::isDirectory)
          .map(pth -> pth.getFileName().toString())
          .collect(Collectors.toSet());
    }

    assertTrue(payloadNames.contains("survey"), "Payload must contain 'survey' directory");
    // there should be exactly one directory and it's "survey"
    assertEquals(1, payloadNames.size(), "Expected exactly one directory in payload and it should be 'survey'");

    // Compare hashes for the single survey dir
    String createdHash = dirHash(newSurveyorDir.resolve("survey"));
    String payloadHash = dirHash(unzipDest.resolve("survey"));
    assertEquals(createdHash, payloadHash, "Hash mismatch for 'survey' directory");
  }

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
    } else {
      return Set.of();
    }
  }
  
  private static Path extractZipToDir(Path zipFile, Path parentDir) throws Exception {
    String zipBase = zipFile.getFileName().toString();
    if (zipBase.endsWith(".zip")) {
      zipBase = zipBase.substring(0, zipBase.length() - 4);
    }
    Path unzipDest = parentDir.resolve(zipBase);
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
               java.io.OutputStream outStream = Files.newOutputStream(out)) {
            in.transferTo(outStream);
          }
        }
      }
    }
    return unzipDest;
  }

  private static int untarFileInDir(Path dir, String filename) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("tar", "-xf", filename);
    pb.directory(dir.toFile());
    Process p = pb.start();
    return p.waitFor();
  }

  private static java.util.Map<String, String> computeHashes(Path root) throws Exception {
    try (Stream<Path> s = Files.list(root)) {
      return s.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(n -> n.startsWith("survey_"))
          .collect(Collectors.toMap(
              name -> name,
              name -> {
                try {
                  return dirHash(root.resolve(name));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
          ));
    }
  }

  // Compute a deterministic SHA-256 hash for a directory's files (paths + contents).
  private static String dirHash(Path dir) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    List<Path> files;
    try (Stream<Path> walk = Files.walk(dir)) {
      files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
    }
    files.sort(Comparator.comparing(p -> dir.relativize(p).toString().replace('\\','/')));
    for (Path f : files) {
      String rel = dir.relativize(f).toString().replace('\\','/');
      byte[] relBytes = rel.getBytes(StandardCharsets.UTF_8);
      md.update(relBytes);
      md.update((byte)0);
      try (InputStream in = Files.newInputStream(f)) {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
          md.update(buf, 0, r);
        }
      }
      md.update((byte)0);
    }
    byte[] digest = md.digest();
    // format as hex
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
  
}