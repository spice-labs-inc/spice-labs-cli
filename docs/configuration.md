# Configuration

`spice` reads one TOML configuration file per run.

## Finding the file

| | |
| --- | --- |
| Explicit | `spice --config <file> …` or `spice --config=<file> …` |
| Unix | `$XDG_CONFIG_HOME/spice/config.toml` (default `$HOME/.config/spice/config.toml`), then `<dir>/spice/config.toml` for each `$XDG_CONFIG_DIRS` entry (default `/etc/xdg`) |
| Windows | `%APPDATA%\spice\config.toml`, then `%PROGRAMDATA%\spice\config.toml` |

**First match wins** — the rest are not consulted. Merging would make "where did this value
come from" a question with several answers.

Windows uses its native locations rather than XDG. `XDG_CONFIG_HOME` is deliberately not
consulted there: it would be one more place to look when a value surprises someone, on a
platform where nothing else sets it. `%APPDATA%` (roaming) rather than `%LOCALAPPDATA%`
because configuration is user intent and should follow the user between machines, unlike the
manifest cache the wrapper keeps.

Naming a file that does not exist is an error. A missing file at a *discovered* location just
means there is no config file.

`--config` is a `spice` option, not an inherited one, so it goes **before** the subcommand:

```
spice --config ./ci.toml survey inventory my-app ./build
```

Subcommands have their own `--config` meaning their own config file — the `registry`
commands do — and those are different files that deserve different answers.

### Discovery runs on the host

`spice` runs in Docker by default, and the container is given neither `HOME` nor `XDG_*`. So
discovery happens in the `spice` / `spice.ps1` wrapper, on the host, which then passes the
resolved path in as `--config`. That also gets the file bind-mounted for free: `--config` is
typed as a `Path`, so the path manifest lists it and the wrapper mounts it like any other path
argument.

The Java-side discovery in `ConfigFile` exists for `java -jar` runs that bypass the wrapper.
Inside the container it finds nothing, which is the correct outcome and needs no container
detection.

## The shape of the file

A command's settings live at its command path; a component the command embeds gets a
sub-table named after it.

```toml
[survey.inventory]
threads = 8

# The analysis engine's own schema, carried by spice without being understood
[survey.inventory.analysis]
max_records = 100000
mime_filter = ["+application/java-archive"]

# A plugin's own schema, likewise
[registry]

[[registry.repositories]]
id = "nexus"

[registry.analysis]
threads = 16
```

**`spice` does not understand the tables it carries.** That is the point: it is what keeps
this CLI free of every plugin's schema. The previous attempt at cross-program configuration
failed exactly there — an allowlist of another program's flags, maintained here, that drifted
until it permitted flags that program does not have.

Plugins receive their table through `SpiceContext.configuration()` (SPI 3), already parsed and
sliced to their own command path. A plugin never sees the file and never learns where its
table sits.

## Precedence

    defaults  <  config file  <  environment  <  command line

## What the config file cannot set

Nothing from the **Spice Pass**. The cutoff, upload server and project/organization/user
identifiers are properties of the credential the platform issued, not settings an operator
chooses — so they have no config-file key at all, and reach commands through
`SpiceContext.passClaims()` instead: registered JWT claims typed, everything Spice-specific
in a verbatim `additionalClaims()` map.

The one place the two nearly meet is the analysis engine's `expiry`, which *is* one of its own
settings when it runs standalone. In a nested `analysis` table it is rejected with an error
saying where the value actually comes from.
