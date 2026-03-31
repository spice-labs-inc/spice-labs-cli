# 🔩 Spice Labs Surveyor CLI

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/spice-labs-cli?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/spice-labs-cli)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/spice-labs-cli?label=GitHub%20Release)](https://github.com/spice-labs-inc/spice-labs-cli/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/spice-labs-cli/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/spice-labs-cli?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/spice-labs-cli)

Surveys software artifacts to generate encrypted **Artifact Dependency Graphs (ADGs)** and uploads them to Spice Labs.

---

## ⚡️ Prerequisites

- **Docker** must be installed and running. [Get Docker](https://docs.docker.com/get-docker/)
- A **Spice Pass** set as the `SPICE_PASS` environment variable. Download it from your Spice Labs project dashboard.

---

## 🚀 Installation

### macOS / Linux

```bash
curl -sSLf https://install.spicelabs.io | bash
```

### Windows PowerShell

```powershell
irm -UseBasicParsing -Uri https://install.spicelabs.io | iex
```

After installation, add `spice` to your PATH as instructed by the installer.

---

## 🔧 Usage

Survey and upload (the default flow):

```bash
spice survey inventory <subject> <input>
```

- **`subject`** — label identifying the system being surveyed (shown on the dashboard)
- **`input`** — path to artifacts (directory or single file)

### Examples

```bash
# Survey a directory and upload
spice survey inventory my-app ./build/output

# Survey a single artifact
spice survey inventory my-app ./artifacts/my-app.tar

# Survey only, skip upload
spice survey inventory my-app ./build/output --no-upload

# Upload previously-generated ADGs
spice survey inventory my-app ./adg-output --upload-only

# Decode your Spice Pass
spice pass decode
```

---

## ⚙️ Options

| Option | Description | Default |
|--------|-------------|---------|
| `--no-upload` | Survey only, skip upload | `false` |
| `--upload-only` | Upload previously-generated ADGs (skip survey) | `false` |
| `--output` | Output directory for survey results | `~/.spicelabs/surveyor/` |
| `--tag-json` | Additional JSON metadata for tags | _(none)_ |
| `--log-level` | `debug` \| `info` \| `warn` \| `error` | `info` |
| `--log-file` | Path to log file (output appended to both console and file) | _(none)_ |
| `--threads` | Number of threads to use | half of available CPU cores |
| `--max-records` | Max records to process per batch | `5000` |
| `--chunk-size` | Target chunk size in MB for uploads | `64` |
| `--goat-rodeo-args` | Additional GoatRodeo args in key=value format | _(none)_ |
| `--ginger-args` | Additional Ginger args in key=value format | _(none)_ |

Flags can appear anywhere in the command line.

---

## 🐳 Docker

### Survey and upload

```bash
docker run --rm \
  --user $(id -u):$(id -g) \
  --network host \
  -e SPICE_PASS=... \
  -v "$PWD/input:/mnt/input" \
  -v "$PWD/output:/mnt/output" \
  spicelabs/spice-labs-cli \
  survey inventory my-app /mnt/input --output=/mnt/output
```

### Upload only

```bash
docker run --rm \
  --user $(id -u):$(id -g) \
  --network host \
  -e SPICE_PASS=... \
  -v "$PWD/adgs:/mnt/input" \
  spicelabs/spice-labs-cli \
  survey inventory my-app /mnt/input --upload-only
```

The wrapper script automatically remaps `input` and `--output` host paths to `/mnt/input` and `/mnt/output` inside the container.

---

## 📦 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPICE_PASS` | **Required** for upload. JWT token for Spice Labs auth. | _(none)_ |
| `SPICE_LABS_CLI_USE_JVM` | Use the local JVM instead of Docker (`1` = enable) | `0` |
| `SPICE_LABS_CLI_JAR` | Path to the CLI JAR when using JVM mode | `/opt/spice-labs-cli/spice-labs-cli.jar` |
| `SPICE_LABS_JVM_ARGS` | Custom JVM flags (e.g. `-Xmx512m -XX:+UseG1GC`) | `-XX:MaxRAMPercentage=75` |
| `SPICE_IMAGE` | Docker image to use | `spicelabs/spice-labs-cli` |
| `SPICE_IMAGE_TAG` | Docker image tag | `latest` |
| `SPICE_LABS_CLI_SKIP_PULL` | Skip `docker pull` before run (`1` = skip) | `0` |
| `SPICE_DOCKER_FLAGS` | Additional flags passed to `docker run` | _(none)_ |

---

## 🧩 GitHub Actions

```yaml
jobs:
  spice-survey:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Spice Labs Surveyor
        uses: spice-labs-inc/action-spice-labs-surveyor@v2
```

See the [Spice Labs Surveyor GitHub Action](https://github.com/spice-labs-inc/action-spice-labs-surveyor) for full configuration options.

---

## 🛠️ Building Locally

Requirements: JDK 21+, Maven 3.6+

```bash
git clone https://github.com/spice-labs-inc/spice-labs-cli.git
cd spice-labs-cli
mvn clean package -DskipTests
```

Run with Docker:

```bash
docker build -t spicelabs/spice-labs-cli:local .
SPICE_IMAGE_TAG=local SPICE_LABS_CLI_SKIP_PULL=1 ./spice survey inventory my-app ./path --no-upload
```

Run with JVM directly:

```bash
java -jar target/spice-labs-cli-*-fat.jar --version
java -jar target/spice-labs-cli-*-fat.jar survey inventory my-app ./path --no-upload
```

---

## 🚀 Releasing

1. Create a GitHub Release with a tag such as `v2.0.0`. This triggers CI to build the JAR, publish to GitHub Packages and Maven Central, and push the Docker image.
2. Verify the release on [Maven Central](https://central.sonatype.com) (propagation takes ~40 minutes).

---

## 📦 Related Repositories

- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo) — ADG surveyor
- [`ginger-j`](https://github.com/spice-labs-inc/ginger-j) — secure uploader
- [`action-spice-labs-surveyor`](https://github.com/spice-labs-inc/action-spice-labs-surveyor) — GitHub Action

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

---

## ⚖️ License

Apache License 2.0. See [`LICENSE`](LICENSE).

---
