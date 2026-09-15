package io.spicelabs.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Prunes the command tree to what the edition has, before plugins are mounted, so
 * {@code --help}, completion, the path manifest and the parser all see one tree.
 *
 * <ul>
 *   <li>without {@link Edition#INVENTORY_SURVEYS}: no {@code survey inventory},
 *       no {@code survey image}</li>
 *   <li>without {@link Edition#RUNTIME_SURVEYS}: no {@code survey runtime}</li>
 *   <li>a {@code survey} left with no types is removed altogether</li>
 *   <li>airgapped: the upload options are hidden from help (they stay parseable so the
 *       commands can refuse them with a reason rather than picocli saying "unknown option")</li>
 * </ul>
 *
 * <p>The help text and footer are rewritten to name the edition and to drop examples of
 * commands that no longer exist.
 */
final class EditionGate {

  private static final List<String> UPLOAD_OPTIONS = List.of("--upload-only", "--upload-args", "--chunk-size");

  private EditionGate() {}

  static void apply(CommandLine root, Edition edition) {
    CommandSpec rootSpec = root.getCommandSpec();
    CommandLine survey = root.getSubcommands().get("survey");
    if (survey != null) {
      CommandSpec surveySpec = survey.getCommandSpec();
      if (!edition.has(Edition.INVENTORY_SURVEYS)) {
        remove(surveySpec, "inventory");
        remove(surveySpec, "image");
      }
      if (!edition.has(Edition.RUNTIME_SURVEYS)) {
        remove(surveySpec, "runtime");
      }
      if (surveySpec.subcommands().isEmpty()) {
        remove(rootSpec, "survey");
      } else if (edition.airgapped()) {
        for (CommandLine type : surveySpec.subcommands().values()) {
          hideUploadOptions(type.getCommandSpec());
        }
      }
      if (edition.branded()) {
        surveySpec.usageMessage().footer(prune(surveySpec.usageMessage().footer(), rootSpec));
      }
    }
    if (edition.branded()) {
      rootSpec.usageMessage().description("Spice Labs CLI: " + edition.displayName());
      rootSpec.usageMessage().footer(prune(rootSpec.usageMessage().footer(), rootSpec));
    }
  }

  private static void remove(CommandSpec parent, String name) {
    if (parent.subcommands().containsKey(name)) {
      parent.removeSubcommand(name);
    }
  }

  /**
   * Re-add each upload option as hidden: still parsed, so the command can refuse it with a
   * reason rather than picocli saying "unknown option", but never advertised. Help text that
   * names an option the edition does not offer is stale, so those lines go too.
   */
  private static void hideUploadOptions(CommandSpec spec) {
    List<String> hidden = new ArrayList<>();
    for (String name : UPLOAD_OPTIONS) {
      OptionSpec option = spec.findOption(name);
      if (option != null && !option.hidden()) {
        spec.remove(option);
        spec.addOption(OptionSpec.builder(option).hidden(true).build());
        hidden.add(name);
      }
    }
    if (!hidden.isEmpty()) {
      spec.usageMessage().description(withoutLinesNaming(spec.usageMessage().description(), hidden));
      spec.usageMessage().footer(withoutLinesNaming(spec.usageMessage().footer(), hidden));
    }
  }

  private static String[] withoutLinesNaming(String[] lines, List<String> names) {
    List<String> kept = new ArrayList<>();
    for (String line : lines) {
      if (names.stream().noneMatch(line::contains)) {
        kept.add(line);
      }
    }
    return kept.toArray(new String[0]);
  }

  /**
   * Drop footer example lines that mention a command path this tree no longer has, e.g.
   * {@code spice survey runtime …} once runtime surveys are gone.
   */
  private static String[] prune(String[] footer, CommandSpec rootSpec) {
    List<String> kept = new ArrayList<>();
    for (String line : footer) {
      if (!mentionsMissingCommand(line.trim(), rootSpec)) {
        kept.add(line);
      }
    }
    return kept.toArray(new String[0]);
  }

  private static boolean mentionsMissingCommand(String line, CommandSpec rootSpec) {
    if (!line.startsWith("spice ")) {
      return false;
    }
    String[] words = line.split("\\s+");
    Map<String, CommandLine> level = rootSpec.subcommands();
    for (int i = 1; i < words.length; i++) {
      String word = words[i];
      if (word.startsWith("-")) {
        return false;
      }
      CommandLine next = level.get(word);
      if (next == null) {
        // An unknown word at the top level is a command the tree lacks (e.g. "survey"
        // after removal); below that it is a positional argument, which proves nothing.
        return i == 1 || !level.isEmpty();
      }
      level = next.getCommandSpec().subcommands();
    }
    return false;
  }
}
