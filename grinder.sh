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

if [[ -z "${SPICE_PASS:-}" ]]; then
  echo "Error: SPICE_PASS environment variable must be set"
  exit 1
fi

if [[ "$pull_latest" == true ]]; then
  docker pull "$DOCKER_IMAGE" >/dev/null
fi

args=(--command "$command")
[[ -z "$input" ]] && input="$PWD"
args+=(--input /mnt/input)
[[ -n "$output" ]] && args+=(--output /mnt/output)

# If CI mode, default to --quiet unless overridden by user
if [[ "$ci_mode" == true && ! " ${extra_args[*]} " =~ " --verbose " && ! " ${extra_args[*]} " =~ " --quiet " ]]; then
  args+=(--quiet)
fi

volumes=(-v "$PWD:/mnt/host")
[[ -n "$input" ]] && volumes+=(-v "$input:/mnt/input")
[[ -n "$output" ]] && volumes+=(-v "$output:/mnt/output")

# Always pass through SPICE_PASS
flags=(-e SPICE_PASS --rm)

# If stdin is needed (events)
[[ "$command" == "upload-deployment-events" ]] && flags+=(-i)

echo "🚀 Running grinder with command: $command"

docker run "${flags[@]}" "${volumes[@]}" "$DOCKER_IMAGE" "${args[@]}" "${extra_args[@]}"