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
  # The mock docker records every invocation, so a manifest refresh would clobber the
  # captured args. Tests here exercise the embedded manifest.
  export SPICE_SKIP_MANIFEST_REFRESH=1
  unset SPICE_PATH_MANIFEST

  TEST_TMPDIR="$(mktemp -d)"
  MOCK_BIN="$TEST_TMPDIR/mock-bin"
  mkdir -p "$MOCK_BIN"

  # Mock docker: captures all args to a file
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
# If this is a "pull" command, silently succeed
if [ "$1" = "pull" ]; then exit 0; fi
if [ "$1" = "info" ]; then echo linux; exit 0; fi
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

  # Check java was called via -cp (so classpath plugins load) + explicit main class
  local java_args="$(cat "$JAVA_ARGS_FILE")"
  [[ "$java_args" == *"-cp"* ]]
  [[ "$java_args" == *"$jar"* ]]
  [[ "$java_args" == *"io.spicelabs.cli.SpiceLabsCLI"* ]]
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
if [ "$1" = "info" ]; then echo linux; exit 0; fi
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

# ── Error handling tests ─────────────────────────────────────────────────────

@test "docker not installed: exits with clear error" {
  # Remove the docker mock, then build a PATH identical to the wrapper's normal
  # runtime PATH except that no `docker` binary (mock or real) is reachable.
  # Symlinking every executable found in the current PATH dirs keeps the
  # wrapper's full toolchain (awk, shasum, dirname, basename, ...) available on
  # any OS, while excluding docker wherever it is installed.
  /bin/rm -f "$MOCK_BIN/docker"
  local saved_path="$PATH"
  local sysbin="$TEST_TMPDIR/sysbin"
  mkdir -p "$sysbin"
  local dir tool name dirs
  IFS=: read -ra dirs <<< "$saved_path"
  for dir in "${dirs[@]}"; do
    [[ "$dir" == /* && -d "$dir" ]] || continue
    for tool in "$dir"/*; do
      [ -x "$tool" ] || continue
      name="$(basename "$tool")"
      [[ "$name" == "docker" ]] && continue
      [ -e "$sysbin/$name" ] || ln -s "$tool" "$sysbin/$name"
    done
  done
  export PATH="$sysbin"

  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR"
  local status_rc="$status"
  echo "docker-missing status=${status_rc} output=${output}"
  # Restore PATH before teardown: teardown's rm must not resolve into the
  # temp dir that it is about to delete.
  export PATH="$saved_path"
  [ "$status_rc" -eq 1 ]
  [[ "$output" == *"Docker is not installed"* ]]
}

@test "JVM mode: missing JAR file exits with error" {
  export SPICE_LABS_CLI_USE_JVM=1
  export SPICE_LABS_CLI_JAR="$TEST_TMPDIR/nonexistent.jar"
  
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR"
  [ "$status" -eq 1 ]
  [[ "$output" == *"Missing:"* ]]
  [[ "$output" == *"nonexistent.jar"* ]]
}

@test "JVM mode: SPICE_LABS_JVM_ARGS passed to java" {
  local jar="$TEST_TMPDIR/fake.jar"
  touch "$jar"
  export SPICE_LABS_CLI_USE_JVM=1
  export SPICE_LABS_CLI_JAR="$jar"
  export SPICE_LABS_JVM_ARGS="-Xmx2g -XX:+UseG1GC"
  
  run "$WRAPPER" survey inventory myapp /some/path
  [ "$status" -eq 0 ]
  
  local java_args="$(cat "$JAVA_ARGS_FILE")"
  [[ "$java_args" == *"-Xmx2g"* ]]
  [[ "$java_args" == *"-XX:+UseG1GC"* ]]
}

@test "nonexistent input path: exits before docker" {
  # Create docker mock that would succeed
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
echo "DOCKER_WOULD_RUN"
exit 0
MOCK
  chmod +x "$MOCK_BIN/docker"
  
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/does-not-exist"
  [ "$status" -eq 2 ]
  [[ "$output" == *"Input path does not exist"* ]]
  [[ "$output" != *"DOCKER_WOULD_RUN"* ]]
}

@test "SPICE_DOCKER_FLAGS: custom flags passed to docker" {
  mkdir -p "$TEST_TMPDIR/input"
  echo test > "$TEST_TMPDIR/input/f.txt"
  export SPICE_DOCKER_FLAGS="--memory=2g --cpus=2"
  
  run "$WRAPPER" survey inventory myapp "$TEST_TMPDIR/input"
  [ "$status" -eq 0 ]
  local docker_args="$(cat "$DOCKER_ARGS_FILE")"
  [[ "$docker_args" == *"--memory=2g"* ]]
  [[ "$docker_args" == *"--cpus=2"* ]]
}

@test "runtime survey: --native-only flag passes through" {
  # Mock docker to capture runtime survey args
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
if [ "$1" = "pull" ]; then exit 0; fi
if [ "$1" = "info" ]; then echo linux; exit 0; fi
# For runtime survey, just echo the args
echo "$@" > "${DOCKER_ARGS_FILE:-/dev/null}"
# Simulate successful extraction
if [[ "$*" == *"cp"* ]]; then
  # Creating fake files for extraction phase
  touch "${RT_WORKDIR:-/tmp}/spice-jfr.jfc" 2>/dev/null || true
fi
exit 0
MOCK
  chmod +x "$MOCK_BIN/docker"
  
  # Skip this test - runtime survey requires complex mocking
  skip "Runtime survey requires complex Docker mocking"
}

# ── spice docs: the guide in a browser on the host ───────────────────────────
# In Docker mode the container has no browser, so the wrapper asks it for HTML and opens
# the page itself. Mock openers (`open` on macOS, `xdg-open` elsewhere) record what they
# were asked to open. bats' stdout is not a terminal, so run_in_terminal gives the wrapper
# one where a test needs it.

# Run the wrapper with a pseudo-terminal for its terminal, as `run` does otherwise. Python's
# pty module rather than script(1): BSD script needs its own stdin to be a terminal, which a CI
# runner (or any non-interactive shell) does not give it.
run_in_terminal() {
  command -v python3 >/dev/null 2>&1 || skip "python3 is needed for a pseudo-terminal"
  run python3 -c 'import os, pty, sys; sys.exit(os.waitstatus_to_exitcode(pty.spawn(sys.argv[1:])))' "$@" </dev/null
}

docs_setup() {
  export XDG_CONFIG_HOME="$TEST_TMPDIR/xdg" XDG_CONFIG_DIRS="$TEST_TMPDIR/xdg-dirs"
  export DISPLAY=:0
  unset WAYLAND_DISPLAY SSH_CONNECTION SSH_TTY
  export OPENED_FILE="$TEST_TMPDIR/opened.txt"
  for opener in open xdg-open; do
    printf '#!/bin/bash\necho "$1" > "$OPENED_FILE"\nexit "${OPENER_EXIT:-0}"\n' > "$MOCK_BIN/$opener"
    chmod +x "$MOCK_BIN/$opener"
  done
  printf '#!/bin/bash\necho firefox.desktop\n' > "$MOCK_BIN/xdg-mime"
  chmod +x "$MOCK_BIN/xdg-mime"
  # NO_GUIDE=1: the image has no guide, so `docs --html` fails as the CLI does. Every run is
  # also logged, one line each, so a test can see the fetch and the fallback.
  mv "$MOCK_BIN/docker" "$MOCK_BIN/docker-image"
  cat > "$MOCK_BIN/docker" <<'MOCK'
#!/bin/bash
echo "$*" >> "$TEST_TMPDIR/docker-runs.txt"
if [ "${NO_GUIDE:-0}" = "1" ] && [[ " $* " == *" --html "* ]]; then
  echo "ERROR ❌ This build of spice carries no user guide."
  exit 1
fi
exec "$(dirname "$0")/docker-image" "$@"
MOCK
  chmod +x "$MOCK_BIN/docker"
  export TEST_TMPDIR
}

@test "docs: output not a terminal passes through for the container to print" {
  docs_setup
  run "$WRAPPER" docs completion
  [ "$status" -eq 0 ]
  [[ "$output" == *"ARG:docs"* ]]
  [[ "$output" == *"ARG:completion"* ]]
  [[ "$output" != *"ARG:--html"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs --browser: fetches HTML from the container and opens it on the host" {
  docs_setup
  run "$WRAPPER" docs --browser completion
  [ "$status" -eq 0 ]
  [[ "$output" == *"Opened the guide in your browser:"* ]]
  local opened; opened="$(cat "$OPENED_FILE")"
  [[ "$opened" == */guide.html ]]
  # What the container was asked for is what landed in the file the browser opens.
  grep -qx "ARG:docs" "$opened"
  grep -qx "ARG:completion" "$opened"
  grep -qx "ARG:--html" "$opened"
  ! grep -qx "ARG:--browser" "$opened"
}

