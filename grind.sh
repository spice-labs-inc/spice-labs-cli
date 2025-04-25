#!/bin/bash
set -euo pipefail

# Constants
ADG_MIME_TYPE="application/vnd.cc.bigtent"
DEPLOYMENT_EVENTS_MIME_TYPE="application/vnd.info.deployevent"
SPICE_PASS_ENV_VAR="SPICE_PASS"

# Globals
command="run"
input_dir=""
output_dir=""
verbose=false
quiet=false
temp_dir=""
spinner_pid=""

#############################################
# Help                                      #
#############################################
show_help() {
  cat << EOF

Usage: grind.sh --command <cmd> [--input <path>] [--output <path>] [--verbose|--quiet]

Commands:
  run                     Scan artifacts and upload ADGs (default)
  scan-artifacts          Generate ADGs from input directory (debug use only)
  upload-adgs             Upload pre-generated ADGs from input directory (debug use only)
  upload-deployment-events Upload deployment log events from stdin

Options:
  --command CMD           One of: run, scan-artifacts, upload-adgs, upload-deployment-events
  --input PATH            Input file or directory (required unless run mode uses current directory)
  --output PATH           Output directory (required for scan-artifacts)
  --verbose               Log full command output and stderr
  --quiet                 Suppress all output
  --help                  Show this help message

Note:
  The Spice Pass must be set via the environment variable $SPICE_PASS_ENV_VAR.

Deployment Event Format:
  Each event must include:
    - identifier (string)
    - system (string)
    - artifact (string)
    - start_time and/or end_time (at least one required)
  Accepted input format: JSON array or newline-delimited JSON (JSONL)
EOF
}

#############################################
# Utilities                                 #
#############################################

fail_if_unset() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Error: Environment variable '$name' is not set"
    exit 1
  fi
}

check_binaries() {
  for bin in /opt/docker/bin/goatrodeo /usr/bin/ginger /usr/bin/jq; do
    [[ -x "$bin" ]] || {
      echo "Error: required binary $bin not found or not executable"
      exit 1
    }
  done
}

start_spinner() {
  if [[ "$quiet" == false && -t 1 ]]; then
    local chars
    chars=( "|" "/" "-" "\\" )
    (
      while true; do
        for c in "${chars[@]}"; do
          printf "\r⏳ %s" "$c"
          sleep 0.2
        done
      done
    ) &
    spinner_pid=$!
  fi
}

stop_spinner() {
  if [[ -n "${spinner_pid:-}" ]]; then
    kill "$spinner_pid" 2>/dev/null || true
    wait "$spinner_pid" 2>/dev/null || true
    echo -ne "\r\033[K"
    spinner_pid=""
  fi
}

run_cmd() {
  if [[ "$verbose" == true ]]; then
    echo "+ $*"
    "$@"
  elif [[ "$quiet" == true ]]; then
    "$@" > /dev/null 2>&1
  else
    "$@" > /dev/null
  fi
}

cleanup() {
  stop_spinner
  if [[ -n "$temp_dir" && -d "$temp_dir" ]]; then
    rm -rf "$temp_dir"
  fi
}
trap cleanup EXIT

#############################################
# Actions                                   #
#############################################

scan_artifacts() {
  [[ -z "$input_dir" || -z "$output_dir" ]] && {
    echo "Error: --input and --output are required for scan-artifacts"
    exit 1
  }

  if run_cmd /opt/docker/bin/goatrodeo -b "$input_dir" -o "$output_dir"; then
    [[ "$quiet" == false ]] && echo "✅ Scan successful"
  else
    echo "❌ Scan failed"
    exit 1
  fi
}

upload_adgs() {
  [[ -z "$input_dir" ]] && {
    echo "Error: --input is required for upload-adgs"
    exit 1
  }

  [[ "$quiet" == false ]] && echo "📦 Uploading... this may take some time."
  start_spinner
  if run_cmd /usr/bin/ginger -p "$input_dir" -j "$SPICE_PASS" -m "$ADG_MIME_TYPE"; then
    stop_spinner
    [[ "$quiet" == false ]] && echo "✅ Upload successful"
  else
    stop_spinner
    echo "❌ Upload failed"
    exit 1
  fi
}

