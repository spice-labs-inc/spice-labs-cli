package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The command tree and the upload paths follow the edition's feature manifest.
 *
 * <p>The editions here are examples, not any distribution's: what is being tested is that a
 * named set of features produces the matching command tree, whoever chose the set.
 */
class EditionGateTest {

  /** No survey of any kind, and no upload: a build whose commands all come from plugins. */
  static final Edition BULK_ONLY = Edition.of("bulk-only", "Example Bulk", true, "bulk");
  /** Inventory surveys but no runtime surveys. */
  static final Edition NO_RUNTIME = Edition.of("no-runtime", "Example Standard", false,
      Edition.INVENTORY_SURVEYS);
  /** Every survey, but never uploads. */
  static final Edition AIRGAPPED = Edition.of("offline", "Example Offline", true,
      Edition.INVENTORY_SURVEYS, Edition.RUNTIME_SURVEYS);

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  @AfterEach
  void reset() {
    Edition.install(null);
  }

  private CommandLine cli(Edition edition) {
    Edition.install(edition);
    CommandLine cmd = SpiceLabsCLI.newCommandLine(RunConfiguration.EMPTY, edition);
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    return cmd;
  }

  @Test
  void edition_bulkOnly_help_omitsSurvey() {
    int rc = cli(BULK_ONLY).execute("--help");
    assertEquals(0, rc);
    String help = out.toString();
    assertFalse(help.contains("survey"), help);
    assertTrue(help.contains("Example Bulk"), help);
    assertTrue(help.contains("pass"), help);
  }

  @Test
  void edition_bulkOnly_version_namesEdition() {
    int rc = cli(BULK_ONLY).execute("--version");
    assertEquals(0, rc);
    assertTrue(out.toString().contains("Example Bulk (bulk-only): airgapped; features: "), out.toString());
  }

  @Test
  void edition_bulkOnly_survey_isRefused(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.txt"), "x");
    int rc = cli(BULK_ONLY).execute("survey", "inventory", "s", dir.toString(), "--no-upload");
    assertNotEquals(0, rc);
    // picocli reports the unmatched `survey` through the CLI's handler (System.err).
  }

  @Test
  void edition_noRuntime_surveyRuntime_isUnknownSurveyType() {
    CommandLine cmd = cli(NO_RUNTIME);
    assertFalse(cmd.getSubcommands().get("survey").getSubcommands().containsKey("runtime"));
    assertTrue(cmd.getSubcommands().get("survey").getSubcommands().containsKey("inventory"));
    int rc = cmd.execute("survey", "runtime", "s", "--jfr", "--no-upload", "--", "true");
    assertNotEquals(0, rc);
  }

  @Test
  void edition_noRuntime_surveyHelp_omitsRuntime() {
    int rc = cli(NO_RUNTIME).execute("survey", "--help");
    assertEquals(0, rc);
    String help = out.toString();
    assertTrue(help.contains("inventory"), help);
    assertFalse(help.contains("runtime"), help);
  }

