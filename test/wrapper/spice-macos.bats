#!/usr/bin/env bats
#
# macOS-specific tests for the bash wrapper script (spice).
# No Docker required — tests portability and JVM-mode arg handling.
#
# Run:  cd ~/dev/spice-labs-cli && bats test/wrapper/spice-macos.bats

# ── One-time setup ───────────────────────────────────────────────────────────

setup_file() {
  export REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
  export WRAPPER="$REPO_ROOT/spice"
}

# ── Per-test setup/teardown ──────────────────────────────────────────────────

setup() {
  TEST_TMPDIR="$(mktemp -d)"
  MOCK_BIN="$TEST_TMPDIR/mock-bin"
  mkdir -p "$MOCK_BIN"

  # Mock docker: captures all args to a file
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
# If this is a "pull" command, silently succeed
if [ "$1" = "pull" ]; then exit 0; fi
# For "run", echo all args to the capture file and to stdout
echo "$@" > "${DOCKER_ARGS_FILE:-/dev/null}"
# Produce structured output like the real test container
shift_past_image=0
for arg in "$@"; do
  if [ "$shift_past_image" -eq 1 ]; then
    echo "ARG:$arg"
  fi
  # The image is the arg after the last -e/env/flag sequence
  case "$arg" in
    *spice-wrapper-test*|*spicelabs/spice-labs-cli*) shift_past_image=1 ;;
  esac
done
exit "${TEST_EXIT_CODE:-0}"
MOCK
  chmod +x "$MOCK_BIN/docker"

  # Mock java: captures all args
  cat > "$MOCK_BIN/java" <<'MOCK'
#!/bin/bash
echo "$@" > "${JAVA_ARGS_FILE:-/dev/null}"
echo "===SPICE_TEST_BEGIN==="
for arg in "$@"; do echo "ARG:$arg"; done
echo "===SPICE_TEST_END==="
exit 0
MOCK
  chmod +x "$MOCK_BIN/java"

  # Put mocks first on PATH
  export PATH="$MOCK_BIN:$PATH"
  export SPICE_LABS_CLI_SKIP_PULL=1
  export SPICE_IMAGE="spice-wrapper-test"
  export SPICE_IMAGE_TAG=latest
  export SPICE_PASS=test-pass
  export DOCKER_ARGS_FILE="$TEST_TMPDIR/docker-args.txt"
  export JAVA_ARGS_FILE="$TEST_TMPDIR/java-args.txt"
  unset __SPICE_LOGGING_ACTIVE
}

teardown() {
  rm -rf "$TEST_TMPDIR"
}

# ── Portability ──────────────────────────────────────────────────────────────

@test "script runs without syntax errors" {
  # Parse check — bash -n doesn't execute, just checks syntax
  run bash -n "$WRAPPER"
  [ "$status" -eq 0 ]
}

@test "sha256sum or shasum available for hash computation" {
  # The script uses sha256sum. On macOS, only shasum -a 256 is available.
  # This test documents what's available on the current platform.
  if command -v sha256sum &>/dev/null; then
    run sha256sum "$WRAPPER"
    [ "$status" -eq 0 ]
  elif command -v shasum &>/dev/null; then
    run shasum -a 256 "$WRAPPER"
    [ "$status" -eq 0 ]
  else
    skip "neither sha256sum nor shasum available"
  fi
}

# ── JVM mode ─────────────────────────────────────────────────────────────────

@test "JVM mode: args passed to java correctly" {
  local jar="$TEST_TMPDIR/fake.jar"
  touch "$jar"
  export SPICE_LABS_CLI_USE_JVM=1
  export SPICE_LABS_CLI_JAR="$jar"

  run "$WRAPPER" survey inventory myapp /some/path --threads 4
  [ "$status" -eq 0 ]

  # Check java was called with the jar and the args
  local java_args="$(cat "$JAVA_ARGS_FILE")"
  [[ "$java_args" == *"-jar"* ]]
  [[ "$java_args" == *"$jar"* ]]
  [[ "$java_args" == *"survey"* ]]
  [[ "$java_args" == *"--threads"* ]]
}

@test "JVM mode: --log-file stripped before passing to java" {
  local jar="$TEST_TMPDIR/fake.jar"
  touch "$jar"
  export SPICE_LABS_CLI_USE_JVM=1
  export SPICE_LABS_CLI_JAR="$jar"

  local logfile="$TEST_TMPDIR/test.log"
  run "$WRAPPER" survey inventory myapp /some/path --log-file "$logfile"
  [ "$status" -eq 0 ]

  local java_args="$(cat "$JAVA_ARGS_FILE")"
  [[ "$java_args" != *"--log-file"* ]]
  [[ "$java_args" != *"$logfile"* ]]
}

# ── Log file tee (uses mock docker, no real Docker needed) ───────────────────

@test "log file tee works with mock docker" {
  local logfile="$TEST_TMPDIR/test.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR" --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5  # wait for process substitution to flush
  [ -f "$logfile" ]
  [ -s "$logfile" ]
}

@test "log file ANSI stripping works" {
  # Make mock docker produce ANSI output
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
if [ "$1" = "pull" ]; then exit 0; fi
printf '\033[32mGREEN\033[0m\n'
printf '\033[31mRED\033[0m\n'
exit 0
MOCK
  chmod +x "$MOCK_BIN/docker"

  local logfile="$TEST_TMPDIR/ansi.log"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR" --log-file "$logfile"
  [ "$status" -eq 0 ]
  sleep 0.5
  [ -f "$logfile" ]
  grep -q "GREEN" "$logfile"
  grep -q "RED" "$logfile"
  ! grep -qP '\x1b\[' "$logfile"
}

# ── Docker command construction (via captured args) ──────────────────────────

@test "docker run includes --network host" {
  mkdir -p "$TEST_TMPDIR/input"
  echo test > "$TEST_TMPDIR/input/f.txt"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  local docker_args="$(cat "$DOCKER_ARGS_FILE")"
  [[ "$docker_args" == *"--network host"* ]]
}

@test "docker run includes --user on Linux" {
  if [[ "$(uname)" == "Darwin" ]]; then
    skip "id -u behavior differs on macOS, tested separately"
  fi
  mkdir -p "$TEST_TMPDIR/input"
  echo test > "$TEST_TMPDIR/input/f.txt"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  local docker_args="$(cat "$DOCKER_ARGS_FILE")"
  [[ "$docker_args" == *"--user"* ]]
}

@test "docker run includes --pull=never when SKIP_PULL set" {
  mkdir -p "$TEST_TMPDIR/input"
  echo test > "$TEST_TMPDIR/input/f.txt"
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  local docker_args="$(cat "$DOCKER_ARGS_FILE")"
  [[ "$docker_args" == *"--pull=never"* ]]
}
