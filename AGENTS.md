# Project Rules

`spice` and `spice.ps1` need to be kept in sync, feature by feature parity.

## Test IDs and shared test data

Downstream tools assemble the `spice` CLI and run black-box integration tests against it
that reuse **this repository's own test data**. They do not copy the data into their trees:
a consumer reads `test-fixtures.json` at the root of this repository, at the exact commit
the shipped jar was built from (the commit recorded in
`META-INF/git/<artifactId>.properties`), and exports what that file declares. Every unit
test here and every downstream case derived from it share one ID:

```
spice-labs-cli/<qualified suite or class>#<test name>     code tests
spice-labs-cli/<data path>[#<case id>]                    data-driven cases: the file's `id` field
```

Consumers diff those IDs against their own coverage manifests and fail their CI on orphans,
so keep these rules when you write or change tests:

- **Put expectations in data, not in code.** A new fixture-driven expectation belongs in a
  data file the unit test reads (see below for this repository's format); a downstream tool
  can then evaluate the same file through `spice` without a second copy.
- **Every data file gets an `id`**, derived from its path exactly as the existing files do,
  and the unit test that reads it must require it. Never hand-pick ids.
- **Declare new data in `test-fixtures.json`**: an `expectations[]` entry (glob, format,
  `idField`, how the fixture path derives) and a `fixtures[]` entry with a tier (0 committed
  and small, 1 downloadable for a nightly run, 2 full corpora). Undeclared data is invisible
  to consumers.
- **Keep tier-0 fixtures small** (kilobytes to a few megabytes); anything large is a download
  (`downloads[]` with a sha256) or LFS, at tier 1 or 2.
- **Renaming a test or data file changes its ID.** That is allowed, but downstream coverage
  manifests will report an orphan; mention it in the PR so they can be regenerated.
- **Keep the provenance plugin.** The jar must record its commit in
  `META-INF/git/<artifactId>.properties`; without it consumers fall back to the release tag.

### Here

- Downstream integration tests re-run the `SpiceLabsCLITest` cases (help, error texts, exit
  codes, output files) through the real `spice` wrapper by **method name**; keep those names
  stable, and when you add a user-visible behaviour, add its `SpiceLabsCLITest` method and
  tell the maintainers of those tests so the case is projected there.
- The wrapper's `test/wrapper/*.bats` and Pester tests run against a mock container; the real
  wrapper is exercised by downstream end-to-end and `--driver tmux` runs.
- The jar records its commit twice: `git.properties` (existing) and
  `META-INF/git/spice-labs-cli.properties` (unique name, survives shading). Keep both.
