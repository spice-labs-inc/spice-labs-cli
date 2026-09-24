package io.spicelabs.cli;

import static io.spicelabs.cli.LicenseFixtures.KEY;
import static io.spicelabs.cli.LicenseFixtures.spiceLicense;
import static io.spicelabs.cli.LicenseFixtures.signed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * The license gate as the command line applies it: which commands it lets through unlicensed,
 * and that a licensed run proceeds.
 *
 * <p>Refusals are logged, not written to picocli's streams, so they are captured from logback.
 */
class LicenseGateTest {

  /** An airgapped edition with inventory surveys, licensed against the test key. */
  static final Edition LICENSED = Edition.of("ex", "Example Pro", true,
      "static-detect", Edition.INVENTORY_SURVEYS, "cbom-material").licensedBy(KEY);
  static final Edition UNLICENSED = Edition.of("ex", "Example Standard", true,
      "static-detect", Edition.INVENTORY_SURVEYS, "cbom-material");

  static final String VALID_PASS = signed(spiceLicense("static-detect", Edition.INVENTORY_SURVEYS, "cbom-material"));
  static final String SHORT_PASS = signed(spiceLicense("static-detect"));

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @AfterEach
  void reset() {
    Edition.install(null);
    DefaultSpiceContext.install(null);
    ((Logger) LoggerFactory.getLogger(LicenseGate.class)).detachAppender(logged);
  }

  private CommandLine cli(Edition edition, String spicePass) {
    Edition.install(edition);
    DefaultSpiceContext context = new DefaultSpiceContext("test", spicePass, edition);
    DefaultSpiceContext.install(context);
    logged.start();
    ((Logger) LoggerFactory.getLogger(LicenseGate.class)).addAppender(logged);
    CommandLine cmd = SpiceLabsCLI.newCommandLine(RunConfiguration.EMPTY, edition, context);
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    return cmd;
  }

