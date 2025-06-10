#!/bin/bash
set -euo pipefail

# Environment variable to bypass Docker and use the JVM-based entry script
# If SPICE_LABS_CLI_USE_JVM=1, this script will invoke ./spice-labs.sh directly.
USE_JVM="${SPICE_LABS_CLI_USE_JVM:-0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DOCKER_IMAGE="ghcr.io/spice-labs-inc/spice-labs-cli:latest"
ci_mode=false
pull_latest=true

# Defaults
command="run"
input=""
output=""
extra_args=()

show_help() {
  cat <<EOF
Usage: spice --command <cmd> [--input <path>] [--output <path>] [--ci] [--quiet|--verbose] [--no-pull]

Commands:
  run                      Scan artifacts and upload ADGs (default)
  scan-artifacts           Generate ADGs only
  upload-adgs              Upload existing ADGs
  upload-deployment-events Upload deployment events from stdin

Options:
  --command CMD            One of: run, scan-artifacts, upload-adgs, upload-deployment-events
  --input PATH             Path to input directory or file
  --output PATH            Path for output (only needed for scan-artifacts or run)
  --ci                     Run in CI/CD mode (non-interactive, implies --quiet unless overridden)
  --quiet                  Suppress output
  --verbose                Enable detailed logging
  --no-pull                Don't pull the latest Docker image
  --help                   Show this help
EOF
}

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --command)
      command="$2"
      shift 2
      ;;
    --input)
      input="$2"
      shift 2
      ;;
    --output)
      output="$2"
      shift 2
      ;;
    --ci)
      ci_mode=true
      shift
      ;;
    --no-pull)
      pull_latest=false
      shift
      ;;
    --quiet)
      shift
      ;;
    --verbose)
      extra_args+=("$1")
      shift
      ;;
    --help)
      show_help
      exit 0
      ;;
    *)
      extra_args+=("$1")
      shift
      ;;
  esac
done

# Validate SPICE_PASS except for scan-artifacts
if [[ "$command" != "scan-artifacts" && -z "${SPICE_PASS:-}" ]]; then
  echo "❌ SPICE_PASS environment variable must be set for command '$command'"
  exit 1
fi

# Default input to current directory if not set
if [[ -z "$input" ]]; then
  input="$PWD"
fi

# For commands that write to output, ensure directory exists and is writable
needs_output=false
if [[ "$command" == "scan-artifacts" || "$command" == "run" ]]; then
  needs_output=true
  if [[ -z "$output" ]]; then
    output="$(mktemp -d)"
  else
    mkdir -p "$output"
  fi
  if [[ ! -w "$output" ]]; then
    echo "❌ Output directory '$output' is not writable. Please fix permissions and try again."
    exit 1
  fi
fi

# In CI mode, default to --quiet if neither verbose nor quiet was provided
if [[ "$ci_mode" == true ]]; then
  if ! printf '%s\n' "${extra_args[@]}" | grep -qE -- "--verbose|--quiet"; then
    extra_args+=("--quiet")
  fi
fi

# If USE_JVM=1, validate additional paths and run the local entry script
if [[ "$USE_JVM" == "1" ]]; then
  if [[ -z "${SPICE_LABS_GOAT_RODEO_PATH:-}" || -z "${SPICE_LABS_GINGER_PATH:-}" ]]; then
    echo "❌ When SPICE_LABS_CLI_USE_JVM=1, both SPICE_LABS_GOAT_RODEO_PATH and SPICE_LABS_GINGER_PATH must be set."
    exit 1
  fi

  # Build argument list for the JVM entry script
  args=(--command "$command" --input "$input")
  if [[ "$needs_output" == true ]]; then
    args+=(--output "$output")
  fi
  # Append any extra flags (--quiet/--verbose, etc.)
  args+=("${extra_args[@]}")

  echo "🚀 Running spice (JVM mode) with command: $command"
  echo "📁 Input:  $input"
  [[ "$needs_output" == true ]] && echo "📁 Output: $output"

  # Invoke the local entry script (assumes it's executable and in the same directory)
  "${SCRIPT_DIR}/spice-labs.sh" "${args[@]}" "${extra_args[@]}" \
  > >(sed 's/\bspice-labs\.sh\b/spice/g' | sed '/::spice-labs-cli-help-start::/,/::spice-labs-cli-help-end::/d') \
  2> >(tee /dev/stderr) || {
    echo
    echo "❌ The Spice Labs CLI (Docker mode) failed."
    show_help
    exit 1
  }

  exit 0
fi

# ---------- Docker-based logic below ----------

# Pull image unless skipped
if [[ "$pull_latest" == true ]]; then
  docker pull "$DOCKER_IMAGE" > /dev/null
fi

# Prepare container args
args=(--command "$command" --input /mnt/input)
if [[ "$needs_output" == true ]]; then
  args+=(--output /mnt/output)
fi

# Prepare Docker flags and mounts
volumes=(-v "$input:/mnt/input")
[[ "$needs_output" == true ]] && volumes+=(-v "$output:/mnt/output")

flags=(-e SPICE_PASS --rm)
[[ "$command" == "upload-deployment-events" ]] && flags+=(-i)

echo "🚀 Running spice-labs-cli (Docker mode) with command: $command"
echo "📁 Mounting input:  $input"
[[ "$needs_output" == true ]] && echo "📁 Mounting output: $output"

# Run Docker container and filter output
docker run "${flags[@]}" "${volumes[@]}" "$DOCKER_IMAGE" "${args[@]}" "${extra_args[@]}" \
  > >(sed 's/\bspice-labs\.sh\b/spice/g' | sed '/::spice-labs-cli-help-start::/,/::spice-labs-cli-help-end::/d') \
  2> >(tee /dev/stderr) || {
    echo
    echo "❌ The Spice Labs CLI (Docker mode) failed."
    show_help
    exit 1
  }
