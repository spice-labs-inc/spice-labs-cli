# The Spice Labs CLI

The Spice Labs CLI is a containerized CLI tool for scanning your systems and uploading results to a Spice Labs server.  
It wraps two tools:
- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo): generates ADGs (Artifact Dependency Graphs)
- [`ginger`](https://github.com/spice-labs-inc/ginger): uploads ADGs or deployment events

---

## 🚀 Quick Start

### 🔹 Using `spice-labs-cli.sh` (recommended)

[`spice-labs-cli.sh`](spice-labs-cli.sh) is a lightweight wrapper that runs the container for you.  
It detects your environment, mounts input/output directories, and passes arguments to `spicelabs.sh`.

```bash
SPICE_PASS=... ./spice-labs-cli.sh --command run --input ./my-artifacts
```

### 🔹 Usage Modes

```bash
./spice-labs-cli.sh [--command <cmd>] [--input <path>] [--output <path>] [--ci] [--quiet|--verbose]
```

| Command                      | Description                                     |
|------------------------------|-------------------------------------------------|
| `run` *(default)*            | Scan and upload in one step                     |
| `scan-artifacts`             | Run `goatrodeo` only                            |
| `upload-adgs`                | Upload a pre-scanned ADG directory              |
| `upload-deployment-events`   | Upload JSON deployment event logs from stdin   |

#### Options

- `--input` : path to scan or upload (defaults to `./`)
- `--output`: output path (for scan only)
- `--quiet` / `--verbose`: control logging
- `--ci`    : CI/CD mode (auto-silent unless overridden)
- `SPICE_PASS`: required environment variable for authentication

### 🔹 Examples

Scan and upload:
```bash
SPICE_PASS=... ./spice-labs-cli.sh --command run --input ./src
```

CI usage:
```bash
SPICE_PASS=... ./spice-labs-cli.sh --command upload-adgs --input ./out --ci
```

Upload deployment events:
```bash
cat deploy.json | SPICE_PASS=... ./spice-labs-cli.sh --command upload-deployment-events
```

---

## 🐳 Docker-Only Usage

You can also run everything directly using Docker and `spicelabs.sh` inside the container.

```bash
docker run --rm \
  -e SPICE_PASS=... \
  -v "$PWD/input:/mnt/input" \
  -v "$PWD/output:/mnt/output" \
  spicelabs/spice-labs-cli:latest \
  --command run --input /mnt/input --output /mnt/output
```

Upload only:
```bash
docker run --rm -e SPICE_PASS=... -v "$PWD/output:/mnt/input" \
  spicelabs/spice-labs-cli:latest \
  --command upload-adgs --input /mnt/input
```

Upload deployment events:
```bash
cat deploy.json | docker run -i --rm -e SPICE_PASS=... \
  spicelabs/spice-labs-cli:latest --command upload-deployment-events
```

---

## 📦 Repository

This tool is maintained by [Spice Labs](https://github.com/spice-labs-inc).

- [`goatrodeo`](https://github.com/spice-labs-inc/goatrodeo)
- [`ginger`](https://github.com/spice-labs-inc/ginger)
- [`spice-labs-cli`](https://github.com/spice-labs-inc/spice-labs-cli)

---

## ⚖️ License

Licensed under the Apache License 2.0. See [`LICENSE`](LICENSE) for details.