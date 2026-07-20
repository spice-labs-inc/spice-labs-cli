// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.cli;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Spice Labs CLI v2 — top-level entry point.
 *
 * Usage:
 *   spice survey inventory &lt;subject&gt; &lt;input&gt; [options]
 *   spice pass decode
 */
@Command(
    name = "spice",
    mixinStandardHelpOptions = true,
    description = "Spice Labs CLI",
    versionProvider = SpiceLabsCLI.VersionProvider.class,
    subcommands = {
        SurveyCommand.class,
        PassCommand.class,
        // Emits a bash/zsh completion script generated from the live command model —
        // including any plugin subcommands mounted via ServiceLoader (e.g. `registry`).
        AutoComplete.GenerateCompletion.class,
        // PowerShell equivalent: built-ins + each plugin's contributed fragment.
        GeneratePowershellCompletion.class,
    },
    footer = {
        "",
        "Examples:",
        "  spice survey inventory my-app ./build/libs",
        "  spice survey inventory my-app ./app.jar --no-upload --output ./out",
        "  spice survey runtime my-app --jfr -- java -jar app.jar",
        "  spice pass decode",
        "",
        "Run 'spice <command> --help' for details on each subcommand.",
        ""
    }
)
public class SpiceLabsCLI implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(SpiceLabsCLI.class);

  public static void main(String[] args) {
    // Force US locale so the CLI's output (number/size formatting, etc.) is
    // deterministic regardless of the host's default locale.
    Locale.setDefault(Locale.US);
    int exitCode;
    try {
      exitCode = newCommandLine().execute(args);
    } catch (Exception e) {
      log.error("Fatal error: {}", e.getMessage(), e);
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  /**
   * Build a CommandLine with the CLI's standard parameter exception handler.
   * Used by main() and tests so both exercise identical error behavior.
   *
   * Plugin subcommands (e.g. `registry` from allspice) are discovered via
   * ServiceLoader and mounted dynamically — see PluginLoader.
   */
  static CommandLine newCommandLine() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    cmd.setParameterExceptionHandler((ex, a) -> {
      CommandLine offending = ex.getCommandLine();
      // When a survey type (e.g. `static`) is not registered, picocli reports it
      // as "Unmatched arguments". Give a clearer message instead.
      if (ex instanceof CommandLine.UnmatchedArgumentException) {
        String[] unmatched = ((CommandLine.UnmatchedArgumentException) ex)
            .getUnmatched().toArray(new String[0]);
        if (unmatched.length > 0) {
          String first = unmatched[0];
          // Case 1: `spice static ...` (without `survey`) — the user forgot the
          // `survey` parent or used a type that requires enterprise/federal.
          if (isTopLevel(cmd, offending) && isKnownSurveyType(first)) {
            System.err.println("❌ Unknown command: " + first);
            System.err.println("   Run 'spice survey --help' for available types.");
            return offending.getCommandSpec().exitCodeOnInvalidInput();
          }
          // Case 2: `spice survey static ...` — the survey type is not registered
          // in this image.
          if ("survey".equals(offending.getCommandName()) && isKnownSurveyType(first)) {
            System.err.println("❌ Unknown survey type: " + first);
            System.err.println("   Available types: "
                + String.join(", ", offending.getSubcommands().keySet()));
            System.err.println("   Run 'spice survey --help' for details.");
            return offending.getCommandSpec().exitCodeOnInvalidInput();
          }
        }
      }
      log.error("❌ {}", PathTranslator.translate(ex.getMessage()));
      if (ex instanceof CommandLine.MissingParameterException) {
        offending.usage(offending.getErr(), offending.getColorScheme());
      } else {
        log.info("Use --help for usage information.");
      }
      return offending.getCommandSpec().exitCodeOnInvalidInput();
    });
    // Discover and mount any subcommand plugins present on the classpath (e.g. the
    // proprietary `registry` plugin). Built-in commands are unaffected when none exist.
    PluginLoader.registerPlugins(cmd, DefaultSpiceContext.create());
    // Hide the picocli-provided `generate-completion` from --help (it stays invokable —
    // install.sh calls it). The PowerShell generator is already hidden via its annotation.
    CommandLine genCompletion = cmd.getSubcommands().get("generate-completion");
    if (genCompletion != null) {
      genCompletion.getCommandSpec().usageMessage().hidden(true);
    }
    return cmd;
  }

  /** True if {@code offending} is the top-level command (not a subcommand). */
  private static boolean isTopLevel(CommandLine root, CommandLine offending) {
    return root == offending;
  }

  /** Known survey type names, including those only available in enterprise/federal. */
  private static final java.util.Set<String> KNOWN_SURVEY_TYPES =
      java.util.Set.of("inventory", "runtime", "static");

  private static boolean isKnownSurveyType(String arg) {
    return KNOWN_SURVEY_TYPES.contains(arg);
  }

  @Override
  public void run() {
    // No subcommand given — print help
    new CommandLine(this).usage(System.out);
  }

  /**
   * Provides the CLI version from pom.properties and git.properties.
   */
  public static class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
      return new String[] { getVersionString(), getGitCommit() };
    }

    private static String getGitCommit() {
      try {
        java.util.Properties properties = new java.util.Properties();
        properties.load(VersionProvider.class.getClassLoader().getResourceAsStream("git.properties"));
        return "https://github.com/spice-labs-inc/spice-labs-cli git commit: " +
            String.valueOf(properties.get("git.commit.id.full"));
      } catch (Exception ignored) {}
      return "unknown-git-version";
    }

    static String getVersionString() {
      try (var is = SpiceLabsCLI.class.getResourceAsStream(
              "/META-INF/maven/io.spicelabs/spice-labs-cli/pom.properties")) {
        if (is != null) {
          java.util.Properties props = new java.util.Properties();
          props.load(is);
          return props.getProperty("version", "unknown");
        }
      } catch (Exception ignored) {}
      return "unknown";
    }
  }
}
