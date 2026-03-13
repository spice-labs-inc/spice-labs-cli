# 🔩 Spice Labs Surveyor CLI

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/spice-labs-cli?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/spice-labs-cli)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/spice-labs-cli?label=GitHub%20Release)](https://github.com/spice-labs-inc/spice-labs-cli/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/spice-labs-cli/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/spice-labs-cli?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/spice-labs-cli)

The **Spice Labs Surveyor CLI** is a JVM-based and containerized CLI that surveys software artifacts to generate encrypted **Artifact Dependency Graphs (ADGs)** and uploads them securely to Spice Labs.


## 🚀 Quick Start

## ⚡️ Prerequisites

- **Docker** must be installed and running on your system.
  [Get Docker](https://docs.docker.com/get-docker/)

### 🧪 Recommended: Installer Script

#### 🐧 macOS/Linux

```bash
curl -sSLf https://install.spicelabs.io | bash
```

#### 🪟 Windows PowerShell

```powershell
irm -UseBasicParsing -Uri https://install.spicelabs.io | iex
```

### Basic Usage

After installation, **add it to your PATH as instructed by the installer**.

Also, set your `SPICE_PASS` environment variable.
Your Spice Pass can be downloaded from your Spice Labs project dashboard's settings page.

After installation, run the CLI using:
```bash
spice --tag=my-module-name
```
Define input path (defaults to current directory):
```bash
spice --input=path/to/my-dir --tag=my-module-name
```

> **Note:** `--tag=my-module-name` is **required** when using the default `run` command. It is used for grouping surveys of the same systems over time. The shortest valid command is `spice --tag=<tag>`.

Each time `spice` runs, it automatically checks for updates to the script and will notify you if a newer version is available.

---

## ⚙️ CLI Options

```bash
spice \
  [--ci] \
  [--command=run|survey-artifacts|upload-adgs|decode-spice-pass] \
  [--input=<path>] \
  [--output=<path>] \
  [--log-level=all|trace|debug|info|warn|error|fatal|off] \
  [--log-file=<path>] \
  [--threads=<number>] \
  --tag=<tag> \
  [--tag-json=<json>] \
  [--max-records=<number>] \
  [--use-static-metadata] \
  [--ginger-args=<key=value>[,<key=value>...]] \
  [--goat-rodeo-args=<key=value>[,<key=value>...]]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--command` | `run` (surveys and uploads ADGs) \| `survey-artifacts` \| `upload-adgs` \| `decode-spice-pass` | `run` |
| `--input` | Input path | current directory |
| `--output` | Output path | _(none)_ |
| `--tag` | **Required for `run` command.** Tags all top-level artifacts (files) with the current date and the text of the tag, for grouping surveys of the same systems over time | _(none)_ |
| `--tag-json` | Add JSON to any tags | _(none)_ |
| `--log-level` | `all\|trace\|debug\|info\|warn\|error\|fatal\|off` | `info` |
| `--log-file` | Path to log file (output will be appended to both console and file) | _(none)_ |
| `--threads` | Number of threads to use when surveying | half of available CPU cores |
| `--max-records` | Max number of ADG records to process per batch | `5000` |
| `--use-static-metadata` | Augment Goat Rodeo information with other static metadata | `false` |
| `--ci` | CI mode | `false` |
| `--ginger-args` | Additional Ginger builder args in `key=value` format (e.g. `--ginger-args="--skip-key,--encrypt-only"`) | _(none)_ |
| `--goat-rodeo-args` | Additional GoatRodeo builder args in `key=value` format (e.g. `--goat-rodeo-args="blockList=ignored,tempDir=/tmp"`) | _(none)_ |

Default command is `run`, which surveys and uploads in one step. Note that `--tag` is required when using the `run` command.

> **Note:** `--log-file` is handled by the wrapper script. ANSI color codes are automatically stripped from file output.

---

## 🐳 Docker Usage _(Advanced)_

```bash
docker run --rm \
  --user $(id -u):$(id -g) \
  --network host \
  -e SPICE_PASS=... \
  -v "$PWD/input:/mnt/input" \
  -v "$PWD/output:/mnt/output" \
  spicelabs/spice-labs-cli \
  --command=run \
  --input=/mnt/input \
  --output=/mnt/output
```
- `-v "/home/<username>/testdata:/mnt/input"` Mounts your actual data directory into the container at `"/mnt/input"`
- The CLI still looks for input at `"/mnt/input"` inside the container, but that now points to `"/home/<username>/testdata"` on your host
- The wrapper script automatically remaps `--input` and `--output` host paths to `/mnt/input` and `/mnt/output` inside the container

Upload only:

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

---

## 📦 Environment Variables

| Variable                   | Description                                                          | Default                                  |
| -------------------------- | -------------------------------------------------------------------- | ---------------------------------------- |
| `SPICE_PASS`               | **Required** for `upload-*` commands. JWT token for Spice Labs auth. | _(no default)_                           |
| `SPICE_LABS_CLI_USE_JVM`   | Run the CLI using the local JVM instead of Docker (`1` = enable)     | `0`                                      |
| `SPICE_LABS_CLI_JAR`       | Path to the CLI JAR when using JVM mode                              | `/opt/spice-labs-cli/spice-labs-cli.jar` |
| `SPICE_LABS_JVM_ARGS`      | Custom JVM tuning flags (e.g., `-Xmx512m -XX:+UseG1GC`)              | `-XX:MaxRAMPercentage=75`                |
| `SPICE_IMAGE`              | Docker image to use when not in JVM mode                             | `spicelabs/spice-labs-cli`               |
| `SPICE_IMAGE_TAG`          | Docker image tag                                                     | `latest`                                 |
| `SPICE_LABS_CLI_SKIP_PULL` | Skip `docker pull` before run (`1` = skip)                           | `0`                                      |
| `SPICE_DOCKER_FLAGS`       | Additional flags passed directly to `docker run`                     | _(none)_                                 |

---

## 🧩 GitHub Actions

Use the [Spice Labs Surveyor GitHub Action](https://github.com/spice-labs-inc/action-spice-labs-surveyor) in your workflow:

```yaml
jobs:
  spice-survey:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Spice Labs Surveyor
        uses: spice-labs-inc/action-spice-labs-surveyor@v2
```

---

## 🛠️ Maintainers

### 🔨 Build Locally

Install JDK 21+ and Maven 3.6+.

Set SPICE_PASS in your environment:

```bash
export SPICE_PASS=your_spice_pass
```

Clone the repo:

```bash
git clone https://github.com/spice-labs-inc/spice-labs-cli.git
cd spice-labs-cli
```

Build with Maven:

```bash
mvn clean install
```

Fat JAR is output at:

```
target/spice-labs-cli-0.0.1-SNAPSHOT-fat.jar
```

Run manually (use same args as above):

```bash
java -jar target/spice-labs-cli-0.0.1-SNAPSHOT-fat.jar --version
```

Or run with Maven exec:

```bash
mvn exec:java -Dexec.mainClass="io.spicelabs.cli.SpiceLabsCLI" \
  -Dexec.args="--command=run --input=./my-dir --output=./out-dir --log-level=all"
```

---

### 🚀 Releasing

1. **Create a GitHub Release**
   Use a tag like `v0.2.0`. This triggers GitHub Actions to:

   - Build the JAR
   - Publish to GitHub Packages
   - Push Docker image to GHCR
   - Upload artifacts to Maven Central (automated)

2. **Monitor Maven Central** (optional)
   Visit [https://central.sonatype.com](https://central.sonatype.com) → Deployments
   Propagation takes ~40 minutes.

3. **Verify the JAR**

```bash
mvn dependency:get \
  -Dartifact=io.spicelabs:spice-labs-cli:jar:0.2.0
```

---

## 📦 Repository

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo) — ADG surveyor used by this CLI
- [`ginger-j`](https://github.com/spice-labs-inc/ginger-j) — secure uploader used by this CLI
- [`spice-labs-cli`](https://github.com/spice-labs-inc/spice-labs-cli) — this CLI

---

## ⚖️ License

Apache License 2.0. See [`LICENSE`](LICENSE).