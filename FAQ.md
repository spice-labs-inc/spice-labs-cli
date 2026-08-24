**Frequently Asked Questions/Situations With The Spice Labs Surveyor CLI**

---

**Q: `spice --help` shows a `registry` command on one machine but not another. Why?**

`registry` is provided by the [`allspice`](https://github.com/spice-labs-inc/allspice) plugin,
not the core CLI. It appears only when that plugin is on the classpath — i.e. in a build/image
that bundles it. The public CLI ships without it. See [Plugins](README.md#-plugins).

**Q: My survey covered fewer artifacts than I expected.**

Your Spice Pass may carry an **artifact cutoff**, which puts anything published after a given
instant out of scope, along with anything that transitively contains it. It follows the pass
rather than any flag, and applies to both the inventory analysis and the `registry` discovery
analysis. Run `spice pass decode` and look for **Artifact Cutoff**; when one is in force, each
run logs `Ignoring artifacts published after …`. See
[Artifact cutoff](README.md#artifact-cutoff).

**Q: How do I add my own command to `spice`?**

Write a plugin — `spice` discovers commands at runtime via `ServiceLoader`, so you don't modify
the CLI itself. See [`docs/PLUGINS.md`](docs/PLUGINS.md) and the
[`sample/hello-plugin`](sample/hello-plugin) example.

**Q: I built a plugin but `spice` doesn't see it.**

Make sure the plugin's jars are under `plugins/<name>/dist/` (symlink the plugin's repo root
into `plugins/`), rebuild `spice` so they're collected into `target/plugins/`, and run with
`-cp "…:plugins/*"` (the wrapper and Docker image do this automatically). Also confirm the
plugin includes a `META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin` entry.