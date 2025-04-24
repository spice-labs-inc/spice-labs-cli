#!/bin/bash
set -euo pipefail

DOCKER_IMAGE="ghcr.io/spice-labs-inc/grinder:latest"
ci_mode=false
pull_latest=true

# Defaults
command="run"
input=""
output=""
extra_args=()

show_help() {
  cat <<EOF
Usage: grinder.sh --command <cmd> [--input <path>] [--output <path>] [--ci] [--quiet|--verbose]

Commands:
  run                     Scan artifacts and upload ADGs (default)
  scan-artifacts          Generate ADGs only
  upload-adgs             Upload existing ADGs
  upload-deployment-events Upload deployment events from stdin

Options:
  --command CMD           One of: run, scan-artifacts, upload-adgs, upload-deployment-events
  --input PATH            Path to input directory or file
  --output PATH           Path for output (only needed for scan-artifacts)
  --ci                    Run in CI/CD mode (non-interactive, implies --quiet unless overridden)
  --quiet                 Suppress output
  --verbose               Enable detailed logging
  --no-pull               Don't pull the latest Docker image
  --help                  Show this help
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
    --quiet|--verbose)
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

# Validate Spice Pass
if [[ -z "${SPICE_PASS:-}" ]]; then
  echo "❌ SPICE_PASS environment variable must be set"
  exit 1
fi

# Pull image unless skipped
if [[ "$pull_latest" == true ]]; then
  docker pull "$DOCKER_IMAGE" > /dev/null
fi

# Prepare args
args=(--command "$command")

# Default to current dir as input if not set
[[ -z "$input" ]] && input="$PWD"
args+=(--input /mnt/input)

# Ensure output directory exists for commands that write to it
if [[ "$command" == "scan-artifacts" || "$command" == "run" ]]; then
  if [[ -z "$output" ]]; then
    output="$(mktemp -d)"
  else
    mkdir -p "$output"
  fi
  if [[ ! -w "$output" ]]; then
    echo "❌ Output directory '$output' is not writable. Please fix permissions and try again."
    exit 1
  fi
  args+=(--output /mnt/output)
fi

# Default to --quiet in CI unless overridden
if [[ "$ci_mode" == true && ! " ${extra_args[*]} " =~ " --verbose " && ! " ${extra_args[*]} " =~ " --quiet " ]]; then
  args+=(--quiet)
fi

# Prepare Docker flags
volumes=(-v "$input:/mnt/input")
[[ -n "$output" ]] && volumes+=(-v "$output:/mnt/output")

flags=(-e SPICE_PASS --rm)
[[ "$command" == "upload-deployment-events" ]] && flags+=(-i)

echo "🚀 Running grinder with command: $command"
echo "📁 Mounting input:  $input"
[[ -n "$output" ]] && echo "📁 Mounting output: $output"

# Run and filter output
docker run "${flags[@]}" "${volumes[@]}" "$DOCKER_IMAGE" "${args[@]}" "${extra_args[@]}" \
  > >(sed 's/\bgrind\.sh\b/grinder.sh/g' | sed '/::grinder-help-start::/,/::grinder-help-end::/d') \
  2> >(tee /dev/stderr) || {
    echo
    echo "❌ Grinder failed."
    show_help
    exit 1
  }


