# 🔩 Spice Labs CLI

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/spice-labs-cli?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/spice-labs-cli)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/spice-labs-cli?label=GitHub%20Release)](https://github.com/spice-labs-inc/spice-labs-cli/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/spice-labs-cli/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/spice-labs-cli?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/spice-labs-cli)

The **Spice Labs CLI** is a JVM-based and containerized CLI that scans software artifacts to generate encrypted **Artifact Dependency Graphs (ADGs)** and uploads them securely to Spice Labs.

---

## Installation

### Linux/macOS
```bash
curl -sSf https://install.spicelabs.io | bash
# adds a 'spice' launcher to ~/.local/bin
```

### Windows (PowerShell)
```powershell
irm -UseBasicParsing -Uri https://install.spicelabs.io | iex
# installs a 'spice.ps1' launcher under %USERPROFILE%\.spice\bin
```

Requirements: Docker installed and available on PATH (unless you enable JVM mode).

---

## Quick start

Scan a directory and upload ADGs to your Spice Labs project (default `--command run`).

```bash
export SPICE_PASS="…your JWT…"
spice --input ./target --tag test-scan
```

Scan only (no upload) and write outputs to a local directory:

```bash
spice --command scan-artifacts --input ./target --output ./out --tag test-scan
```

Upload previously generated ADGs from a directory:

```bash
export SPICE_PASS="…your JWT…"
spice --command upload-adgs --input ./out --tag wasabi
```

Run with a different log level:

```bash
spice --input ./target --tag wasabi --log-level debug
```

---

## Commands

- `run` (default): scans the `--input` directory and uploads the resulting ADGs
- `scan-artifacts`: scans the `--input` directory and writes results to `--output` (no upload)
- `upload-adgs`: uploads ADGs found under `--input` (requires `SPICE_PASS`)
- `upload-deployment-events`: uploads deployment events (for advanced use; subject to change)

---

## Options

| Option                  | Required | Default | Description                                                                 |
|-------------------------|----------|---------|-----------------------------------------------------------------------------|
| `--command <cmd>`       | No       | run     | One of: `run`, `scan-artifacts`, `upload-adgs`, `upload-deployment-events` |
| `--input <dir>`         | Yes      | —       | Directory to scan or read ADGs from                                         |
| `--output <dir>`        | No       | —       | Where to write local results when scanning                                  |
| `--tag <tag>`           | Yes      | —       | Tag to associate with the scan/upload (e.g., a service or release name)     |
| `--log-level <level>`   | No       | info    | Logging level: `error`, `warn`, `info`, `debug`, `trace`                    |
| `--goat-rodeo-args k=v[,k=v…]` | No | —       | Extra scanner tuning (e.g., `blockList=/etc/blocklist.txt,tempDir=/tmp`)    |

Notes:

- `--tag` is required by the CLI
- When uploading, the CLI reads the **Spice Pass** from the `SPICE_PASS` environment variable

---

## Environment variables

| Variable                     | Purpose                                                                                       |
|-----------------------------|-----------------------------------------------------------------------------------------------|
| `SPICE_PASS`                | Required for uploads.  JWT for your Spice Labs project                                        |
| `SPICE_LABS_CLI_USE_JVM`    | Set to `1` to run the local JAR instead of Docker                                            |
| `SPICE_LABS_JVM_ARGS`       | Additional JVM options (e.g., `-XX:MaxRAMPercentage=75`)                                     |
| `SPICE_LABS_CLI_SKIP_PULL`  | Set to `1` to skip `docker pull` in the launcher                                             |

---

## Docker usage (without the launcher)

```bash
docker run \
  --rm \
  -v "$PWD/target:/mnt/input:ro" \
  -e SPICE_PASS \
  spicelabs/spice-labs-cli:latest \
  --input /mnt/input \
  --tag test-scan
```

You may pin the image for reproducible builds:

```bash
spicelabs/spice-labs-cli@sha256:<digest>
```

---

## GitHub Actions

Use the published composite action to scan and upload in a workflow job:

```yaml
- name: Index and upload ADG
  uses: spice-labs-inc/action-spice-labs-cli-scan@v3
  with:
    file_path: ${{ github.workspace }}/target
    spice_pass: ${{ secrets.SPICE_PASS }}
    tag: wasabi
```

---

## Building from source

- JDK 21 and Maven are required
- Package: `mvn -B -DskipTests package`
- The Docker image embeds the fat JAR and a `spice` launcher script

---

## 📦 Repository

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo) — ADG scanner
- [`ginger-j`](https://github.com/spice-labs-inc/ginger-j) — secure uploader
- [`spice-labs-cli`](https://github.com/spice-labs-inc/spice-labs-cli) — this CLI

---

## ⚖️ License

Apache License 2.0. See [`LICENSE`](LICENSE).
