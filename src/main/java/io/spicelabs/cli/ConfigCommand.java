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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.spicelabs.config.Resolution;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Questions about the configuration itself, rather than about artifacts. */
@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = "Inspect the configuration this run would use",
    subcommands = {ConfigCommand.Explain.class})
public class ConfigCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /**
   * Print every setting a command would run with, and where each value came from.
   *
   * <p>The question this answers — "why is it doing that?" — previously had no answer at
   * all. A value could come from a shared group, a command-scoped group, an environment
   * variable or a flag, and nothing recorded which. Overrides are reported as they happen
   * during a run; this shows the whole resolved picture without running anything.
   */
  @Command(
      name = "explain",
      mixinStandardHelpOptions = true,
      description = "Show every setting and where its value came from")
  static class Explain implements Callable<Integer> {

    @Parameters(
        arity = "0..*",
        paramLabel = "COMMAND",
        description =
            "The command to explain, e.g. `survey inventory` or `registry`. "
                + "Defaults to every command's settings.")
    List<String> commandPath = new ArrayList<>();

    @Option(
        names = "--group",
        paramLabel = "GROUP",
        description = "Restrict to one configuration group, e.g. analysis")
    String group;

    @Override
    public Integer call() {
      RunConfiguration configuration = RunConfiguration.current();

      configuration
          .file()
          .ifPresentOrElse(
              path -> System.out.println("# " + path + "\n"),
              () ->
                  System.out.println(
                      "# No configuration file; showing defaults and the environment\n"));

      List<String> groups = group == null ? knownGroups() : List.of(group);
      Resolution resolved = configuration.explain(commandPath, groups);
      String explained = resolved.explain();
      System.out.print(explained.isEmpty() ? "# Nothing set\n" : explained);

      configuration.warnAboutUnclaimedGroups(knownGroups(), knownCommands());
      return 0;
    }

    /**
     * Every group any command reads.
     *
     * <p>Built-in claims are listed by the commands themselves; a plugin's claims come from
     * the plugin. Both are needed here, because explaining a configuration means explaining
     * all of it — and because a group in the file that appears in neither list is exactly
     * the typo worth reporting.
     */
    private static List<String> knownGroups() {
      List<String> groups = new ArrayList<>(SurveyInventoryCommand.GROUPS);
      for (String group : PluginLoader.claimedGroups()) {
        if (!groups.contains(group)) {
          groups.add(group);
        }
      }
      return groups;
    }

    private static List<String> knownCommands() {
      List<String> commands = new ArrayList<>(List.of("survey", "pass", "config"));
      commands.addAll(PluginLoader.mountedCommands());
      return commands;
    }
  }
}
