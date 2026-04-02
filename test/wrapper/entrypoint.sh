#!/bin/sh
# Test container entrypoint for spice wrapper script tests.
# Echoes all received args and env vars in a parseable format,
# writes marker files for volume-mount verification.
#
# Note: the bash wrapper passes --user $(id -u):$(id -g), so this
# entrypoint may run as a non-root user. All writes must handle
# permission errors gracefully.

echo "===SPICE_TEST_BEGIN==="

# Echo each arg on its own line
for arg in "$@"; do
  echo "ARG:${arg}"
done

# Echo env vars the wrapper is responsible for passing
echo "ENV:SPICE_PASS=${SPICE_PASS:-}"
echo "ENV:SPICE_LABS_JVM_ARGS=${SPICE_LABS_JVM_ARGS:-}"

# If --output is in args, write a marker file there
prev=""
for arg in "$@"; do
  if [ "$prev" = "--output" ]; then
    mkdir -p "$arg" 2>/dev/null
    echo "OK" > "$arg/marker.txt" 2>/dev/null && echo "WROTE:${arg}/marker.txt"
    break
  fi
  case "$arg" in
    --output=*)
      dir="${arg#--output=}"
      mkdir -p "$dir" 2>/dev/null
      echo "OK" > "$dir/marker.txt" 2>/dev/null && echo "WROTE:${dir}/marker.txt"
      break
      ;;
  esac
  prev="$arg"
done

# Write to the default output location inside the container.
# The real CLI image runs as root, so the default path is /root/.
# The wrapper mounts the host dir to /root/.spicelabs/surveyor.
default_out="/root/.spicelabs/surveyor"
mkdir -p "$default_out" 2>/dev/null
echo "DEFAULT" > "$default_out/default-marker.txt" 2>/dev/null && echo "WROTE:${default_out}/default-marker.txt"

# ANSI-colored output for log-file stripping tests
printf '\033[32mCOLORED:green-text\033[0m\n'
printf '\033[31mCOLORED:red-text\033[0m\n'

# Stderr output for log-file capture tests
echo "STDERR:test-error-output" >&2

echo "===SPICE_TEST_END==="

# Support configurable exit code for exit-code propagation tests
exit "${TEST_EXIT_CODE:-0}"
