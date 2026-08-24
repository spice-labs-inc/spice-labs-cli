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
  default java.util.List<String> configurationGroups() { return List.of(); } // see below
  default String powershellCompletion() { return ""; } // see "Tab completion" below
}

public interface SpiceContext {
  int API_VERSION = 5;
  String version();                            // the running spice CLI version
  java.util.Optional<String> spicePass();      // resolved SPICE_PASS, for plugins that upload
  default SpicePassClaims passClaims();        // that pass, decoded once (see below)
  default java.util.Map<String, Object> configuration();
}
```

`SpiceContext` gives plugins the same shared services the built-in commands use, so a plugin
behaves consistently (version reporting, `SPICE_PASS` resolution, configuration).

`spice` mounts a plugin only when its `apiVersion()` **equals** `API_VERSION` exactly. A
mismatch is a plugin that does not appear, logged as such — not a build failure — so rebuild
plugins against the `spice-plugin-api` the CLI ships.

## Reading configuration

`context.configuration()` hands over the config-file groups this plugin claimed through
`configurationGroups()`, already resolved. Defaults, the shared `[group]`, the command-scoped
`[command.group]`, environment variables and flags have all been applied by the time a plugin
sees them, so it reads one settled value per key instead of re-implementing precedence — and
gets the same answer the built-in commands get from the same file.

Values arrive as plain nested `java.*` maps, so the SPI stays dependency-free and a plugin
needs no TOML parser of its own.

Claim the groups you read. A group that no plugin and no built-in command claims is reported
as a probable typo, which only works if claims are honest — an unclaimed group is
indistinguishable from a misspelt one.

See [configuration.md](configuration.md) for the layering rules, and `spice config explain`
for the resolved values with an origin per key.

## Reading the Spice Pass

`context.passClaims()` hands over the claims of the pass in force. The CLI decodes it once at
startup and shares that one value with every plugin and every built-in command, so nothing can
disagree about what the pass says. Registered JWT claims (`iss`, `sub`, `exp`, …) come out
through typed accessors; everything Spice-specific lands in `additionalClaims()` verbatim as
plain `java.*` values, with integral numbers normalised to `Long`.

Decoding the pass yourself works but re-derives what you already have, and claims never arrive
as system properties: a `-D` property can be set on the command line, which would let a caller
widen a scope the pass had deliberately narrowed.

### The `x-cutoff` claim

A pass may carry an **artifact cutoff** — artifacts published after that instant are out of
scope for the whole run, along with anything that transitively contains them. It already
constrains the built-in inventory analysis; a plugin that analyses or discovers artifacts is
expected to honour it too, so that one pass scopes the run consistently.

The SPI hands over the raw claim rather than an interpretation, so read it like this:

```java
Object value = context.passClaims().additionalClaims().get("x-cutoff");
Optional<Instant> cutoff = (value instanceof Long seconds)
    ? Optional.of(Instant.ofEpochSecond(seconds))
    : Optional.empty();
```

Three rules matter, because each way of getting them wrong yields a run that silently covers
almost nothing while exiting successfully:

- **Epoch seconds, not milliseconds.** Read as millis, a 2026 cutoff lands in January 1970 and
  excludes the entire estate.
- **An absent claim means no cutoff**, never `Instant.EPOCH`. Defaulting to the epoch turns
  "this pass does not narrow scope" into "exclude everything".
- **A non-numeric value is ignored** — warn and carry on with no cutoff. A malformed claim must
  not be read as a bound of zero.

`PassClaims.cutoff()` in the CLI is the reference implementation; keep the two in step.

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
