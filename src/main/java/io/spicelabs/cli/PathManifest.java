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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.spicelabs.cli.spi.SpiceCommandPlugin;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Renders the <em>path manifest</em>: a description of which arguments of which commands
 * are host filesystem paths, for the host-side {@code spice} / {@code spice.ps1} wrappers.
 *
 * <p>The wrappers run <em>outside</em> the container and must decide, before {@code docker
 * run} starts, which arguments to absolutise and bind-mount. They cannot see the picocli
 * model, which lives inside the image — so historically each wrapper carried a hardcoded
 * list of path flags, which drifted from the commands (notably plugin-contributed ones).
 *
 * <p>Instead, this class walks the <em>fully assembled</em> command tree — after
 * {@link PluginLoader} has mounted every plugin — and derives the list. A plugin declares
 * a parameter as a path simply by typing it as {@link Path} or {@link File}; no SPI method
 * and no {@code SpiceContext#API_VERSION} bump is involved, so already-published plugin
 * jars are covered without change.
 *
 * <p>The format is line-oriented and whitespace-separated so that bash 3.2 (macOS) and
 * PowerShell 5.1 can both parse it without {@code jq}. See {@code shared/path-manifest.bash}
 * for the consumer.
 *
 * <pre>
 * # spice-path-manifest 1
 * V 1
 * G 1.4.2
 * R /opt/spice-labs-cli
 * C spice/registry/cbom
 * O spice/registry/cbom --rogues value path create=parent
 * P spice/survey/inventory 1 value path create=self exists
 * </pre>
 */
public final class PathManifest {

  /** Manifest schema version. Bump only on an incompatible format change. */
  public static final int SCHEMA_VERSION = 1;

  /** First line of every manifest; the wrappers validate it before trusting the rest. */
  public static final String HEADER = "# spice-path-manifest " + SCHEMA_VERSION;

  /**
   * Options the wrapper consumes itself and strips from the container command line.
   * Each is handled at the shell level (log files are written on the host; feature flags
   * select the image), so they must not be mounted even when they name a path.
   */
  private static final Set<String> HOST_ONLY = Set.of("--log-file", "--features");

  /** {@code paramLabel} values that mean "this names a directory". */
  private static final Set<String> DIR_LABELS = Set.of("DIR", "DIRECTORY", "FOLDER", "OUTDIR");

  /** {@code paramLabel} values that mean "this names a file". */
  private static final Set<String> FILE_LABELS = Set.of("FILE", "PATH");

  private static final Pattern DIRECTORY_WORD = Pattern.compile("\\bdirector(y|ies)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_WORD = Pattern.compile("\\bfile\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Filesystem roots that must never be shadowed by a bind mount. Matched <em>exactly</em>,
   * never by prefix: {@code /var/folders/...} (macOS temp dirs) and {@code /home/...} must
   * stay mountable, so prefix matching is reserved for the derived entries in
   * {@link #reservedDirs()}.
   */
  private static final List<String> FHS_ROOTS = List.of(
      "/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32",
      "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var");

  private PathManifest() {}

  /** Render the manifest for a fully-assembled command tree. */
  public static String render(CommandLine root) {
    return render(root, true);
  }

  /**
   * Render the manifest.
   *
   * @param deriveInstallDirs whether to emit the {@code RP} records naming this
   *     installation's own directories. True when rendering inside the image, where those
   *     directories are what they will be at runtime; false when rendering at build time,
   *     where they would be the developer's checkout — a machine-specific value that must
   *     not end up in the manifest embedded in the wrapper.
   */
  static String render(CommandLine root, boolean deriveInstallDirs) {
    StringBuilder out = new StringBuilder();
    out.append(HEADER).append('\n');
    out.append("V ").append(SCHEMA_VERSION).append('\n');
    out.append("G ").append(sanitize(SpiceLabsCLI.VersionProvider.getVersionString())).append('\n');
    for (String dir : FHS_ROOTS) {
      out.append("R ").append(dir).append('\n');
    }
    if (deriveInstallDirs) {
      for (String dir : installDirs()) {
        out.append("RP ").append(dir).append('\n');
      }
    }
    walk(root.getCommandSpec(), root.getCommandName(),
        Collections.newSetFromMap(new IdentityHashMap<>()), out);
    return out.toString();
  }

  /**
   * Emit records for {@code spec} and recurse into its subcommands. {@code seen} guards
   * against a command reachable by more than one alias, and against any cycle.
   */
  static void walk(CommandSpec spec, String path, Set<CommandSpec> seen, StringBuilder out) {
    if (!seen.add(spec)) {
      return;
    }
    out.append("C ").append(path).append('\n');

    for (OptionSpec option : spec.options()) {
      String attrs = attributesOf(option, option.arity().max() > 0, false);
      for (String name : option.names()) {
        out.append("O ").append(path).append(' ').append(name).append(attrs).append('\n');
      }
    }
    for (PositionalParamSpec param : spec.positionalParameters()) {
      out.append("P ").append(path).append(' ').append(param.index().min())
         .append(attributesOf(param, true, true)).append('\n');
    }

    for (CommandLine sub : spec.subcommands().values()) {
      walk(sub.getCommandSpec(), path + "/" + sub.getCommandName(), seen, out);
    }
  }

  /**
   * The trailing attribute list for one argument, each attribute space-prefixed.
   *
   * @param takesValue whether the wrapper must consume a following token
   * @param positional whether this is a positional parameter. Only positionals are marked
   *     {@code exists}: they are inputs by convention throughout this CLI, and the wrapper
   *     already refused a missing one before manifests existed. A path <em>option</em> is
   *     never marked, even when {@code required()} — required says the flag must be given,
   *     not that its target already exists ({@code registry init --file} names a file it
   *     is about to create). Rejecting those in the wrapper would both break that case and
   *     pre-empt the CLI's own diagnostic for the ones that genuinely are inputs.
   */
  private static String attributesOf(ArgSpec arg, boolean takesValue, boolean positional) {
    StringBuilder sb = new StringBuilder();
    sb.append(takesValue ? " value" : " flag");

    boolean hostOnly = arg instanceof OptionSpec
        && anyMatch(((OptionSpec) arg).names(), HOST_ONLY);
    if (hostOnly) {
      // Stripped by the wrapper, so never mounted — even though --log-file names a path.
      return sb.append(" hostonly").toString();
    }

    if (isPathType(arg)) {
      sb.append(" path");
      sb.append(" create=").append(createHint(arg));
      if (positional) {
        sb.append(" exists");
      }
    }
    if (arg.scopeType() == CommandLine.ScopeType.INHERIT) {
      sb.append(" inherit");
    }
    return sb.toString();
  }

  /**
   * Whether an argument names a host filesystem path, derived purely from its declared
   * type. This is the entire mechanism by which a plugin declares a mounted parameter:
   * type it as {@link Path} or {@link File} (or an array/collection of either).
   */
  static boolean isPathType(ArgSpec arg) {
    Class<?> type = arg.type();
    if (type.isArray()) {
      type = type.getComponentType();
    } else if (Collection.class.isAssignableFrom(type)) {
      Class<?>[] aux = arg.auxiliaryTypes();
      if (aux.length > 0) {
        type = aux[0];
      }
    }
    return Path.class.isAssignableFrom(type) || File.class.isAssignableFrom(type);
  }

  /**
   * Whether a missing path should be created as a directory itself ({@code self}) or have
   * its parent directory created ({@code parent}).
   *
   * <p>Nothing in the type says whether a {@code Path} names a file or a directory, so this
   * reads the {@code paramLabel} first (the convention plugins should follow:
   * {@code paramLabel = "DIR"} or {@code "FILE"}), then falls back to the description text.
   * When neither says, {@code parent} is chosen: mounting the parent lets the container
   * create either a file or a directory there, so the worst case is that the command must
   * {@code mkdir} its own output directory — never a directory created where a file belongs.
   */
  static String createHint(ArgSpec arg) {
    String label = arg.paramLabel();
    if (label != null) {
      String bare = label.replaceAll("[<>\\[\\]]", "").toUpperCase(Locale.ROOT);
      if (DIR_LABELS.contains(bare)) {
        return "self";
      }
      if (FILE_LABELS.contains(bare)) {
        return "parent";
      }
    }
    String description = String.join(" ", arg.description());
    if (DIRECTORY_WORD.matcher(description).find()) {
      return "self";
    }
    if (FILE_WORD.matcher(description).find()) {
      return "parent";
    }
    return "parent";
  }

  /**
   * Install directories that a bind mount must never shadow, matched by <em>prefix</em>.
   * Derived rather than listed, so it stays correct as plugins come and go: mounting a
   * host directory over {@code /opt/allspice} would hide the plugin's own resources from
   * the code that needs them.
   */
  static List<String> installDirs() {
    Set<String> dirs = new TreeSet<>();
    addCodeSourceDir(SpiceLabsCLI.class, dirs);
    try {
      for (SpiceCommandPlugin plugin : ServiceLoader.load(SpiceCommandPlugin.class)) {
        addCodeSourceDir(plugin.getClass(), dirs);
      }
    } catch (Throwable ignored) {
      // A plugin that cannot even be instantiated contributes no directory to protect.
    }
    return new ArrayList<>(dirs);
  }

  private static void addCodeSourceDir(Class<?> type, Set<String> dirs) {
    try {
      var source = type.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return;
      }
      Path location = Paths.get(source.getLocation().toURI());
      Path dir = Files.isDirectory(location) ? location : location.getParent();
      if (dir != null && dir.isAbsolute() && dir.getNameCount() > 0) {
        dirs.add(dir.toString());
      }
    } catch (Throwable ignored) {
      // Unusual class loaders (JPMS images, custom loaders) simply contribute nothing.
    }
  }

  /**
   * The built-in command tree with no plugins mounted, so the manifest rendered from it
   * depends on nothing but this source. {@link SpiceLabsCLI#newCommandLine()} deliberately
   * does load plugins, which is right at runtime and wrong for a committed artefact.
   */
  static CommandLine builtInCommandLine() {
    return new CommandLine(new SpiceLabsCLI());
  }

  /** Collapse whitespace so a value can never break the record's token count. */
  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.trim().replaceAll("\\s+", "_");
  }

  private static boolean anyMatch(String[] names, Set<String> set) {
    for (String name : names) {
      if (set.contains(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Build-time entry point: render the manifest for the command tree on the current
   * classpath and write it to {@code args[0]}, or to stdout if no path is given.
   *
   * <p>This output is committed into the wrapper scripts, so it must be identical on every
   * machine. Hence two deliberate omissions: install directories (they would be the
   * developer's checkout), and plugins (whichever happen to be on the build classpath).
   * Both are carried by the manifest the wrapper fetches from the image at runtime; this
   * one only has to describe the built-in commands well enough to be a safe fallback.
   */
  public static void main(String[] args) throws IOException {
    String manifest = render(builtInCommandLine(), false);
    if (args.length > 0) {
      Files.writeString(Paths.get(args[0]), manifest, StandardCharsets.UTF_8);
    } else {
      System.out.print(manifest);
    }
  }
}
