# Writing `spice` plugins

`spice` is extensible: top-level subcommands can be contributed by **plugins** that are
discovered at runtime, with no compile-time coupling to the CLI. A plugin is included in a
build purely by being **present on the classpath** — the public CLI ships none, and an
internal/enterprise build adds them by dropping their jars in. The `spice registry` command
(bulk registry surveying, provided by [`allspice`](https://github.com/spice-labs-inc/allspice))
is a plugin built exactly this way, and [`sample/hello-plugin`](../sample/hello-plugin) is a
minimal worked example.

## How it works

1. A plugin implements the `io.spicelabs.cli.spi.SpiceCommandPlugin` service-provider
   interface (from the public `io.spicelabs:spice-plugin-api` artifact) and registers it in
   `META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin`.
2. At startup `spice` calls `ServiceLoader` and mounts each provider's command. Discovery is
   defensive: an incompatible API version, a clash with a built-in name, or a plugin that
   throws is skipped with a warning — never a CLI-wide failure.
3. The plugin's command is an ordinary picocli `@Command`, so it fully defines its own name,
   options, parameters, nested subcommands and execution. `spice` needs no knowledge of it.

## The SPI

```java
package io.spicelabs.cli.spi;

public interface SpiceCommandPlugin {
  Object command(SpiceContext context); // any picocli @Command object / CommandLine / CommandSpec
  String id();                          // stable id, for ordering and diagnostics
  default int apiVersion() { return SpiceContext.API_VERSION; }
  default String parent() { return ""; } // parent command to mount under, or "" for top-level
  default String powershellCompletion() { return ""; } // see "Tab completion" below
}

public interface SpiceContext {
  int API_VERSION = 1;
  String version();                 // the running spice CLI version
  java.util.Optional<String> spicePass(); // resolved SPICE_PASS, for plugins that upload
}
```

`SpiceContext` gives plugins the same shared services the built-in commands use, so a plugin
behaves consistently (version reporting, `SPICE_PASS` resolution).

## Authoring a plugin

A plugin is its own self-contained build. It depends on `spice-plugin-api` and `picocli` as
**`provided`** — the CLI ships both, so they must not be re-bundled — and stages its jar(s)
into a top-level **`dist/`** directory:

- **its own jar**, plus
- **any runtime dependencies the CLI does not already provide.** Declare the libraries the CLI
  already ships — `goatrodeo`, `ginger-j`, the Scala library, SLF4J/Logback, picocli and
  `spice-plugin-api` — as **`provided`** so they are available at compile time but excluded from
  `dist/`; a single copy of each then lives on the runtime classpath. Import the shared BOM,
  `io.spicelabs:spice-bom` (`<type>pom</type>`, `<scope>import</scope>`), and declare those
  dependencies **without versions** so the whole ecosystem — the CLI and every plugin — converges
  on one governed set of versions.

See [`sample/hello-plugin/pom.xml`](../sample/hello-plugin/pom.xml) for the smallest possible
build, and `allspice`'s `spicePlugin` module for one that bundles real dependencies.

## Including a plugin in a build

Symlink the plugin's **repository root** into `spice/plugins/`; the build collects
`plugins/<name>/dist/**/*.jar`:

```bash
ln -s /path/to/your-plugin spice/plugins/your-plugin
mvn -DskipTests package          # collects plugins/*/dist/*.jar into target/plugins/
```

At runtime the launcher puts the CLI fat jar **and** `plugins/*` on the classpath
(`java -cp "spice-labs-cli.jar:plugins/*" io.spicelabs.cli.SpiceLabsCLI …`), so each plugin's
`META-INF/services` provider is discovered. The Docker image does the same: anything under
`plugins/` at build time is baked into `/opt/spice-labs-cli/plugins/`.

The symlink name is irrelevant and the contents are gitignored — a public build leaves
`plugins/` empty (no extra commands); an internal build symlinks the proprietary plugins in.

## Tab completion

- **bash/zsh** completion is generated from the live picocli model (`spice generate-completion`),
  so a plugin's commands and options are included automatically — nothing to do.
- **PowerShell** cannot be generated from the model, so a plugin contributes its own fragment
  via `SpiceCommandPlugin.powershellCompletion()`. `spice generate-powershell-completion`
  splices every plugin's fragment into the completion script. Return `""` (the default) for
  no PowerShell completion.

`install.sh` / `install.ps1` generate the appropriate script from the configured image at
install time, so completion always reflects whatever plugins that image ships.

## Quick reference

| Concern | Convention |
|---|---|
| SPI artifact | `io.spicelabs:spice-plugin-api` (`provided`) |
| Provider registration | `META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin` |
| Shared runtime libs | declare CLI-provided libs (`goatrodeo`, `ginger-j`, …) as `provided`, versions from the imported `io.spicelabs:spice-bom` — do not re-bundle |
| Plugin output | a top-level `dist/` directory of jars |
| Inclusion | `ln -s /path/to/plugin spice/plugins/<name>` → build collects `<name>/dist/*.jar` |
| Runtime | `-cp "spice-labs-cli.jar:plugins/*"` |
