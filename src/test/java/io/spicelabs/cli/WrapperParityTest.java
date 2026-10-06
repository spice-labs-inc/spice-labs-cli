package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

  /**
   * Manifest handed to the wrappers for the current test, or null to let them fall back to
   * the one embedded in the script. The registry cases set this, because the plugin that
   * contributes {@code registry} is not on the test classpath — and the whole point of the
   * manifest is that the wrapper learns those commands from the image rather than from a
   * list it carries.
   */
  Path manifestFile;

  /** Everything the last wrapper run printed, stdout and stderr together. */
  String lastOutput = "";

  private static final String MOCK_DOCKER = String.join("\n",
      "#!/bin/bash",
      "echo \"$@\" > ARGS_FILE",
      "if [ -n \"${MOCK_DOCKER_CONFIG_COPY:-}\" ]; then",
      "  prev=''",
      "  for a in \"$@\"; do",
      "    if [ \"$prev\" = -v ]; then",
      "      case \"$a\" in *:/mnt/spice/docker-config:ro) cp \"${a%:/mnt/spice/docker-config:ro}/config.json\" \"$MOCK_DOCKER_CONFIG_COPY\" ;; esac",
      "    fi",
      "    prev=\"$a\"",
      "  done",
      "fi",
      "");

  @BeforeAll
  static void setup() {
    projectDir = Path.of(System.getProperty("user.dir"));
    hasPwsh = checkCommand("pwsh");
  }

  /**
   * Render a manifest for the built-in commands plus a stand-in for the allspice plugin,
   * so the registry cases exercise the same path the real plugin takes.
   */
  private void useManifestWithRegistryPlugin() throws Exception {
    CommandLine root = SpiceLabsCLI.newCommandLine();
    if (!root.getSubcommands().containsKey("registry")) {
      root.addSubcommand("registry", new CommandLine(new FakeRegistryCommand()));
    }
    manifestFile = Files.createTempFile("parity-manifest", ".txt");
    Files.writeString(manifestFile, PathManifest.render(root, false));
  }

  @Command(name = "registry", subcommands = {
      FakeRegistryDiscover.class, FakeRegistryRun.class,
      FakeRegistryStatus.class, FakeRegistryCbom.class })
  static class FakeRegistryCommand implements Callable<Integer> {
    public Integer call() { return 0; }
  }

  @Command(name = "discover")
  static class FakeRegistryDiscover implements Callable<Integer> {
    @Option(names = "--config", required = true, paramLabel = "FILE") Path config;
    @Option(names = "--output", paramLabel = "FILE") Path output;
    public Integer call() { return 0; }
  }

  @Command(name = "run")
  static class FakeRegistryRun implements Callable<Integer> {
    @Option(names = "--config", required = true, paramLabel = "FILE") Path config;
    @Option(names = "--discovery", paramLabel = "FILE") Path discovery;
    public Integer call() { return 0; }
  }

  @Command(name = "status")
  static class FakeRegistryStatus implements Callable<Integer> {
    @Option(names = "--config", required = true, paramLabel = "FILE") Path config;
    @Option(names = "--json") boolean json;
    public Integer call() { return 0; }
  }

  @Command(name = "cbom")
  static class FakeRegistryCbom implements Callable<Integer> {
    @Option(names = "--config", required = true, paramLabel = "FILE") Path config;
    @Option(names = "--rogues", paramLabel = "FILE") Path rogues;
    @Option(names = "--output", paramLabel = "DIR") Path output;
    public Integer call() { return 0; }
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

  // ── survey image (oras pull) ────────────────────────────────────────────────

  /**
   * `survey image` mounts the host docker config so oras inside the container can
   * authenticate to private registries (e.g. GHCR). The binned image ref is a bare
   * String positional; the docker-config mount is wrapper logic, not a manifest path.
   */
  @Test
  void surveyImageMountsDockerConfig() throws Exception {
    Path cfgDir = Files.createTempDirectory("parity-dockercfg");
    Files.writeString(cfgDir.resolve("config.json"), "{\"auths\":{\"ghcr.io\":{}}}");

    String args = assertParityOrBashOnly(Map.of("DOCKER_CONFIG", cfgDir.toString()),
        "survey", "image", "ghcr.io/spice-labs-inc/grinder:0.1.0", "--no-upload");

    assertTrue(args.contains(cfgDir.toAbsolutePath() + ":/mnt/spice/docker-config:ro"),
        "docker config must be mounted read-only: " + args);
  }

  /** Without a config.json, the mount is omitted and the ref passes through untouched. */
  @Test
  void surveyImageWithoutDockerConfig() throws Exception {
    Path emptyCfg = Files.createTempDirectory("parity-dockercfg-empty");

    String args = assertParityOrBashOnly(Map.of("DOCKER_CONFIG", emptyCfg.toString()),
        "survey", "image", "alpine:latest", "--no-upload");

    assertFalse(args.contains("/mnt/spice/docker-config"),
        "no config.json means no mount: " + args);
    assertTrue(args.contains("survey image alpine:latest"),
        "the bare ref must pass through untouched: " + args);
  }

  // ── survey inventory docker://<image> ──────────────────────────────────────

  /** The image goes through untouched: never mounted, never refused as a missing path. */
  @Test
  void surveyInventoryDockerInputPassesThrough() throws Exception {
    Path emptyCfg = Files.createTempDirectory("parity-dockercfg-empty");

    String args = assertParityOrBashOnly(Map.of("DOCKER_CONFIG", emptyCfg.toString()),
        "survey", "inventory", "my-app", "docker://ghcr.io/acme/app:1.0", "--no-upload");

    assertTrue(args.contains("survey inventory my-app docker://ghcr.io/acme/app:1.0 --no-upload"), args);
  }

  /** `nginx:1.27` where a path belongs reaches the CLI, which explains docker://. */
  @Test
  void surveyInventoryImageLookingInputReachesTheCli() throws Exception {
    String args = assertParityOrBashOnly("survey", "inventory", "my-app", "nginx:1.27", "--no-upload");

    assertTrue(args.contains("survey inventory my-app nginx:1.27 --no-upload"), args);
  }

  /** A config.json with the login inline is mounted as it is, and no helper is asked. */
  @Test
  void dockerLoginInlineIsMountedAsItIs() throws Exception {
    Path cfgDir = dockerConfig(
        "{\"auths\":{\"ghcr.io\":{\"auth\":\"aW5saW5lOng=\"}},\"credsStore\":\"fake\"}");

    for (String args : withHelper(cfgDir, "survey", "inventory", "my-app", "docker://ghcr.io/acme/app:1.0")) {
      assertTrue(args.contains(cfgDir.toAbsolutePath() + ":/mnt/spice/docker-config:ro"), args);
    }
    assertEquals("", Files.readString(helperLog).trim(), "no helper may be asked");
  }

  /** Docker Desktop's credsStore: the helper's login is handed over for this registry only. */
  @Test
  void dockerLoginFromCredsStoreIsHandedOverAndRemoved() throws Exception {
    Path cfgDir = dockerConfig("{\"auths\":{\"ghcr.io\":{}},\"credsStore\":\"fake\"}");

    withHelper(cfgDir, "survey", "inventory", "my-app", "docker://ghcr.io/acme/app:1.0", "--no-upload");

    assertEquals(Map.of("ghcr.io", Map.of("auth", base64("ghuser:gh-s3cret"))), handedOverAuths());
  }

  /** credHelpers names the helper for one registry, ahead of credsStore. */
  @Test
  void dockerLoginFromCredHelpersWinsOverCredsStore() throws Exception {
    Path cfgDir = dockerConfig(
        "{\"credsStore\":\"not-installed\",\"credHelpers\":{\"ghcr.io\":\"fake\"}}");

    withHelper(cfgDir, "survey", "inventory", "my-app", "docker://ghcr.io/acme/app:1.0");

    assertEquals(Map.of("ghcr.io", Map.of("auth", base64("ghuser:gh-s3cret"))), handedOverAuths());
  }

  /** Docker Hub images are logged in under Docker Hub's key, however they are written. */
  @Test
  void dockerHubUsesItsLegacyKey() throws Exception {
    Path cfgDir = dockerConfig("{\"credsStore\":\"fake\"}");
    String hub = "https://index.docker.io/v1/";

    withHelper(cfgDir, "survey", "inventory", "my-app", "docker://nginx:1.27");
    assertEquals(Map.of(hub, Map.of("auth", base64("hubuser:hub-s3cret"))), handedOverAuths());

    withHelper(cfgDir, "survey", "inventory", "my-app", "docker://docker.io/library/nginx:1.27");
    assertEquals(Map.of(hub, Map.of("auth", base64("hubuser:hub-s3cret"))), handedOverAuths());
  }

  /** A helper that answers with username <token> holds an identity token. */
  @Test
  void anIdentityTokenIsHandedOverAsOne() throws Exception {
    Path cfgDir = dockerConfig("{\"credsStore\":\"fake\"}");

    withHelper(cfgDir, "survey", "inventory", "my-app", "docker://tok.example.com/acme/app:1.0");

    assertEquals(Map.of("tok.example.com", Map.of("identitytoken", "tok-s3cret")), handedOverAuths());
  }

  /** The older `survey image` spelling gets the same login, its reference found past options. */
  @Test
  void theSurveyImageAliasUsesTheHelperToo() throws Exception {
    Path cfgDir = dockerConfig("{\"credsStore\":\"fake\"}");

    withHelper(cfgDir, "survey", "image", "--subject", "my-app", "ghcr.io/acme/app:1.0");

    assertEquals(Map.of("ghcr.io", Map.of("auth", base64("ghuser:gh-s3cret"))), handedOverAuths());
  }

  /** No helper installed: today's mount, so public images still work. */
  @Test
  void aMissingHelperFallsBackToTheConfigAsItIs() throws Exception {
    Path cfgDir = dockerConfig("{\"credsStore\":\"not-installed\"}");

    for (String args : withHelper(cfgDir, "survey", "inventory", "my-app", "docker://ghcr.io/acme/app:1.0")) {
      assertTrue(args.contains(cfgDir.toAbsolutePath() + ":/mnt/spice/docker-config:ro"), args);
    }
  }

  /** A helper that knows nothing about the registry: today's mount as well. */
  @Test
  void aHelperWithNoLoginFallsBackToTheConfigAsItIs() throws Exception {
    Path cfgDir = dockerConfig("{\"credsStore\":\"fake\"}");

    for (String args : withHelper(cfgDir, "survey", "inventory", "my-app", "docker://quay.io/acme/app:1.0")) {
      assertTrue(args.contains(cfgDir.toAbsolutePath() + ":/mnt/spice/docker-config:ro"), args);
    }
  }

  private Path helperLog;
  private Path handedOver;

  private static Path dockerConfig(String json) throws Exception {
    Path dir = Files.createTempDirectory("parity-dockercfg");
    Files.writeString(dir.resolve("config.json"), json);
    return dir;
  }

  /**
   * Run both wrappers with {@code docker-credential-fake} on PATH, checking parity, that the
   * secret never reaches the output, and that a temporary login is gone once the run ends.
   * Returns the normalized docker args of each run.
   */
  private java.util.List<String> withHelper(Path cfgDir, String... cliArgs) throws Exception {
    Path helperDir = Files.createTempDirectory("parity-helper");
    helperLog = helperDir.resolve("asked.log");
    Files.writeString(helperLog, "");
    handedOver = helperDir.resolve("handed-over.json");
    Path helper = helperDir.resolve("docker-credential-fake");
    Files.writeString(helper, FAKE_HELPER.replace("LOG_FILE", helperLog.toString()));
    helper.toFile().setExecutable(true);
    Map<String, String> env = Map.of(
        "DOCKER_CONFIG", cfgDir.toString(),
        "PATH", helperDir.toString(),
        "MOCK_DOCKER_CONFIG_COPY", handedOver.toString());

    java.util.List<String> runs = new java.util.ArrayList<>();
    String bashHandedOver = null;
    for (String shell : hasPwsh ? new String[] {"bash", "pwsh"} : new String[] {"bash"}) {
      Files.deleteIfExists(handedOver);
      String raw = runWrapper(shell, env, cliArgs);
      assertFalse(lastOutput.contains("s3cret") || raw.contains("s3cret"),
          shell + ": the secret must never be printed or passed as an argument");
      var mounted = java.util.regex.Pattern.compile("-v (\\S+):/mnt/spice/docker-config:ro").matcher(raw);
      assertTrue(mounted.find(), shell + ": the docker config is mounted: " + raw);
      if (!Path.of(mounted.group(1)).equals(cfgDir.toAbsolutePath()) && !Path.of(mounted.group(1)).equals(cfgDir)) {
        assertFalse(Files.exists(Path.of(mounted.group(1))),
            shell + ": the temporary login must be removed when the run ends");
      }
      String copy = Files.exists(handedOver) ? Files.readString(handedOver) : null;
      if (bashHandedOver == null) {
        bashHandedOver = copy;
      } else if (copy != null) {
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(bashHandedOver),
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(copy),
            "both wrappers hand over the same login");
      }
      runs.add(normalizeDockerArgs(raw));
    }
    if (runs.size() == 2) {
      assertEquals(runs.get(0), runs.get(1), "Bash and PowerShell wrappers produced different docker args");
    }
    return runs;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handedOverAuths() throws Exception {
    Map<String, Object> config = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(Files.readString(handedOver), Map.class);
    assertEquals(java.util.Set.of("auths"), config.keySet(), "only the one login is handed over");
    return (Map<String, Object>) config.get("auths");
  }

  private static String base64(String s) {
    return java.util.Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** A credential helper that knows three registries and logs what it was asked. */
  private static final String FAKE_HELPER = String.join("\n",
      "#!/bin/bash",
      "[ \"$1\" = get ] || exit 1",
      "IFS= read -r registry || true",
      "echo \"$registry\" >> 'LOG_FILE'",
      "case \"$registry\" in",
      "  https://index.docker.io/v1/) echo '{\"ServerURL\":\"https://index.docker.io/v1/\",\"Username\":\"hubuser\",\"Secret\":\"hub-s3cret\"}' ;;",
      "  ghcr.io) echo '{\"ServerURL\":\"ghcr.io\",\"Username\":\"ghuser\",\"Secret\":\"gh-s3cret\"}' ;;",
      "  tok.example.com) echo '{\"ServerURL\":\"tok.example.com\",\"Username\":\"<token>\",\"Secret\":\"tok-s3cret\"}' ;;",
      "  *) echo 'credentials not found in native keychain'; exit 1 ;;",
      "esac",
      "");

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

  // ── registry (allspice plugin) ──────────────────────────────────────────────

  @Test
  void registryDiscover_spaceSeparatedPaths() throws Exception {
    useManifestWithRegistryPlugin();
    Path dir = Files.createTempDirectory("parity-reg-discover");
    Path cfg = dir.resolve("nexus.toml");
    Files.writeString(cfg, "x = 1\n");
    Path out = dir.resolve("discovery.toml"); // does not exist yet (parent does)

    String args = assertParityOrBashOnly(
        "registry", "discover", "--config", cfg.toString(), "--output", out.toString());

    assertTrue(args.contains(dir.toAbsolutePath().toString()), "the config's directory is mounted");
    assertFalse(Files.isDirectory(out),
        "--output is paramLabel=FILE, so only its parent is created — never a directory "
            + "named discovery.toml");
  }

  @Test
  void registryRun_joinedPaths() throws Exception {
    useManifestWithRegistryPlugin();
    Path dir = Files.createTempDirectory("parity-reg-run");
    Path cfg = dir.resolve("nexus.toml");
    Files.writeString(cfg, "x = 1\n");
    Path disc = dir.resolve("discovery.toml");
    Files.writeString(disc, "");

    assertParityOrBashOnly(
        "registry", "run", "--config=" + cfg, "--discovery=" + disc);
  }

  @Test
  void registryStatus_withJson() throws Exception {
    useManifestWithRegistryPlugin();
    Path dir = Files.createTempDirectory("parity-reg-status");
    Path cfg = dir.resolve("nexus.toml");
    Files.writeString(cfg, "x = 1\n");

    assertParityOrBashOnly(
        "registry", "status", "--config", cfg.toString(), "--json");
  }

  /**
   * The defect that motivated the manifest. {@code --rogues} is a {@code Path} on a
   * plugin subcommand; no wrapper's hardcoded list mentioned it, so its value reached the
   * container with nothing mounted for it. Nothing about the plugin changed to fix this —
   * the wrapper now derives it from the option's type.
   */
  @Test
  void registryCbom_pluginPathOptionIsMounted() throws Exception {
    useManifestWithRegistryPlugin();
    Path dir = Files.createTempDirectory("parity-reg-cbom");
    Path cfg = dir.resolve("allspice.toml");
    Files.writeString(cfg, "x = 1\n");
    Path roguesDir = Files.createTempDirectory("parity-reg-rogues");
    Path rogues = roguesDir.resolve("rogues.json");
    Files.writeString(rogues, "{}");
    Path out = dir.resolve("cbom-out");

    String args = assertParityOrBashOnly(
        "registry", "cbom", "--config", cfg.toString(),
        "--rogues", rogues.toString(), "--output", out.toString());

    assertTrue(args.contains(roguesDir.toAbsolutePath().toString()),
        "--rogues lives in a different tree, so it needs its own mount: " + args);
    assertTrue(Files.isDirectory(out),
        "--output is paramLabel=DIR, so the directory itself is created");
  }

  /**
   * A subject that happens to share a subcommand's name is still a subject. The old
   * wrapper matched positional tokens against a hardcoded name list and swallowed them.
   */
  @Test
  void subjectNamedLikeASubcommandIsStillASubject() throws Exception {
    Path inputDir = Files.createTempDirectory("parity-subject-run");
    Files.writeString(inputDir.resolve("file.txt"), "test");

    String args = assertParityOrBashOnly(
        "survey", "inventory", "run", inputDir.toString());

    assertTrue(args.contains("inventory run "), "`run` stays the subject: " + args);
  }

  /**
   * Mounting a host directory over one the image owns would hide the installation, so
   * such a path is relocated and the mapping recorded for PathTranslator to reverse.
   */
  @Test
  void pathUnderAReservedDirectoryIsRelocated() throws Exception {
    String args = normalizeDockerArgs(runWrapper("bash", "survey", "inventory", "my-app", "/etc"));
    assertTrue(args.contains("/mnt/spice/"),
        "a path under a reserved root must not be identity-mounted: " + args);
  }

  // ── Infra ─────────────────────────────────────────────────────────────────

  /**
   * Run both wrappers and assert identical docker args (after normalizing
   * known platform differences).
   * If pwsh is not available, only test bash.
   */
  private String assertParityOrBashOnly(String... cliArgs) throws Exception {
    return assertParityOrBashOnly(Map.of(), cliArgs);
  }

  private String assertParityOrBashOnly(Map<String, String> extraEnv, String... cliArgs)
      throws Exception {
    String bashDockerArgs = normalizeDockerArgs(runWrapper("bash", extraEnv, cliArgs));

    if (hasPwsh) {
      String pwshDockerArgs = normalizeDockerArgs(runWrapper("pwsh", extraEnv, cliArgs));
      assertEquals(bashDockerArgs, pwshDockerArgs,
          "Bash and PowerShell wrappers produced different docker args for: " +
              String.join(" ", cliArgs));
    }

    // Basic sanity: docker args should not be empty
    assertFalse(bashDockerArgs.isBlank(), "Docker args should not be empty");
    return bashDockerArgs;
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
    // A temporary registry login gets a random directory name; only its place matters.
    volumes.replaceAll(v -> v.contains("spice-docker-config") && v.endsWith(":/mnt/spice/docker-config:ro")
        ? "<temporary-login>:/mnt/spice/docker-config:ro" : v);
    java.util.Collections.sort(volumes);

    return "VOLUMES=" + String.join(",", volumes) + " ARGS=" + cliArgs;
  }

  /**
   * Run a wrapper script with a mock docker that captures all args.
   * Returns the captured docker arguments as a single string.
   */
  private String runWrapper(String shell, String... cliArgs) throws Exception {
    return runWrapper(shell, Map.of(), cliArgs);
  }

  private String runWrapper(String shell, Map<String, String> extraEnv, String... cliArgs)
      throws Exception {
    Path mockBin = Files.createTempDirectory("mock-docker-" + shell);
    Path argsFile = Files.createTempFile("docker-args-" + shell, ".txt");

    // Mock docker that captures args (pwsh on Linux uses the same bash mock). With
    // MOCK_DOCKER_CONFIG_COPY set it also keeps the docker config it was handed, which a
    // temporary registry login only lives as long as the run.
    Files.writeString(mockBin.resolve("docker"), MOCK_DOCKER.replace("ARGS_FILE", argsFile.toString()));
    mockBin.resolve("docker").toFile().setExecutable(true);

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
    // The mock docker records every invocation, so a manifest refresh would both clobber
    // the captured args and make the result depend on whatever image is on the machine.
    // Tests supply their manifest explicitly instead.
    pb.environment().put("SPICE_SKIP_MANIFEST_REFRESH", "1");
    for (Map.Entry<String, String> e : extraEnv.entrySet()) {
      if (e.getKey().equals("PATH")) {
        pb.environment().put("PATH", e.getValue() + ":" + pb.environment().get("PATH"));
      } else {
        pb.environment().put(e.getKey(), e.getValue());
      }
    }
    if (manifestFile != null) {
      pb.environment().put("SPICE_PATH_MANIFEST", manifestFile.toString());
    }
    pb.redirectErrorStream(true);

    Process p = pb.start();
    lastOutput = new String(p.getInputStream().readAllBytes());
    p.waitFor();

    return Files.readString(argsFile).trim();
  }
}
