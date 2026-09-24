// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors

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
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Prints the whole command model: by default every visible command's help, one after another, as a
 * manual; with {@code --json}, the same model as JSON with each argument's type, requirement and
 * default. The release attaches the JSON as {@code cli-docs.json}, and the Spice Labs MCP serves it. Built from {@link CommandSpec#root()} after {@link PluginLoader} has run,
 * so plugin commands are included, as in {@link PathManifestCommand}.
 */
@Command(
    name = "docs",
    mixinStandardHelpOptions = true,
    description = "Print the help for every command; with --json, as JSON for tools and AI agents.")
public class DocsCommand implements Callable<Integer> {

  /** Bumped when a field is renamed or removed; adding fields does not bump it. */
  static final int FORMAT_VERSION = 1;

  @Spec
  CommandSpec spec;

  @Option(names = "--json", description = "Print the commands as JSON instead of help text.")
  boolean json;

  @Override
  public Integer call() throws Exception {
    // Written straight to stdout, not through the logger: the output is read as it is.
    System.out.println(json
        ? new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(render(spec.root()))
        : manual(spec.root()));
    System.out.flush();
    return 0;
  }

  /** Each visible command's usual help text, in the order {@code --json} lists them. */
  static String manual(CommandSpec root) {
    StringBuilder out = new StringBuilder();
    collect(root, Collections.newSetFromMap(new IdentityHashMap<>())).forEach(c -> {
      if (out.length() > 0) {
        out.append("\n").append("-".repeat(72)).append("\n\n");
      }
      out.append(c.commandLine().getUsageMessage(CommandLine.Help.Ansi.OFF));
    });
    return out.toString();
  }

  private static List<CommandSpec> collect(CommandSpec spec, Set<CommandSpec> seen) {
    List<CommandSpec> out = new ArrayList<>();
    if (!seen.add(spec) || spec.usageMessage().hidden()) {
      return out;
    }
    out.add(spec);
    for (var sub : spec.subcommands().values()) {
      out.addAll(collect(sub.getCommandSpec(), seen));
    }
    return out;
  }

  static Map<String, Object> render(CommandSpec root) {
    List<Map<String, Object>> commands = new ArrayList<>();
    walk(root, root.name(), Collections.newSetFromMap(new IdentityHashMap<>()), commands);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("format", FORMAT_VERSION);
    out.put("cli", root.name());
    out.put("version", SpiceLabsCLI.VersionProvider.getVersionString());
    out.put("commands", commands);
    return out;
  }

  private static void walk(CommandSpec spec, String path, Set<CommandSpec> seen, List<Map<String, Object>> out) {
    if (!seen.add(spec) || spec.usageMessage().hidden()) {
      return;
    }
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("command", path);
    if (spec.aliases().length > 0) {
      command.put("aliases", List.of(spec.aliases()));
    }
    command.put("description", text(spec.usageMessage().description()));
    List<Map<String, Object>> arguments = new ArrayList<>();
    for (PositionalParamSpec p : spec.positionalParameters()) {
      if (!p.hidden()) {
        arguments.add(arg(p, p.paramLabel()));
      }
    }
    command.put("arguments", arguments);
    List<Map<String, Object>> options = new ArrayList<>();
    for (OptionSpec o : spec.options()) {
      if (!o.hidden()) {
        Map<String, Object> option = arg(o, o.paramLabel());
        option.put("names", List.of(o.names()));
        options.add(option);
      }
    }
    command.put("options", options);
    String footer = text(spec.usageMessage().footer());
    if (!footer.isBlank()) {
      command.put("notes", footer);
    }
    List<String> subcommands = spec.subcommands().values().stream()
        .map(c -> c.getCommandSpec())
        .filter(s -> !s.usageMessage().hidden())
        .map(CommandSpec::name)
        .distinct()
        .toList();
    if (!subcommands.isEmpty()) {
      command.put("subcommands", subcommands);
    }
    out.add(command);
    for (var sub : spec.subcommands().values()) {
      walk(sub.getCommandSpec(), path + " " + sub.getCommandSpec().name(), seen, out);
    }
  }

  private static Map<String, Object> arg(ArgSpec a, String label) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", label);
    m.put("description", text(a.description()));
    m.put("type", a.type().getSimpleName());
    m.put("required", a.required());
    if (a.defaultValue() != null) {
      m.put("default", a.defaultValueString());
    }
    return m;
  }

  /** Picocli text lines as one string, with its {@code %n} line breaks made real. */
  private static String text(String[] lines) {
    return String.join("\n", Arrays.asList(lines)).replace("%n", "\n").strip();
  }
}