  private String loggedText() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent event : logged.list) {
      sb.append(event.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }

  private static Path surveyInput(Path dir) throws Exception {
    Path in = dir.resolve("in");
    Files.createDirectories(in);
    Files.writeString(in.resolve("a.txt"), "x");
    return in;
  }

  // ---- refusals ----------------------------------------------------------------------------

  @Test
  void unlicensed_surveyInventory_isRefused(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    int rc = cli(LICENSED, null).execute("survey", "inventory", "s", in.toString(), "--output", dir.resolve("out").toString());
    assertEquals(LicenseGate.EXIT_CODE, rc);
    assertTrue(loggedText().contains("Example Pro edition requires a Spice License"), loggedText());
    assertFalse(Files.exists(dir.resolve("out")), "nothing was surveyed");
  }

  @Test
  void spicePass_surveyInventory_isRefused_namingTheForm(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    int rc = cli(LICENSED, LicenseFixtures.spicePass()).execute("survey", "inventory", "s", in.toString());
    assertEquals(LicenseGate.EXIT_CODE, rc);
    assertTrue(loggedText().contains("a Spice Pass (long-duration)"), loggedText());
  }

  @Test
  void insufficientGrant_surveyInventory_isRefused(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    int rc = cli(LICENSED, SHORT_PASS).execute("survey", "inventory", "s", in.toString());
    assertEquals(LicenseGate.EXIT_CODE, rc);
    // Edition features are a set; name each missing one without assuming their order.
    assertTrue(loggedText().contains("lacks "), loggedText());
    assertTrue(loggedText().contains("inventory-surveys"), loggedText());
    assertTrue(loggedText().contains("cbom-material"), loggedText());
  }

  @Test
  void refusal_doesNotPrintThePass(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    cli(LICENSED, SHORT_PASS).execute("survey", "inventory", "s", in.toString());
    String everything = loggedText() + out + err;
    assertFalse(everything.contains(SHORT_PASS.split("\\.")[2]), everything);
  }

  // ---- what proceeds ---------------------------------------------------------------------------

  @Test
  void licensed_surveyInventory_runs(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    int rc = cli(LICENSED, VALID_PASS).execute("survey", "inventory", "s", in.toString(), "--output", dir.resolve("out").toString());
    assertEquals(0, rc, err + loggedText());
    try (var files = Files.walk(dir.resolve("out"))) {
      assertTrue(files.anyMatch(f -> f.toString().endsWith(".grd")), "survey output written");
    }
  }

  @Test
  void editionWithoutKey_runsWithoutACredential(@TempDir Path dir) throws Exception {
    Path in = surveyInput(dir);
    int rc = cli(UNLICENSED, null).execute("survey", "inventory", "s", in.toString(), "--output", dir.resolve("out").toString());
    assertEquals(0, rc, err + loggedText());
  }

  // ---- diagnostics stay reachable ----------------------------------------------------------------

  @Test
  void unlicensed_help_stillPrints() {
    assertEquals(0, cli(LICENSED, null).execute("--help"));
    assertTrue(out.toString().contains("Example Pro"), out.toString());
  }

  @Test
  void unlicensed_subcommandHelp_stillPrints() {
    assertEquals(0, cli(LICENSED, null).execute("survey", "inventory", "--help"));
    assertTrue(out.toString().contains("--no-upload"), out.toString());
    assertEquals("", loggedText());
  }

  @Test
  void unlicensed_version_namesEdition_andLicenseState() {
    assertEquals(0, cli(LICENSED, null).execute("--version"));
    assertTrue(out.toString().contains("Example Pro (ex): airgapped; licensed; features:"), out.toString());
    assertTrue(out.toString().contains("license: none (The Example Pro edition requires a Spice License.)"), out.toString());
  }

  @Test
  void licensed_version_saysValidUntil() {
    assertEquals(0, cli(LICENSED, VALID_PASS).execute("--version"));
    assertTrue(out.toString().contains("license: valid until "), out.toString());
  }

  @Test
  void unlicensed_bareCommands_printUsage() {
    assertEquals(0, cli(LICENSED, null).execute());
    assertEquals(0, cli(LICENSED, null).execute("survey"));
    assertEquals(0, cli(LICENSED, null).execute("pass"));
    assertEquals("", loggedText());
  }

  @Test
  void unlicensed_passDecode_stillWorks_andReportsTheLicense() {
    // pass decode logs through SpicePassDecoder; capture that logger too.
    Logger decoder = (Logger) LoggerFactory.getLogger(SpicePassDecoder.class);
    decoder.addAppender(logged);
    try {
      assertEquals(0, cli(LICENSED, SHORT_PASS).execute("pass", "decode"));
      String text = loggedText();
      assertTrue(text.contains("Licensed Features"), text);
      assertTrue(text.contains("License: ❌"), text);
      assertTrue(text.contains("inventory-surveys"), text);
    } finally {
      decoder.detachAppender(logged);
    }
  }

  @Test
  void unlicensed_pathManifest_stillRuns() {
    // path-manifest writes to System.out directly (the wrapper reads it); the exit code and the
    // absence of a refusal are what prove it ran.
    assertEquals(0, cli(LICENSED, null).execute("path-manifest"));
    assertEquals("", loggedText());
  }

  @Test
  void unlicensed_generateCompletion_stillPrints() {
    assertEquals(0, cli(LICENSED, null).execute("generate-completion"));
    assertTrue(out.toString().contains("complete"), out.toString());
  }

  @Test
  void unlicensed_config_stillRuns() {
    assertEquals(0, cli(LICENSED, null).execute("config"));
    assertEquals("", loggedText());
  }

  /**
   * The wrapper and installers invoke some commands in a container that has no SPICE_PASS at
   * all. Each such command must be on the exempt list, or installing the CLI breaks before the
   * user ever sees a license message.
   */
  @Test
  void exemptList_coversWhatTheWrapperAndInstallersInvoke() throws Exception {
    List<String> scripts = List.of("spice", "install.sh", "install.ps1", "spice.ps1");
    for (String command : List.of("path-manifest", "generate-completion", "generate-powershell-completion")) {
      boolean invoked = false;
      for (String script : scripts) {
        Path file = Path.of(script);
        if (Files.exists(file) && Files.readString(file).contains(" " + command)) {
          invoked = true;
        }
      }
      assertTrue(invoked, command + " is no longer invoked by any wrapper; reconsider its exemption");
      assertTrue(LicenseGate.EXEMPT.contains(command), command + " must be exempt from the license gate");
    }
  }
}
