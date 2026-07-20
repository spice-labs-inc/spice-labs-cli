# Releasing

This repo publishes three independently-versioned artifacts. Each releases on its own tag,
so a change to one never forces a release of the others.

| Artifact | Source | Tag | Workflow |
|---|---|---|---|
| `io.spicelabs:spice-plugin-api` | `shared/plugin-api/` | `plugin-api-v<x.y.z>` | `publish-plugin-api.yml` |
| `io.spicelabs:spice-bom` | `bom/` | `bom-v<x.y.z>` | `publish-bom.yml` |
| `io.spicelabs:spice-labs-cli` | `pom.xml` | GitHub Release `v<x.y.z>` | `publish.yml` |

In-repo, every version is a `-SNAPSHOT` (e.g. `1.0.0-SNAPSHOT`); `mvn install` puts it in
`~/.m2` for local/CI builds, and downstream repos (`goatrodeo`, `sassafras`, `allspice`) pin
the same `-SNAPSHOT`. A tag strips the `-SNAPSHOT` and publishes a real release to GitHub
Packages + Maven Central (Maven Central rejects `-SNAPSHOT`).

## Dependency order

`spice-plugin-api`  ←  `spice-bom` (references a released plugin-api)  ←  `spice-labs-cli`
(imports a released BOM). Release lower layers first. Each release workflow **verifies** its
inputs are already published (`dependency:get`) and fails fast otherwise.

## BOM version-bump policy (enforced)

The BOM's contract is the set of dependencies it manages. Relative to the last `bom-v*`
release, bump `bom/pom.xml`'s `<version>`:

- **patch** — a managed version changed (e.g. `logback` 1.5.18 → 1.5.19, or the pinned
  `spice-plugin-api` moved to a new release)
- **minor** — a managed artifact was added
- **major** — a managed artifact was removed

`bin/check-bom-version.py` computes the required bump by diffing the managed set against the
last `bom-v*` tag. Run it before tagging a BOM release to see the recommended version (and, in
its default mode, whether the in-repo `<version>` already reflects a sufficient bump):

```bash
python3 bin/check-bom-version.py --advise   # print the recommended patch/minor/major bump
python3 bin/check-bom-version.py            # exit non-zero if the version isn't bumped enough
```

(Note: this tracks the *set of coordinates and their pinned versions*. Upgrading a managed
dependency across its own major boundary is still a BOM **patch** — the BOM only promises the
version it pins, not that the pinned library is source-compatible.)

## Cutting a release

**plugin-api** (only when the SPI's Java API changed — SemVer it on the API):
1. Bump `shared/plugin-api/pom.xml` `<version>` to the new `-SNAPSHOT` as needed; merge.
2. Tag `plugin-api-v<x.y.z>` and push → `publish-plugin-api.yml` publishes it.
3. In `bom/pom.xml`, set `spice-plugin-api.version` to the released `<x.y.z>` (this is a BOM
   *version change* → BOM **patch** bump; see above).

**BOM**:
1. Ensure the managed versions are what you want and the `<version>` reflects the required
   bump (CI enforces this).
2. Tag `bom-v<x.y.z>` and push → `publish-bom.yml` verifies the pinned plugin-api is
   published, then publishes the BOM.

**CLI**:
1. Ensure `spice-bom.version` in `pom.xml` points at the BOM you want (its `-SNAPSHOT` base
   is the release target).
2. Publish a GitHub Release tagged `v<x.y.z>` → `publish.yml` verifies that `spice-bom` is
   published, pins the CLI to it, sets the CLI version, and deploys.

After a release, bump the relevant in-repo `-SNAPSHOT` to the next intended version.
Downstream repos pointing at a `-SNAPSHOT` see changes on their next `mvn install`; for their
own releases they pin the released BOM version.