upload_deployment_events() {
  fail_if_unset "$SPICE_PASS_ENV_VAR"

  [[ "$quiet" == false ]] && echo "📦 Uploading deployment events... this may take some time."
  start_spinner

  temp_json="$(mktemp "/tmp/deploy_events_XXXXXX").json"

  #pull the passed in info into a variable to allow multi-step processing
  temp_cache=$(cat)

  if jq -e type <<< "$temp_cache" | grep -q array; then    
    echo $temp_cache > "$temp_json"
  else
    if ! jq -s <<< "$temp_cache" > "$temp_json"; then
      stop_spinner
      echo "❌ Failed to convert input to JSON"
      rm -f "$temp_json"
      exit 1
    fi
  fi

  if [[ "$verbose" == true ]]; then
    /usr/bin/ginger -p "$temp_json" -j "$SPICE_PASS" -m "$DEPLOYMENT_EVENTS_MIME_TYPE"
  elif [[ "$quiet" == true ]]; then
    /usr/bin/ginger -p "$temp_json" -j "$SPICE_PASS" -m "$DEPLOYMENT_EVENTS_MIME_TYPE" > /dev/null 2>&1
  else
    /usr/bin/ginger -p "$temp_json" -j "$SPICE_PASS" -m "$DEPLOYMENT_EVENTS_MIME_TYPE" 2>&1 | grep 'Important! SHA256 hash of bundle is' | sed 's/^.*Important/Important/'
  fi

  rm -f "$temp_json"
  stop_spinner
  [[ "$quiet" == false ]] && echo "✅ Deployment events upload complete"
}

run_combined() {
  fail_if_unset "$SPICE_PASS_ENV_VAR"
  [[ -z "$input_dir" ]] && input_dir="$(pwd)"
  temp_dir=$(mktemp -d)

  if [[ "$verbose" == true ]]; then
    /opt/docker/bin/goatrodeo -b "$input_dir" -o "$temp_dir"
  elif [[ "$quiet" == true ]]; then
    /opt/docker/bin/goatrodeo -b "$input_dir" -o "$temp_dir" > /dev/null 2>&1
  else
    /opt/docker/bin/goatrodeo -b "$input_dir" -o "$temp_dir" > /dev/null 2>&1
  fi

  [[ "$quiet" == false ]] && echo "✅ Scan complete"
  [[ "$quiet" == false ]] && echo "📦 Uploading... this may take some time."
  start_spinner
  if [[ "$verbose" == true ]]; then
    /usr/bin/ginger -p "$temp_dir" -j "$SPICE_PASS" -m "$ADG_MIME_TYPE"
  elif [[ "$quiet" == true ]]; then
    /usr/bin/ginger -p "$temp_dir" -j "$SPICE_PASS" -m "$ADG_MIME_TYPE" > /dev/null 2>&1
  else
    /usr/bin/ginger -p "$temp_dir" -j "$SPICE_PASS" -m "$ADG_MIME_TYPE" 2>&1 | grep 'Important! SHA256 hash of bundle is' | sed 's/^.*Important/Important/'
  fi
  stop_spinner
  [[ "$quiet" == false ]] && echo "✅ Upload complete"
}

#############################################
# Parse Arguments                           #
#############################################

while [[ $# -gt 0 ]]; do
  case $1 in
    --command)
      command="$2"
      shift 2
      ;;
    --input)
      input_dir="$2"
      shift 2
      ;;
    --output)
      output_dir="$2"
      shift 2
      ;;
    --verbose)
      verbose=true
      shift
      ;;
    --quiet)
      quiet=true
      shift
      ;;
    --help)
      show_help
      exit 0
      ;;
    *)
      echo "Unknown option: '$1'"
      show_help
      exit 1
      ;;
  esac
done

if [[ "$verbose" == true && "$quiet" == true ]]; then
  echo "Error: --verbose and --quiet cannot be used together"
  exit 1
fi

fail_if_unset "$SPICE_PASS_ENV_VAR"
check_binaries

if [[ "$verbose" == true ]]; then
  echo "Executing command: $command"
elif [[ "$quiet" == false ]]; then
  echo "🚀 Running Spice Grinder..."
fi

case "$command" in
  scan-artifacts)
    scan_artifacts
    ;;
  upload-adgs)
    upload_adgs
    ;;
  upload-deployment-events)
    upload_deployment_events
    ;;
  run)
    run_combined
    ;;
  *)
    echo "Error: Invalid command '$command'"
    show_help
    exit 1
    ;;
esac

unset "${SPICE_PASS_ENV_VAR}"