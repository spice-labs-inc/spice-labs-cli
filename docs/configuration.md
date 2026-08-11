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

## Groups

Settings live in **groups**, named for the job rather than for whoever does it — `analysis`,
`upload`, `crypto`, `pipeline`, `repositories`.

```toml
# Shared: every command that claims `analysis` sees this
[analysis]
threads = 16
max_records = 100000

[upload]
target_chunk_size = 64        # also: encrypt_only, skip_key, comment, bundle_format_version

[crypto]
max_classes = 50000

# Scoped: only `spice registry` sees this
[registry.analysis]
threads = 4
```

A group is **shared**. `[analysis] threads = 16` written once governs `spice survey
inventory` and `spice registry` alike, so you configure the job and not each component that
performs part of it. A same-named table under a command's path overrides for that command
alone.

A command reads a group only if it **claims** it, and it receives nothing else. That is what
makes sharing safe: a command cannot read settings meant for another, and a table nobody
claims can be reported as a probable typo rather than silently doing nothing.

```
WARN ⚠️  No command reads [anaylsis] — check the spelling, or the plugin may not be installed
```

A group is usually a table of settings. Some name a list of things — `[[repositories]]` — and
have no keys to layer, so a later source replaces such a group whole or leaves it alone.

**A group may not share a name with a command.** At the root of the file a table is either a
group or a command's scope, so `[registry.analysis]` can only mean "the analysis group, for
the registry command" if nothing called `registry` is also a group.

**`spice` does not understand the groups it carries.** That is the point: it is what keeps
this CLI free of every component's schema. The previous attempt at cross-program
configuration failed exactly there — an allowlist of another program's flags, maintained
here, that drifted until it permitted flags that program does not have.

Plugins receive their claimed groups through `SpiceContext.configuration()` (SPI 4), already
resolved. A plugin never sees the file and never learns where its tables sit.

## One setting, three names

| Form | Shape | Example |
| --- | --- | --- |
| Config key | `snake_case` in a group | `[analysis] max_records` |
| Flag | its kebab-case form | `--max-records` |
| Environment | `SPICE_<GROUP>_<KEY>` | `SPICE_ANALYSIS_MAX_RECORDS` |

No exceptions, so there is no table of them to remember. The one variation: when a command
claims two groups that both define `threads`, the flag is qualified as `--analysis-threads` —
still derived, not remembered.

Flags are *bindings onto group keys*, not values of their own. `--threads` and `[analysis]
threads` are one setting reached two ways, rather than two settings that have to be kept in
agreement.

Run a component standalone and only the prefix changes:

| Component | Prefix |
| --- | --- |
| `spice` | `SPICE_` |
| `goatrodeo` | `GOATRODEO_` |
| `allspice` | `ALLSPICE_` |
| `sassafras` | `SASSAFRAS_` |

The environment is matched against *claimed group names* rather than parsed, so the wrapper's
own variables — `SPICE_IMAGE`, `SPICE_CACHE_DIR`, `SPICE_PATH_MANIFEST`, `SPICE_PASS` — can
never be mistaken for settings. They are read on the host before any JVM exists and are not
part of any configuration. No group may be named `image`, `cache`, `path` or `pass`.

## Logging is a group like any other

```toml
[logging]
level = "debug"          # error, warn, info, debug, trace
file = "/tmp/spice.log"  # in addition to the console
```

`--log-level` and `--log-file` set the same two keys, and so does
`SPICE_LOGGING_LEVEL`. Every Spice tool reads this group, with the same keys and the same
precedence, so a level means one thing wherever it is set and two runs' logs can be read
side by side.

Standalone components differ only in the prefix: `GOATRODEO_LOGGING_LEVEL`,
`ALLSPICE_LOGGING_LEVEL`, `SASSAFRAS_LOGGING_LEVEL`, `GINGER_LOGGING_LEVEL`.

Each tool moves only its own loggers. A level says how much *that* program should say, and
lifting a noisy dependency along with it buries the output you asked for.

A library never applies this: the group is applied by whichever program owns the process,
because a library reconfiguring its host's logging is a rude surprise.

## Precedence

    defaults  <  [group]  <  [command.group]  <  environment  <  command line

Resolution does not depend on the order sources are supplied in: a value may only be
displaced by one from a strictly later layer.

**There is no fifth channel.** System properties are not a configuration input anywhere in
`spice` or its components. Setting one to steer a third-party library is an *output*, written
once from a resolved configuration and never read back.

## Disagreements are reported

One place decides which source wins, so one place can say so:

```
INFO ⚙️  analysis.threads = 8 (SPICE_ANALYSIS_THREADS) overrides 16 ([analysis] in ~/.config/spice/config.toml)
```

Overriding a *default* is deliberately not reported: that happens for every setting on every
run, and the noise would bury the cases where two deliberate choices conflict.

`spice config explain` prints the whole resolved configuration with an origin per key —
which is also how to answer "why is it doing that?":

```
$ spice config explain survey inventory
# /home/u/.config/spice/config.toml

[analysis]
  max_records = 100000    [analysis] in /home/u/.config/spice/config.toml
  threads     = 4         [survey.inventory.analysis] in /home/u/.config/spice/config.toml
```

Standalone components print the same thing with `--explain-config` (`allspice
explain-config`).

A value that came from the environment shows quoted — `threads = "8"` — because the
environment has no types and the value really is text until the component that knows the
schema coerces it.

## Where the rules live

Naming, layering, precedence and provenance are implemented once, in
[`spice-config`](https://github.com/spice-labs-inc/spice-config), and shared by every
component. Rules copied into several codebases are rules that will disagree, and these
already have.

## What the config file cannot set

Nothing from the **Spice Pass**. The cutoff, upload server and project/organization/user
identifiers are properties of the credential the platform issued, not settings an operator
chooses — so they have no config-file key at all, and reach commands through
`SpiceContext.passClaims()` instead: registered JWT claims typed, everything Spice-specific
in a verbatim `additionalClaims()` map.

The one place the two nearly meet is the analysis engine's `expiry`, which *is* one of its own
settings when it runs standalone. In a nested `analysis` table it is rejected with an error
saying where the value actually comes from.
