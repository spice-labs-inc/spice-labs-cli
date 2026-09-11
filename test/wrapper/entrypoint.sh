#!/bin/sh
# Test container entrypoint for spice wrapper script tests.
# Echoes all received args and env vars in a parseable format,
# writes marker files for volume-mount verification.
#
# Note: the bash wrapper passes --user $(id -u):$(id -g), so this
# entrypoint may run as a non-root user. All writes must handle
# permission errors gracefully.

# `path-manifest --config <file>`: the stand-in for the CLI's own manifest command.
# Answered only when asked about a config file, so the no-config tests keep
# exercising an image that does not know the command at all. Each line of the file
# is taken as a path — the test's stand-in for the TOML parser — and the file is
# read where the wrapper mounted it, which is what proves the mount.
if [ "${1:-}" = "path-manifest" ] && [ "${2:-}" = "--config" ] && [ -r "${3:-}" ]; then
  cat <<'MANIFEST'
# spice-path-manifest 1
V 1
R /
R /etc
R /opt
R /usr
R /var
C spice
C spice/survey
C spice/survey/inventory
O spice --config value path create=parent
P spice/survey/inventory 0 value
P spice/survey/inventory 1 value path exists

# spice-config-paths 1
MANIFEST
  while IFS= read -r line; do
    [ -n "$line" ] && echo "P $line"
  done < "$3"
  exit 0
fi

echo "===SPICE_TEST_BEGIN==="

# Echo each arg on its own line
for arg in "$@"; do
  echo "ARG:${arg}"
done

# Echo every mountpoint, so a test can assert a bind mount exists rather than
# infer it from what the container managed to write there.
awk '{print "MOUNT:" $2}' /proc/mounts 2>/dev/null

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
# The wrapper mounts the user's current directory at its own path and makes it the
# container's working directory, so a relative write lands there on the host.
default_out="$(pwd)"
echo "DEFAULT" > "$default_out/default-marker.txt" 2>/dev/null && echo "WROTE:${default_out}/default-marker.txt"

# ANSI-colored output for log-file stripping tests
printf '\033[32mCOLORED:green-text\033[0m\n'
printf '\033[31mCOLORED:red-text\033[0m\n'

# Stderr output for log-file capture tests
echo "STDERR:test-error-output" >&2

echo "===SPICE_TEST_END==="

# Support configurable exit code for exit-code propagation tests
exit "${TEST_EXIT_CODE:-0}"
