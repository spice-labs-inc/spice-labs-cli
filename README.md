
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

The shortest valid command:

```bash
spice --tag=<tag>
```

With an explicit input path:

```bash
spice --input=path/to/my-dir --tag=<tag>
```

`--tag` is required when using the default `run` command. It groups surveys of the same system over time.

The default command is `run` (survey + upload). Use `--command` only when running something other than `run`:

```bash
spice --command=survey-artifacts --input=path/to/my-dir
```

---

## ⚙️ Options

| Option | Description | Default |
|--------|-------------|---------|
| `--command` | `run` \| `survey-artifacts` \| `upload-adgs` \| `decode-spice-pass` | `run` |
| `--input` | Input path | current directory |
| `--output` | Output path | _(none)_ |
| `--tag` | Tag all top-level artifacts with the current date and the given text. **Required for `run`.** | _(none)_ |
| `--tag-json` | Add JSON to any tags | _(none)_ |
| `--log-level` | `all` \| `trace` \| `debug` \| `info` \| `warn` \| `error` \| `fatal` \| `off` | `info` |
| `--log-file` | Append log output to this file (in addition to console). ANSI codes are stripped. | _(none)_ |
| `--threads` | Number of threads to use | half of available CPU cores |
| `--max-records` | Max records to process per batch | `5000` |
| `--use-static-metadata` | Augment Goat Rodeo information with other static metadata | `false` |
| `--ci` | CI mode | `false` |
| `--ginger-args` | Additional Ginger builder args (e.g. `--ginger-args="--skip-key,--encrypt-only"`) | _(none)_ |
| `--goat-rodeo-args` | Additional GoatRodeo builder args (e.g. `--goat-rodeo-args="blockList=ignored,tempDir=/tmp"`) | _(none)_ |

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
  --input=/mnt/input \
  --output=/mnt/output \
  --tag=<tag>
```

### Upload only

```bash
docker run --rm \
  --user $(id -u):$(id -g) \
  --network host \
  -e SPICE_PASS=... \
  -v "$PWD/output:/mnt/input" \
  spicelabs/spice-labs-cli \
  --command=upload-adgs \
  --input=/mnt/input
```

The wrapper script automatically remaps `--input` and `--output` host paths to `/mnt/input` and `/mnt/output` inside the container.

---

## 📦 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPICE_PASS` | **Required** for `upload-*` commands. JWT token for Spice Labs auth. | _(none)_ |
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
mvn clean install
```

Run the built JAR:

```bash
java -jar target/spice-labs-cli-*-fat.jar --version
```

Or with Maven:

```bash
mvn exec:java -Dexec.mainClass="io.spicelabs.cli.SpiceLabsCLI" \
  -Dexec.args="--command=run --input=./my-dir --output=./out-dir --log-level=all"
```

---

## 🚀 Releasing

1. Create a GitHub Release with a tag such as `v0.2.0`. This triggers CI to build the JAR, publish to GitHub Packages and Maven Central, and push the Docker image.
2. Verify the release on [Maven Central](https://central.sonatype.com) (propagation takes ~40 minutes).

---

## 📦 Related Repositories

- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo) — ADG surveyor
- [`ginger-j`](https://github.com/spice-labs-inc/ginger-j) — secure uploader
- [`spice-labs-cli`](https://github.com/spice-labs-inc/spice-labs-cli) — this repository

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

---

## ⚖️ License

Apache License 2.0. See [`LICENSE`](LICENSE).

---