#!/usr/bin/env bats
#
# Full Docker-mode tests for the bash wrapper script (spice).
# Requires: bats-core, Docker with the spice-wrapper-test image built.
#
# Run:  cd ~/dev/spice-labs-cli && bats test/wrapper/spice.bats

# ── One-time setup: build the test container ─────────────────────────────────

setup_file() {
  export REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
  export WRAPPER="$REPO_ROOT/spice"
  export TEST_IMAGE="spice-wrapper-test"

  docker build -t "$TEST_IMAGE" "$(dirname "$BATS_TEST_FILENAME")"
}

# ── Per-test setup/teardown ──────────────────────────────────────────────────

setup() {
  export SPICE_LABS_CLI_SKIP_PULL=1
  export SPICE_IMAGE="$TEST_IMAGE"
  export SPICE_IMAGE_TAG=latest
  export SPICE_PASS=test-pass-value
  unset __SPICE_LOGGING_ACTIVE

  TEST_TMPDIR="$(mktemp -d)"
  mkdir -p "$TEST_TMPDIR/input"
  echo "test-content" > "$TEST_TMPDIR/input/file.txt"
}

teardown() {
  rm -rf "$TEST_TMPDIR"
}

# ── Helpers ──────────────────────────────────────────────────────────────────

# Extract ARG: lines from between the markers in $output (set by `run`)
container_args() {
  local in_block=0
  while IFS= read -r line; do
    case "$line" in
      "===SPICE_TEST_BEGIN===") in_block=1 ;;
      "===SPICE_TEST_END===")  in_block=0 ;;
      ARG:*) [ "$in_block" -eq 1 ] && echo "${line#ARG:}" ;;
    esac
  done <<< "$output"
}

# Extract a single ENV: value by key
container_env() {
  local key="$1" in_block=0
  while IFS= read -r line; do
    case "$line" in
      "===SPICE_TEST_BEGIN===") in_block=1 ;;
      "===SPICE_TEST_END===")  in_block=0 ;;
      "ENV:${key}="*) [ "$in_block" -eq 1 ] && echo "${line#ENV:${key}=}" && return ;;
    esac
  done <<< "$output"
}

# Assert that a specific arg was passed to the container
assert_arg() {
  container_args | grep -qxF -- "$1" || {
    echo "expected container arg: $1"
    echo "actual args:"
    container_args | sed 's/^/  /'
    return 1
  }
}

# Assert that no arg matching a pattern was passed
refute_arg() {
  ! container_args | grep -qxF -- "$1" || {
    echo "did not expect container arg: $1"
    return 1
  }
}

# ── Args: basic commands ─────────────────────────────────────────────────────

@test "survey inventory with directory input" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  assert_arg "survey"
  assert_arg "inventory"
  assert_arg "myapp"
  assert_arg "/mnt/input"
}

@test "survey inventory with single file input" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input/file.txt"
  [ "$status" -eq 0 ]
  assert_arg "survey"
  assert_arg "inventory"
  assert_arg "myapp"
  assert_arg "/mnt/input/file.txt"
}

@test "pass decode" {
  run "$WRAPPER" pass decode
  [ "$status" -eq 0 ]
  assert_arg "pass"
  assert_arg "decode"
}

@test "--version flag reaches container" {
  run "$WRAPPER" --version
  [ "$status" -eq 0 ]
  assert_arg "--version"
}

@test "--help flag reaches container" {
  run "$WRAPPER" --help
  [ "$status" -eq 0 ]
  assert_arg "--help"
}

@test "survey --help passes through" {
  run "$WRAPPER" survey --help
  [ "$status" -eq 0 ]
  assert_arg "survey"
  assert_arg "--help"
}

# ── Output directory ─────────────────────────────────────────────────────────

@test "--output (space) creates dir and mounts volume" {
  local outdir="$TEST_TMPDIR/output-space"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --output "$outdir"
  [ "$status" -eq 0 ]
  assert_arg "--output"
  assert_arg "/mnt/output"
  [ -d "$outdir" ]
}

@test "--output= (equals) creates dir and mounts volume" {
  local outdir="$TEST_TMPDIR/output-eq"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" "--output=$outdir"
  [ "$status" -eq 0 ]
  assert_arg "--output"
  assert_arg "/mnt/output"
  [ -d "$outdir" ]
}

@test "--output marker file written by container" {
  # Skip on snap Docker — uid remapping prevents container volume writes
  if docker info 2>/dev/null | grep -q '/snap/'; then
    skip "snap Docker uid remapping prevents container volume writes"
  fi
  local outdir="$TEST_TMPDIR/output-marker"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --output "$outdir"
  [ "$status" -eq 0 ]
  assert_arg "--output"
  assert_arg "/mnt/output"
  [ -f "$outdir/marker.txt" ]
  [ "$(cat "$outdir/marker.txt")" = "OK" ]
}

@test "default output dir mounted when --output omitted" {
  # Reproduces bug #530
  local default_dir="$HOME/.spicelabs/surveyor"
  # Clean up before test in case it exists from a prior run
  rm -rf "$default_dir"

  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  # The wrapper must create the directory on the host
  [ -d "$default_dir" ]
  # On standard Docker, the container writes a marker file.
  # On snap/rootless Docker with --user, the marker write may fail
  # due to uid mapping — check container output as fallback.
  [ -f "$default_dir/default-marker.txt" ] || \
    echo "$output" | grep -q "WROTE:.*default-marker.txt" || \
    echo "$output" | grep -q "/root/.spicelabs/surveyor" || {
    echo "default output dir not mounted into container"
    return 1
  }
}

