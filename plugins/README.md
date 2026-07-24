# plugins/

Build-time plugin inclusion for the `spice` CLI.

`spice` discovers top-level subcommands at runtime via `java.util.ServiceLoader`
(see `io.spicelabs.cli.spi.SpiceCommandPlugin`). A plugin is included in a build purely
by being **present on the classpath** — `spice` has no compile-time knowledge of it.

## How it works

Symlink a plugin's **repository root** into this directory:

```
plugins/allspice -> /path/to/allspice        # the repo root, not a deep subdir
```

By convention a plugin repo stages its jars — the plugin's own jar **and its runtime
dependency jars** (everything not already bundled in the CLI fat jar) — into a top-level
`dist/` directory. The symlink name is irrelevant — **any** symlink (or directory) here
is included.

At `mvn package`, every `*.jar` under `plugins/<name>/dist/` (following symlinks) is
collected, flattened, into `target/plugins/`. At runtime the launcher puts both the CLI
fat jar and `plugins/*` on the classpath:

```
java -cp "spice-labs-cli.jar:plugins/*" io.spicelabs.cli.SpiceLabsCLI ...
```

so each plugin jar keeps its own `META-INF/services/...SpiceCommandPlugin` and is
discovered. Plugins should depend on `io.spicelabs:spice-plugin-api` and on any
CLI-bundled libraries (goatrodeo, ginger-j, …) as **provided** scope, so those are not
duplicated here — version convergence is governed by the shared `io.spicelabs:spice-bom`
(spice-labs-inc/spice-bom).

## Public vs internal builds

A public/OSS build leaves this directory **empty** (no `registry`, no proprietary
plugins). An internal build symlinks the proprietary plugin components in. The symlinked
contents are never committed (see `.gitignore`).