  @Test
  void edition_airgapped_uploadOnly_fails(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.txt"), "x");
    Edition.install(AIRGAPPED);
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.input = dir;
    cmd.uploadOnly = true;
    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, cmd::run);
    assertTrue(ex.getMessage().contains("not available in the Example Offline edition"), ex.getMessage());
  }

  @Test
  void edition_airgapped_uploadArgs_fails(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.txt"), "x");
    Edition.install(AIRGAPPED);
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.input = dir;
    cmd.gingerArgsRaw = List.of("--encrypt-only=true");
    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, cmd::run);
    assertTrue(ex.getMessage().contains("--upload-args is not available"), ex.getMessage());
  }

  @Test
  void edition_airgapped_surveyInventory_impliesNoUpload(@TempDir Path dir) throws Exception {
    Path in = dir.resolve("in");
    Files.createDirectories(in);
    Files.writeString(in.resolve("a.txt"), "x");
    Edition.install(AIRGAPPED);
    // No --no-upload and no SPICE_PASS: a connected edition would demand the pass here.
    int rc = cli(AIRGAPPED).execute("survey", "inventory", "s", in.toString(), "--output", dir.resolve("out").toString());
    assertEquals(0, rc, err.toString());
    assertFalse(err.toString().contains("SPICE_PASS must be set"), err.toString());
    try (var files = Files.walk(dir.resolve("out"))) {
      assertTrue(files.anyMatch(f -> f.toString().endsWith(".grd")), "survey output written");
    }
  }

  @Test
  void edition_airgapped_hidesUploadOptions_inHelp() {
    int rc = cli(AIRGAPPED).execute("survey", "inventory", "--help");
    assertEquals(0, rc);
    String help = out.toString();
    assertFalse(help.contains("--upload-only"), help);
    assertFalse(help.contains("--upload-args"), help);
    assertTrue(help.contains("--no-upload"), help);
  }

  @Test
  void edition_airgapped_register_throws() {
    Edition.install(AIRGAPPED);
    IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
        () -> SurveyRegistration.register("x.y.z", "INVENTORY_SURVEY", "s", null));
    assertTrue(ex.getMessage().contains("never uploads"), ex.getMessage());
  }

  @Test
  void edition_bulkOnly_powershellCompletion_omitsSurvey() {
    String header = GeneratePowershellCompletion.header(BULK_ONLY);
    assertFalse(header.contains("survey"), header);
    assertTrue(header.contains("pass"), header);
  }

  @Test
  void edition_noRuntime_powershellCompletion_omitsRuntime() {
    String header = GeneratePowershellCompletion.header(NO_RUNTIME);
    assertTrue(header.contains("inventory"), header);
    assertFalse(header.contains("runtime"), header);
    assertTrue(header.contains("--upload-only"), header);
    assertFalse(GeneratePowershellCompletion.header(AIRGAPPED).contains("--upload-only"));
  }

  @Test
  void edition_noRuntime_bashCompletion_omitsRuntime() {
    int rc = cli(NO_RUNTIME).execute("generate-completion");
    assertEquals(0, rc);
    assertFalse(out.toString().contains("runtime"), out.toString());
    assertTrue(out.toString().contains("inventory"), out.toString());
  }

  @Test
  void edition_noRuntime_pathManifest_omitsRuntime() {
    // The manifest the wrapper mounts paths from is rendered from the live tree, so it
    // describes exactly the commands this edition has. (The command prints to System.out,
    // so the renderer is asked directly.)
    String manifest = PathManifest.render(cli(NO_RUNTIME), false);
    assertFalse(manifest.contains("spice/survey/runtime"), manifest);
    assertTrue(manifest.contains("spice/survey/inventory"), manifest);
  }

  @Test
  void edition_unrestricted_treeIsUnchanged() {
    CommandLine cmd = cli(Edition.UNRESTRICTED);
    var survey = cmd.getSubcommands().get("survey").getSubcommands();
    assertTrue(survey.containsKey("inventory") && survey.containsKey("runtime") && survey.containsKey("image"));
    int rc = cmd.execute("--version");
    assertEquals(0, rc);
    assertFalse(out.toString().contains("features:"), out.toString());
  }

  @Test
  void pluginContext_exposesTheUploadPolicy() {
    // The one thing a plugin cannot work out for itself. What it can *do* is answered by
    // whether its own code and tools are present, not by asking the host.
    PluginContext airgapped =
        new PluginContext(new DefaultSpiceContext("1.0", null, BULK_ONLY), RunConfiguration.EMPTY, List.of());
    assertTrue(airgapped.airgapped());
    PluginContext connected =
        new PluginContext(new DefaultSpiceContext("1.0", null, NO_RUNTIME), RunConfiguration.EMPTY, List.of());
    assertFalse(connected.airgapped());
  }
}
