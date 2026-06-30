**Frequently Asked Questions/Situations With The Spice Labs Surveyor CLI**

---

**Q: `spice --help` shows a `registry` command on one machine but not another. Why?**

`registry` is provided by the [`allspice`](https://github.com/spice-labs-inc/allspice) plugin,
not the core CLI. It appears only when that plugin is on the classpath — i.e. in a build/image
that bundles it. The public CLI ships without it. See [Plugins](README.md#-plugins).

**Q: How do I add my own command to `spice`?**

Write a plugin — `spice` discovers commands at runtime via `ServiceLoader`, so you don't modify
the CLI itself. See [`docs/PLUGINS.md`](docs/PLUGINS.md) and the
[`sample/hello-plugin`](sample/hello-plugin) example.

**Q: I built a plugin but `spice` doesn't see it.**

Make sure the plugin's jars are under `plugins/<name>/dist/` (symlink the plugin's repo root
into `plugins/`), rebuild `spice` so they're collected into `target/plugins/`, and run with
`-cp "…:plugins/*"` (the wrapper and Docker image do this automatically). Also confirm the
plugin includes a `META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin` entry.