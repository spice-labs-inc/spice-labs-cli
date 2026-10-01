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

import java.io.Console;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The documentation a build carries. Every build documents its commands: {@code --commands}
 * prints every visible command's help, one after another, as a manual; {@code --json} prints
 * the same model as JSON with each argument's type, requirement and default. The release
 * attaches the JSON as {@code cli-docs.json}, and the Spice Labs MCP serves it. Both are built
 * from {@link CommandSpec#root()} after {@link PluginLoader} has run, so plugin commands are
 * included, as in {@link PathManifestCommand}.
 *
 * <p>A build may also carry a user guide ({@link Guide}). Then {@code spice docs} shows it, and
 * {@code spice docs <page>} one page of it: in a browser when one can be opened from here and
 * the output is a terminal ({@link HostBrowser}), as Markdown otherwise, so
 * {@code spice docs | less} prints. {@code --browser}, {@code --markdown} and {@code --html}
 * choose the form. Without a guide, {@code spice docs} prints the manual, as it always has.
 */
@Command(
    name = "docs",
    mixinStandardHelpOptions = true,
    description = {
        "Show the user guide, or print the help for every command when this build has no guide.",
        "--commands prints the help for every command; --json, the same as JSON for tools and AI agents."})
public class DocsCommand implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(DocsCommand.class);

  /** Bumped when a field is renamed or removed; adding fields does not bump it. */
  static final int FORMAT_VERSION = 1;

  @Spec
  CommandSpec spec;

  @Parameters(arity = "0..1", paramLabel = "<page>", completionCandidates = PageIds.class,
      description = "A page of the user guide, when this build has one. Without one, the whole guide.")
  String page;

  @Option(names = "--json", description = "Print the commands as JSON instead of help text.")
  boolean json;

  @Option(names = "--commands", description = "Print the help for every command, even when this build has a guide.")
  boolean commands;

  @Option(names = "--browser", description = "Open the guide in a browser; fail if none can be opened.")
  boolean browser;

  @Option(names = "--markdown", description = "Print the guide as Markdown.")
  boolean markdown;

  @Option(names = "--html", description = "Print the guide as one self-contained HTML page, opening at <page> when one is given.")
  boolean html;

  /** The guide's page ids, for tab completion: picocli asks when the script is generated. */
  public static final class PageIds implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
      return Guide.pages().stream().map(Guide.Page::id).iterator();
    }
  }

  @Override
  public Integer call() throws Exception {
    if (Stream.of(json, commands, browser, markdown, html).filter(b -> b).count() > 1) {
      throw new ParameterException(spec.commandLine(),
          "Choose one of --json, --commands, --browser, --markdown and --html.");
    }
    if (json || commands) {
      if (page != null) {
        throw new ParameterException(spec.commandLine(),
            (json ? "--json" : "--commands") + " documents every command, so it takes no page.");
      }
      return printModel();
    }
    List<Guide.Page> pages = Guide.pages();
    if (pages.isEmpty()) {
      if (page == null && !browser && !markdown && !html) {
        return printModel();
      }
      log.error("❌ This build of spice carries no user guide. `spice docs` prints the help for every command.");
      return 1;
    }
    if (page != null && pages.stream().noneMatch(p -> p.id().equals(page))) {
      throw new ParameterException(spec.commandLine(), "There is no page '" + page + "'. The pages are: "
          + pages.stream().map(Guide.Page::id).collect(Collectors.joining(", ")) + ".");
    }
    if (markdown) {
      return print(Guide.markdown(page));
    }
    if (html) {
      return print(Guide.html(page));
    }
    HostBrowser.Detection detection = HostBrowser.find();
    if (browser) {
      if (detection instanceof HostBrowser.Unavailable none) {
        log.error("❌ Cannot open a browser: {}.", none.reason());
        return 1;
      }
      return openIn((HostBrowser.Found) detection) ? 0 : 1;
    }
    if (detection instanceof HostBrowser.Found found && stdoutIsTerminal()) {
      if (openIn(found)) {
        return 0;
      }
      log.warn("Printing the guide as Markdown instead.");
    }
    return print(Guide.markdown(page));
  }

  /** The manual, or with --json the model: what {@code spice docs} printed before guides. */
  private int printModel() throws Exception {
    // Written straight to stdout, not through the logger: the output is read as it is.
    System.out.println(json
        ? new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(render(spec.root()))
        : manual(spec.root()));
    System.out.flush();
    return 0;
  }

  private boolean openIn(HostBrowser.Found found) throws IOException {
    Optional<byte[]> page = Guide.html(this.page);
    if (page.isEmpty()) {
      log.error("❌ This build of spice carries no HTML guide.");
      return false;
    }
    Path file = Files.createTempDirectory("spice-docs").resolve("guide.html");
    Files.write(file, page.get());
    if (HostBrowser.open(found, file)) {
      System.out.println("Opened the guide in your browser: " + file);
      return true;
    }
    log.error("❌ `{}` could not open {}.", found.name(), file);
    return false;
  }

  private static int print(Optional<byte[]> content) {
    if (content.isEmpty()) {
      log.error("❌ This build of spice carries no user guide.");
      return 1;
    }
    System.out.write(content.get(), 0, content.get().length);
    System.out.flush();
    return 0;
  }

  /**
   * Whether standard output is a terminal the reader is looking at. {@code System.console()}
   * says so on Java 21; from Java 22 it returns a console even when output is redirected, and
   * {@code Console.isTerminal()} (absent on 21, hence reflection) is the answer.
   */
  static boolean stdoutIsTerminal() {
    Console console = System.console();
    if (console == null) {
      return false;
    }
    try {
      Method isTerminal = Console.class.getMethod("isTerminal");
      return (Boolean) isTerminal.invoke(console);
    } catch (NoSuchMethodException e) {
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
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
