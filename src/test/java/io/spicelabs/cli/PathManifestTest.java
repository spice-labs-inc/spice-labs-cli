package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The manifest is what the host wrapper substitutes for knowing any flag names, so these
 * tests pin the two things it must get right: which arguments are paths (derived from the
 * declared type alone) and whether a missing one names a file or a directory.
 */
class PathManifestTest {

  // ── Type derivation ────────────────────────────────────────────────────────

  @Command(name = "types")
  static class TypesCommand implements Callable<Integer> {
    @Option(names = "--a-path") Path aPath;
    @Option(names = "--a-file") File aFile;
    @Option(names = "--path-array") Path[] pathArray;
    @Option(names = "--path-list") List<Path> pathList;
    @Option(names = "--a-string") String aString;
    @Option(names = "--a-number") Integer aNumber;
    @Option(names = "--a-flag") boolean aFlag;
    @Option(names = "--string-list") List<String> stringList;
    public Integer call() { return 0; }
  }

  @Test
  void pathAndFileTypesAreMounted_othersAreNot() {
    String manifest = PathManifest.render(new CommandLine(new TypesCommand()), false);

    assertTrue(attrs(manifest, "types", "--a-path").contains("path"));
    assertTrue(attrs(manifest, "types", "--a-file").contains("path"));
    assertTrue(attrs(manifest, "types", "--path-array").contains("path"),
        "an array of paths still names paths");
    assertTrue(attrs(manifest, "types", "--path-list").contains("path"),
        "a collection of paths still names paths");

    assertFalse(attrs(manifest, "types", "--a-string").contains("path"));
    assertFalse(attrs(manifest, "types", "--a-number").contains("path"));
    assertFalse(attrs(manifest, "types", "--a-flag").contains("path"));
    assertFalse(attrs(manifest, "types", "--string-list").contains("path"));
  }

  @Test
  void valueVersusFlagIsRecorded() {
    String manifest = PathManifest.render(new CommandLine(new TypesCommand()), false);
    assertTrue(attrs(manifest, "types", "--a-string").contains("value"));
    assertTrue(attrs(manifest, "types", "--a-flag").contains("flag"));
    assertFalse(attrs(manifest, "types", "--a-flag").contains("value"));
  }

  // ── create=self vs create=parent ───────────────────────────────────────────

  @Command(name = "hints")
  static class HintsCommand implements Callable<Integer> {
    @Option(names = "--labelled-dir", paramLabel = "DIR") Path labelledDir;
    @Option(names = "--labelled-file", paramLabel = "FILE") Path labelledFile;
    @Option(names = "--described-dir", description = "Output directory for results") Path describedDir;
    @Option(names = "--described-file", description = "Path to the log file") Path describedFile;
    @Option(names = "--unsaid") Path unsaid;
    public Integer call() { return 0; }
  }

  @Test
  void createHintPrefersParamLabelThenDescription() {
    String manifest = PathManifest.render(new CommandLine(new HintsCommand()), false);

    assertTrue(attrs(manifest, "hints", "--labelled-dir").contains("create=self"));
    assertTrue(attrs(manifest, "hints", "--labelled-file").contains("create=parent"));
    assertTrue(attrs(manifest, "hints", "--described-dir").contains("create=self"));
    assertTrue(attrs(manifest, "hints", "--described-file").contains("create=parent"));
    // Nothing says which it is. `parent` is the safe answer: it lets the container
    // create either a file or a directory, and never makes a directory named like a file.
    assertTrue(attrs(manifest, "hints", "--unsaid").contains("create=parent"));
  }

  @Test
  void paramLabelBracketsAreIgnored() {
    CommandLine cmd = new CommandLine(new HintsCommand());
    ArgSpec arg = cmd.getCommandSpec().findOption("--labelled-dir");
    assertEquals("self", PathManifest.createHint(arg));
  }

  // ── Built-in commands ──────────────────────────────────────────────────────

  @Test
  void logFileIsHostOnlyAndNeverMounted() {
    String manifest = PathManifest.render(SpiceLabsCLI.newCommandLine(), false);
    String logFile = attrs(manifest, "spice/survey/inventory", "--log-file");
    assertTrue(logFile.contains("hostonly"),
        "--log-file is written on the host, so the wrapper strips it");
    assertFalse(logFile.contains("path"),
        "a host-only argument must never be bind-mounted");
  }

  @Test
  void surveyInventoryPositionalsAreSubjectThenInputPath() {
    String manifest = PathManifest.render(SpiceLabsCLI.newCommandLine(), false);
    assertFalse(positional(manifest, "spice/survey/inventory", 0).contains("path"),
        "the subject is a label, not a path");
    String input = positional(manifest, "spice/survey/inventory", 1);
    assertTrue(input.contains("path"));
    assertTrue(input.contains("exists"),
        "a positional path is an input, so a missing one is an error rather than a mkdir");
  }

  @Test
  void everyCommandInTheTreeIsDeclared() {
    String manifest = PathManifest.render(SpiceLabsCLI.newCommandLine(), false);
    assertTrue(manifest.contains("\nC spice\n"));
    assertTrue(manifest.contains("\nC spice/survey/inventory\n"));
    assertTrue(manifest.contains("\nC spice/pass/decode\n"));
    assertTrue(manifest.contains("\nC spice/path-manifest\n"));
  }

