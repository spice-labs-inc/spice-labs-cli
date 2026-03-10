# 🔩 Spice Labs Surveyor CLI

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/spice-labs-cli?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/spice-labs-cli)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/spice-labs-cli?label=GitHub%20Release)](https://github.com/spice-labs-inc/spice-labs-cli/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/spice-labs-cli/packages/)
[![Docker Image Version](https://img.shields.io/docker/v/spicelabs/spice-labs-cli?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/spice-labs-cli)

The **Spice Labs Surveyor CLI** is a JVM-based and containerized CLI that surveys software artifacts to generate encrypted **Artifact Dependency Graphs (ADGs)** and uploads them securely to Spice Labs.

---

# 🚀 Quick Start

## ⚡ Prerequisites

Docker must be installed and running on your system.

Install Docker:

https://docs.docker.com/get-docker/

---

## 🧪 Recommended Installer

### macOS / Linux

```bash
curl -sSLf https://install.spicelabs.io | bash
```

### Windows PowerShell

```powershell
irm -UseBasicParsing -Uri https://install.spicelabs.io | iex
```

After installation, ensure the CLI is available in your `PATH`.

---

## Basic Usage

Set your `SPICE_PASS` environment variable.

Your Spice Pass can be downloaded from the Spice Labs project dashboard settings page.

Example:

```bash
export SPICE_PASS=your_spice_pass
```

Run a survey:

```bash
spice --tag=my-module-name
```

Specify an input path (defaults to the current directory):

```bash
spice --input=path/to/my-dir --tag=my-module-name
```

`--tag` is required.
It is used to group surveys of the same system over time.

---

# ⚙️ CLI Usage

```
spice [OPTIONS]
```

View the full command reference:

```bash
spice --help
```

> **Note**
>
> The output of `spice --help` is the authoritative source of truth for CLI options.
> The documentation below summarizes common usage but may not list every option.

---

## Main Options

| Option | Description |
|------|-------------|
| `--command=<command>` | Operation to perform |
| `--input=<path>` | Input directory |
| `--output=<path>` | Output directory |
| `--log-file=<path>` | Write console output to a log file |
| `--log-level=<level>` | Logging verbosity |
| `--threads=<n>` | Number of worker threads |
| `--max-records=<n>` | Maximum records processed per batch |
| `--tag=<tag>` | Tag top-level artifacts |
| `--tag-json=<json>` | Attach JSON metadata to tags |
| `--ginger-args=<args>` | Additional Ginger uploader arguments |
| `--goat-rodeo-args=<args>` | Additional Goat Rodeo surveyor arguments |
| `--ci` | Enable CI mode |
| `--use-static-metadata` | Augment Goat Rodeo metadata |
| `-h`, `--help` | Show help |
| `-V`, `--version` | Print version |

---

## Commands

The `--command` option supports the following values:

| Command | Description |
|------|-------------|
| `run` | Default command. Surveys artifacts and uploads ADGs |
| `survey-artifacts` | Survey artifacts only |
| `upload-adgs` | Upload ADGs only |
| `decode-spice-pass` | Decode a Spice Pass |

If `--command` is omitted, the default is:

```
run
```

---

## Log Levels

Valid values for `--log-level`:

```
all
trace
debug
info
warn
error
fatal
off
```

Default:

```
info
```

---

# 🐳 Docker Usage (Advanced)

Run the CLI container directly:

```bash
docker run --rm \
  -e SPICE_PASS=... \
  -v "$PWD/input:/mnt/input" \
  -v "$PWD/output:/mnt/output" \
  spicelabs/spice-labs-cli \
  --command=run \
  --input=/mnt/input \
  --output=/mnt/output
```

Example mounting local directories:

```
-v "/home/<username>/testdata:/mnt/input"
```

This mounts `/home/<username>/testdata` from your host system as `/mnt/input` inside the container.

---

### Upload Only

```bash
docker run --rm \
  -e SPICE_PASS=... \
  -v "$PWD/output:/mnt/input" \
  spicelabs/spice-labs-cli \
  --command=upload-adgs \
  --input=/mnt/input
```

---

# 📦 Environment Variables

| Variable | Description | Default |
|---------|-------------|--------|
| `SPICE_PASS` | Required for upload commands. JWT token for Spice Labs authentication | none |
| `SPICE_LABS_CLI_USE_JVM` | Run the CLI using the local JVM instead of Docker | `0` |
| `SPICE_LABS_CLI_JAR` | Path to the CLI JAR when using JVM mode | `/opt/spice-labs-cli/spice-labs-cli.jar` |
| `SPICE_LABS_JVM_ARGS` | Custom JVM flags | `-XX:MaxRAMPercentage=75` |
| `SPICE_IMAGE` | Docker image used by the launcher | `spicelabs/spice-labs-cli` |
| `SPICE_IMAGE_TAG` | Docker image tag | `latest` |
| `SPICE_LABS_CLI_SKIP_PULL` | Skip `docker pull` before running (`1` to skip) | `0` |

---

# 🧩 GitHub Actions

Use the official Spice Labs Surveyor GitHub Action:

https://github.com/spice-labs-inc/action-spice-labs-surveyor

Example workflow:

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

# 🛠 Maintainers

## Build Locally

Install JDK 21+ and Maven 3.6+.

Set your Spice Pass:

```bash
export SPICE_PASS=your_spice_pass
```

Clone the repository:

```bash
git clone https://github.com/spice-labs-inc/spice-labs-cli.git
cd spice-labs-cli
```

Build with Maven:

```bash
mvn clean install
```

The fat JAR will be produced at:

```
target/spice-labs-cli-<version>-fat.jar
```

Run manually:

```bash
java -jar target/spice-labs-cli-<version>-fat.jar --version
```

Or run using Maven exec:

```bash
mvn exec:java \
  -Dexec.mainClass="io.spicelabs.cli.SpiceLabsCLI" \
  -Dexec.args="--command=run --input=./my-dir --output=./out-dir --log-level=all"
```

---

## 🚀 Releasing

1. Create a GitHub Release using a tag such as `v0.2.0`.

This triggers GitHub Actions to:

- Build the JAR
- Publish to GitHub Packages
- Push the Docker image
- Publish to Maven Central

2. Monitor Maven Central propagation:

https://central.sonatype.com

3. Verify the artifact:

```bash
mvn dependency:get \
  -Dartifact=io.spicelabs:spice-labs-cli:jar:<version>
```

---

# 🔄 Documentation Maintenance

CLI behavior evolves over time. To keep documentation accurate:

1. After any CLI option change, update this README if necessary.
2. Always verify behavior with:

```bash
spice --help
```

3. The `spice --help` output should always be treated as the authoritative reference.

---

# 📦 Repository

Maintained by Spice Labs.

- https://github.com/spice-labs-inc/goatrodeo
- https://github.com/spice-labs-inc/ginger-j
- https://github.com/spice-labs-inc/spice-labs-cli

---

# ⚖️ License

Apache License 2.0. See `LICENSE`.

---