# Project Rules

`spice` and `spice.ps1` need to be kept in sync, feature by feature parity.

## Tests shared with Surveyor

[Surveyor](https://github.com/spice-labs-inc/surveyor) assembles the `spice` CLI and runs
black-box integration tests against it that reuse **this repository's own test data**. It
does not copy the data into its tree: `build-surveyor expectations` reads `test-fixtures.json`
at the root of this repository, at the exact commit the shipped jar was built from (the
commit recorded in `META-INF/git/<artifactId>.properties`), and exports what that file
declares. Every unit test here and every Surveyor case derived from it share one ID:

```
spice-labs-cli/<qualified suite or class>#<test name>     code tests
spice-labs-cli/<data path>[#<case id>]                    data-driven cases: the file's `id` field
```

Surveyor's `scripts/compare-test-ids.py` diffs those IDs against `tests/coverage/spice-labs-cli.tsv`
and fails its CI on orphans, so keep these rules when you write or change tests:

- **Put expectations in data, not in code.** A new fixture-driven expectation belongs in a
  data file the unit test reads (see below for this repository's format); Surveyor can then
  evaluate the same file through `spice` without a second copy.
- **Every data file gets an `id`**, derived from its path exactly as the existing files do,
  and the unit test that reads it must require it. Never hand-pick ids.
- **Declare new data in `test-fixtures.json`**: an `expectations[]` entry (glob, format,
  `idField`, how the fixture path derives) and a `fixtures[]` entry with a tier (0 committed
  and small, 1 downloadable for a nightly run, 2 full corpora). Undeclared data is invisible
  to Surveyor.
- **Keep tier-0 fixtures small** (kilobytes to a few megabytes); anything large is a download
  (`downloads[]` with a sha256) or LFS, at tier 1 or 2.
- **Renaming a test or data file changes its ID.** That is allowed, but Surveyor's coverage
  manifest will report an orphan; mention it in the PR so the manifest is regenerated.
- **Keep the provenance plugin.** The jar must record its commit in
  `META-INF/git/<artifactId>.properties`; without it Surveyor falls back to the release tag.

### Here

- A distribution may narrow what `spice` can do by shipping `spice-edition.properties` on the
  classpath (see `Edition`); that decides which commands exist and whether any upload path
  does. Gate a new command in `EditionGate`, never with an `if` at the point of use, so
  `--help`, completion and the path manifest agree with the parser. A build without the
  manifest is unrestricted, which is what keeps a plain `mvn package` here unchanged, and
  nothing in this repository should know any particular distribution's editions by name.
- Surveyor re-runs the `SpiceLabsCLITest` cases (help, error texts, exit codes, output files)
  through the real `spice` wrapper by **method name**; keep those names stable, and when you
  add a user-visible behaviour, add its `SpiceLabsCLITest` method and tell Surveyor's
  `adapters/cli.py` maintainer so the case is projected there.
- The wrapper's `test/wrapper/*.bats` and Pester tests run against a mock container; the real
  wrapper is exercised by Surveyor's `tests/e2e` (Scoville) and `--driver tmux` runs.
- The jar records its commit twice: `git.properties` (existing) and
  `META-INF/git/spice-labs-cli.properties` (unique name, survives shading). Keep both.
