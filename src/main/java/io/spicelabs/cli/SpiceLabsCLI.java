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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
   */
  static CommandLine newCommandLine() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    cmd.setParameterExceptionHandler((ex, a) -> {
      CommandLine offending = ex.getCommandLine();
      log.error("❌ {}", ex.getMessage());
      if (ex instanceof CommandLine.MissingParameterException) {
        offending.usage(offending.getErr(), offending.getColorScheme());
      } else {
        log.info("Use --help for usage information.");
      }
      return offending.getCommandSpec().exitCodeOnInvalidInput();
    });
    return cmd;
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