  // ── Plugin-contributed options (the --rogues regression) ───────────────────

  @Command(name = "widget", subcommands = WidgetReportCommand.class)
  static class WidgetCommand implements Callable<Integer> {
    public Integer call() { return 0; }
  }

  @Command(name = "report")
  static class WidgetReportCommand implements Callable<Integer> {
    @Option(names = "--config", required = true, paramLabel = "FILE") Path config;
    @Option(names = "--rogues", paramLabel = "FILE") Path rogues;
    @Option(names = "--out", paramLabel = "DIR") Path out;
    @Option(names = "--json") boolean json;
    @Parameters(index = "0") String subject;
    public Integer call() { return 0; }
  }

  /**
   * A plugin's path options must appear without the plugin declaring anything beyond the
   * field's type. This is the defect that motivated the manifest: {@code --rogues} was a
   * {@code Path} on a plugin subcommand, but absent from every wrapper's hardcoded list,
   * so its value reached the container unmounted.
   */
  @Test
  void pluginContributedPathOptionsAreDerivedFromTheirType() {
    CommandLine root = SpiceLabsCLI.newCommandLine();
    root.addSubcommand("widget", new CommandLine(new WidgetCommand()));

    String manifest = PathManifest.render(root, false);

    assertTrue(manifest.contains("\nC spice/widget/report\n"),
        "a plugin's subcommands are part of the command tree");
    String rogues = attrs(manifest, "spice/widget/report", "--rogues");
    assertTrue(rogues.contains("path"), "--rogues is a Path, so it is mounted");
    assertTrue(rogues.contains("create=parent"), "paramLabel=FILE means create the parent");
    assertFalse(rogues.contains("exists"), "an optional output-ish path may be created");

    // `required` says the flag must be given, not that its target already exists —
    // `registry init --file` names a file it is about to create. Enforcing existence in
    // the wrapper would break that, and pre-empt the CLI's diagnostic for the rest.
    assertFalse(attrs(manifest, "spice/widget/report", "--config").contains("exists"),
        "a required path option is still the CLI's to validate, not the wrapper's");
    assertTrue(attrs(manifest, "spice/widget/report", "--out").contains("create=self"),
        "paramLabel=DIR means create the directory itself");
    assertFalse(attrs(manifest, "spice/widget/report", "--json").contains("path"));
  }

  // ── Reserved directories ───────────────────────────────────────────────────

  @Test
  void filesystemRootsAreReservedExactly() {
    String manifest = PathManifest.render(SpiceLabsCLI.newCommandLine(), false);
    assertTrue(manifest.contains("\nR /opt\n"));
    assertTrue(manifest.contains("\nR /usr\n"));
    assertTrue(manifest.contains("\nR /var\n"));
    // /var is reserved but /var/folders (macOS temp dirs) must stay mountable, which is
    // why R records are matched exactly and only RP records match by prefix.
    assertFalse(manifest.contains("\nR /tmp\n"));
    assertFalse(manifest.contains("\nR /home\n"));
  }

  @Test
  void installDirsAreDerivedAndOmittedFromBuildTimeOutput() {
    assertFalse(PathManifest.render(SpiceLabsCLI.newCommandLine(), false).contains("\nRP "),
        "build-time output is committed into the wrapper, so it must carry no local path");
    assertFalse(PathManifest.installDirs().isEmpty(),
        "the CLI's own code source is always somewhere");
    assertTrue(PathManifest.render(SpiceLabsCLI.newCommandLine(), true).contains("\nRP "));
  }

  // ── Format ─────────────────────────────────────────────────────────────────

  @Test
  void everyRecordHasAStableTokenCount() {
    String manifest = PathManifest.render(SpiceLabsCLI.newCommandLine(), true);
    assertTrue(manifest.startsWith(PathManifest.HEADER + "\n"));
    for (String line : manifest.split("\n")) {
      if (line.startsWith("#")) continue;
      assertFalse(line.isBlank(), "blank records would confuse the wrapper's parser");
      String[] fields = line.split(" ");
      assertFalse(fields[0].isEmpty());
      switch (fields[0]) {
        case "V", "G", "R", "RP", "C" -> assertEquals(2, fields.length,
            "single-value record must not contain whitespace: " + line);
        case "O", "P" -> assertTrue(fields.length >= 4, "under-specified record: " + line);
        default -> fail("unknown record kind in: " + line);
      }
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** The attribute text of one option record, or "" if the option is absent. */
  private static String attrs(String manifest, String cmdpath, String name) {
    return record(manifest, "O " + cmdpath + " " + name);
  }

  private static String positional(String manifest, String cmdpath, int index) {
    return record(manifest, "P " + cmdpath + " " + index);
  }

  private static String record(String manifest, String prefix) {
    for (String line : manifest.split("\n")) {
      if (line.equals(prefix) || line.startsWith(prefix + " ")) {
        return line.substring(prefix.length());
      }
    }
    return "";
  }
}
