# 🔩 Spice Labs Surveyor CLI

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/spice-labs-cli?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/spice-labs-cli)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/spice-labs-cli?label=GitHub%20Release)](https://github.com/spice-labs-inc/spice-labs-cli/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/spice-labs-cli/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/spice-labs-cli?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/spice-labs-cli)

Surveys software artifacts for post-quantum cryptographic readiness. Supports **inventory surveys** (static dependency analysis) and **runtime surveys** (JFR-based runtime crypto detection).

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

### Inventory Survey

Analyze a project's dependency graph for cryptographic algorithm usage:

```bash
spice survey inventory <subject> <input>
```

- **`subject`** — label identifying the system being surveyed (shown on the dashboard)
- **`input`** — path to artifacts (directory or single file)

### Runtime Survey

Instrument a running JVM to detect cryptographic operations executed at runtime:

```bash
spice survey runtime <subject> --jfr -- <command>
```

- **`subject`** — label identifying the system being surveyed
- **`--`** — separates CLI options from the target command
- **`command`** — the JVM command to instrument (e.g. `java -jar app.jar`, `mvn test`)

### Examples

```bash
# Inventory survey
spice survey inventory my-app ./build/output
spice survey inventory my-app ./artifacts/my-app.tar
spice survey inventory my-app ./build/output --no-upload

# Runtime survey — instrument a Java application
spice survey runtime my-app --jfr -- java -jar app.jar

# Runtime survey — instrument Maven tests
spice survey runtime my-app --jfr -- mvn test

# Runtime survey — native JDK events only (no agent)
spice survey runtime my-app --jfr --native-only -- java -jar app.jar

# Runtime survey — keep JFR recordings after upload
spice survey runtime my-app --jfr --keep-recording -- mvn test

# Runtime survey — analyze locally without uploading
spice survey runtime my-app --jfr --no-upload -- java -jar app.jar

# Decode your Spice Pass
spice pass decode
```

---

## ⚙️ Options

### Inventory Survey

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

### Runtime Survey

| Option | Description | Default |
|--------|-------------|---------|
| `--jfr` | **Required.** Use JFR instrumentation | — |
| `--native-only` | Use only native JDK security events (no agent) | `false` |
| `--no-upload` | Analyze locally, don't upload results | `false` |
| `--keep-recording` | Don't delete JFR recordings after upload | `false` |
| `--output` | Directory for temporary files | `~/.spicelabs/runtime-survey/` |
| `--log-level` | `debug` \| `info` \| `warn` \| `error` | `info` |
| `--log-file` | Path to log file (output appended to both console and file) | _(none)_ |
| `--chunk-size` | Target chunk size in MB for uploads | `64` |

Flags can appear anywhere before the `--` separator.

### `--tag-json` on Windows PowerShell 5.1

PowerShell 5.1 strips outer single quotes before passing an argument to the CLI,
so `--tag-json='{"env":"dev"}'` arrives as a bare `{env:dev}` literal and fails
to parse. This is a PS5.1 quirk; PowerShell 7 handles the same syntax correctly.

Workaround — assign the JSON to a variable first:

```powershell
$json = '{"env":"dev","team":"platform"}'
spice survey inventory my-app C:\path\to\artifacts --tag-json=$json
```

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
        uses: spice-labs-inc/action-spice-labs-surveyor@v5
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
- [`ancho`](https://github.com/spice-labs-inc/ancho) — JFR instrumentation agent
- [`action-spice-labs-surveyor`](https://github.com/spice-labs-inc/action-spice-labs-surveyor) — GitHub Action

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

---

## ⚖️ License

Apache License 2.0. See [`LICENSE`](LICENSE).

---
