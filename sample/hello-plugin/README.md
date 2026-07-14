# hello-plugin — a sample `spice` plugin

A minimal, self-contained example of a `spice` subcommand plugin. It adds a top-level
`spice hello` command that prints a greeting. Use it as a template (or a smoke test) for
the plugin mechanism.

## What's here

- `pom.xml` — its own small Maven build; depends on `io.spicelabs:spice-plugin-api` and
  `picocli` as **provided** (the CLI ships both), and writes its jar to `dist/`.
- `HelloPlugin.java` — implements `io.spicelabs.cli.spi.SpiceCommandPlugin` and returns a
  picocli `hello` command.
- `META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin` — registers the provider for
  `ServiceLoader` discovery.

## Build and try it

From the `spice` repo root:

```bash
# 1. Build the plugin (needs spice-plugin-api in ~/.m2 — `mvn install` it from qahwa).
mvn -f sample/hello-plugin package          # → sample/hello-plugin/dist/hello-plugin.jar

# 2. Symlink it into plugins/ (any name; the build looks in <name>/dist/).
ln -s ../sample/hello-plugin plugins/hello

# 3. Build spice; the plugin is collected onto the classpath.
mvn -DskipTests package

# 4. Run it.
FAT="$PWD/target/spice-labs-cli-0.0.1-SNAPSHOT-fat.jar"
java -cp "${FAT}:target/plugins/*" io.spicelabs.cli.SpiceLabsCLI hello
# → Hello world! (from the spice sample plugin, CLI 0.0.1-SNAPSHOT)
```

`spice --help` will now list `hello`. Remove the symlink and rebuild to confirm it
disappears — inclusion is purely by presence on the classpath.