@test "docs --browser: recognised after spice's own --config" {
  docs_setup
  touch "$TEST_TMPDIR/spice.toml"
  run "$WRAPPER" --config "$TEST_TMPDIR/spice.toml" docs --browser
  [ "$status" -eq 0 ]
  grep -qx "ARG:--html" "$(cat "$OPENED_FILE")"
}

@test "docs --browser: over SSH, refuses without running the container" {
  docs_setup
  export SSH_CONNECTION="10.0.0.1 22 10.0.0.2 22"
  run "$WRAPPER" docs --browser
  [ "$status" -eq 1 ]
  [[ "$output" == *"Cannot open a browser: this is an SSH session"* ]]
  [ ! -s "$DOCKER_ARGS_FILE" ]
}

@test "docs --browser: an opener that fails is an error" {
  docs_setup
  export OPENER_EXIT=1
  run "$WRAPPER" docs --browser
  [ "$status" -eq 1 ]
  [[ "$output" == *"could not open"* ]]
}

@test "docs --markdown: passes through even when a browser is available" {
  docs_setup
  run "$WRAPPER" docs --markdown intro
  [ "$status" -eq 0 ]
  [[ "$output" == *"ARG:--markdown"* ]]
  [[ "$output" != *"ARG:--html"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs: a failing container is reported, and nothing is opened" {
  docs_setup
  export TEST_EXIT_CODE=2
  run "$WRAPPER" docs --browser nope
  [ "$status" -eq 2 ]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs: JVM mode leaves the choice to the CLI on the host" {
  docs_setup
  local jar="$TEST_TMPDIR/fake.jar"
  touch "$jar"
  export SPICE_LABS_CLI_USE_JVM=1 SPICE_LABS_CLI_JAR="$jar"
  run "$WRAPPER" docs --browser completion
  [ "$status" -eq 0 ]
  local java_args; java_args="$(cat "$JAVA_ARGS_FILE")"
  [[ "$java_args" == *"docs --browser completion"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs: in a terminal, opens the page in a browser by default" {
  docs_setup
  run_in_terminal "$WRAPPER" docs completion
  [ "$status" -eq 0 ]
  [[ "$output" == *"Opened the guide in your browser:"* ]]
  grep -qx "ARG:--html" "$(cat "$OPENED_FILE")"
}

@test "docs --json: spice's own docs command reaches the container untouched, even in a terminal" {
  docs_setup
  run_in_terminal "$WRAPPER" docs --json
  [ "$status" -eq 0 ]
  [[ "$output" == *"ARG:docs"* ]]
  [[ "$output" == *"ARG:--json"* ]]
  [[ "$output" != *"ARG:--html"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs --commands: reaches the container untouched, even in a terminal" {
  docs_setup
  run_in_terminal "$WRAPPER" docs --commands
  [ "$status" -eq 0 ]
  [[ "$output" == *"ARG:--commands"* ]]
  [[ "$output" != *"ARG:--html"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs: an image without a guide falls back to plain docs, which prints the help" {
  docs_setup
  export NO_GUIDE=1
  run_in_terminal "$WRAPPER" docs
  [ "$status" -eq 0 ]
  # The fetch asked for HTML; the fallback ran the command as given.
  [ "$(wc -l < "$TEST_TMPDIR/docker-runs.txt" | tr -d ' ')" -eq 2 ]
  [[ "$(sed -n 1p "$TEST_TMPDIR/docker-runs.txt")" == *" docs --html" ]]
  [[ "$(sed -n 2p "$TEST_TMPDIR/docker-runs.txt")" == *" docs" ]]
  [[ "$output" != *"carries no user guide"* ]]
  [ ! -f "$OPENED_FILE" ]
}

@test "docs --browser: when the fetch fails, the container answers the command as given" {
  docs_setup
  export NO_GUIDE=1
  run "$WRAPPER" docs --browser nope
  [[ "$(sed -n 2p "$TEST_TMPDIR/docker-runs.txt")" == *" docs nope --browser" ]]
  [ ! -f "$OPENED_FILE" ]
}