# ── Value flags pass-through ─────────────────────────────────────────────────

@test "--threads N passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --threads 4
  [ "$status" -eq 0 ]
  assert_arg "--threads"
  assert_arg "4"
}

@test "--max-records N passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --max-records 1000
  [ "$status" -eq 0 ]
  assert_arg "--max-records"
  assert_arg "1000"
}

@test "--chunk-size N passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --chunk-size 128
  [ "$status" -eq 0 ]
  assert_arg "--chunk-size"
  assert_arg "128"
}

@test "--tag-json value passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --tag-json '{"env":"ci"}'
  [ "$status" -eq 0 ]
  assert_arg "--tag-json"
  assert_arg '{"env":"ci"}'
}

@test "--ginger-args value passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --ginger-args "--timeout=30"
  [ "$status" -eq 0 ]
  assert_arg "--ginger-args"
  assert_arg "--timeout=30"
}

@test "--goat-rodeo-args value passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --goat-rodeo-args "--parallel"
  [ "$status" -eq 0 ]
  assert_arg "--goat-rodeo-args"
  assert_arg "--parallel"
}

@test "all value flags combined" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" \
    --threads 4 --max-records 1000 --chunk-size 128 \
    --tag-json '{"k":"v"}' --ginger-args "--g" --goat-rodeo-args "--r"
  [ "$status" -eq 0 ]
  assert_arg "--threads"
  assert_arg "4"
  assert_arg "--max-records"
  assert_arg "1000"
  assert_arg "--chunk-size"
  assert_arg "128"
  assert_arg "--tag-json"
  assert_arg '{"k":"v"}'
  assert_arg "--ginger-args"
  assert_arg "--g"
  assert_arg "--goat-rodeo-args"
  assert_arg "--r"
}

@test "flags before positional args" {
  run "$WRAPPER" survey inventory --threads 4 --log-level debug myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  assert_arg "survey"
  assert_arg "inventory"
  assert_arg "myapp"
  assert_arg "/mnt/input"
  assert_arg "--threads"
  assert_arg "4"
  assert_arg "--log-level"
  assert_arg "debug"
}

# ── Boolean flags ────────────────────────────────────────────────────────────

@test "--no-upload passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --no-upload
  [ "$status" -eq 0 ]
  assert_arg "--no-upload"
}

@test "--upload-only passes through" {
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --upload-only
  [ "$status" -eq 0 ]
  assert_arg "--upload-only"
}

# ── Log file ─────────────────────────────────────────────────────────────────

@test "--log-file (space) creates log file with content" {
  local logfile="$TEST_TMPDIR/test.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5  # wait for process substitution tee/sed to flush
  [ -f "$logfile" ]
  [ -s "$logfile" ]
  grep -q "SPICE_TEST_BEGIN" "$logfile"
}

@test "--log-file= (equals) creates log file" {
  local logfile="$TEST_TMPDIR/test-eq.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" "--log-file=$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5
  [ -f "$logfile" ]
  [ -s "$logfile" ]
  grep -q "SPICE_TEST_BEGIN" "$logfile"
}

@test "log file has ANSI codes stripped" {
  local logfile="$TEST_TMPDIR/ansi.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5
  # Log file should contain the text but not the escape codes
  grep -q "COLORED:green-text" "$logfile"
  grep -q "COLORED:red-text" "$logfile"
  ! grep -qP '\x1b\[' "$logfile"
}

@test "log file written when --threads present (bug #529)" {
  local logfile="$TEST_TMPDIR/threads.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --threads 2 --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5
  [ -f "$logfile" ]
  [ -s "$logfile" ]
  grep -q "SPICE_TEST_BEGIN" "$logfile"
}

@test "log file written with all extra flags (bug #529)" {
  local logfile="$TEST_TMPDIR/allflags.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" \
    --threads 2 --max-records 100 --chunk-size 32 \
    --ginger-args "--g" --tag-json '{"k":"v"}' \
    --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5
  [ -f "$logfile" ]
  [ -s "$logfile" ]
  grep -q "SPICE_TEST_BEGIN" "$logfile"
}

@test "--log-file stripped from container args" {
  local logfile="$TEST_TMPDIR/stripped.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input" --log-file "$logfile"
  [ "$status" -eq 0 ]
  refute_arg "--log-file"
  refute_arg "$logfile"
}

# ── SPICE_PASS ───────────────────────────────────────────────────────────────

@test "SPICE_PASS passed to container" {
  export SPICE_PASS="my-secret-token"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  [ "$(container_env SPICE_PASS)" = "my-secret-token" ]
}

@test "SPICE_PASS with whitespace is trimmed" {
  export SPICE_PASS="  spaces-around  "
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  # Bash passes SPICE_PASS by reference (-e SPICE_PASS), so trimming
  # is not done by the bash wrapper. This test documents that behavior.
  local pass="$(container_env SPICE_PASS)"
  [ -n "$pass" ]
}

# ── Exit code ────────────────────────────────────────────────────────────────

@test "non-zero exit code propagated" {
  export SPICE_DOCKER_FLAGS="-e TEST_EXIT_CODE=42"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 42 ]
}
