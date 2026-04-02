#!/usr/bin/env bats
#
# Tests for bash tab completion (completions/spice.bash).
#
# Run:  cd ~/dev/spice-labs-cli && bats test/wrapper/completions.bats

setup_file() {
  export REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
}

setup() {
  # Use a minimal _init_completion stub for predictable behavior.
  # The real bash-completion library has special -- handling that
  # complicates testing. Our completion script only needs cur/prev/words/cword.
  _init_completion() {
    COMPREPLY=()
    cur="${COMP_WORDS[COMP_CWORD]}"
    prev="${COMP_WORDS[COMP_CWORD-1]}"
    words=("${COMP_WORDS[@]}")
    cword=$COMP_CWORD
    return 0
  }
  _filedir() { :; }
  source "$REPO_ROOT/completions/spice.bash"
}

# Helper: simulate completion at the end of a command line
# Usage: complete_at "spice survey "
complete_at() {
  local cmdline="$1"
  # Split into words
  COMP_WORDS=($cmdline)
  COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 ))
  # If the line ends with a space, we're completing an empty word
  if [[ "$cmdline" == *" " ]]; then
    COMP_WORDS+=("")
    COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 ))
  fi
  COMPREPLY=()
  _spice_completions
  echo "${COMPREPLY[*]}"
}

# ── Top-level completions ────────────────────────────────────────────────────

@test "top level: completes survey and pass" {
  result="$(complete_at "spice ")"
  [[ "$result" == *"survey"* ]]
  [[ "$result" == *"pass"* ]]
}

@test "top level: completes --help and --version" {
  result="$(complete_at "spice ")"
  [[ "$result" == *"--help"* ]]
  [[ "$result" == *"--version"* ]]
}

@test "top level: partial 's' completes to survey" {
  result="$(complete_at "spice s")"
  [[ "$result" == *"survey"* ]]
}

@test "top level: partial 'p' completes to pass" {
  result="$(complete_at "spice p")"
  [[ "$result" == *"pass"* ]]
}

# ── Survey subcommand ────────────────────────────────────────────────────────

@test "survey: completes inventory" {
  result="$(complete_at "spice survey ")"
  [[ "$result" == *"inventory"* ]]
}

@test "survey: completes --help" {
  result="$(complete_at "spice survey ")"
  [[ "$result" == *"--help"* ]]
}

# ── Survey inventory options ─────────────────────────────────────────────────

@test "survey inventory: completes flags when dash typed" {
  result="$(complete_at "spice survey inventory myapp /path --")"
  [[ "$result" == *"--output"* ]]
  [[ "$result" == *"--threads"* ]]
  [[ "$result" == *"--no-upload"* ]]
  [[ "$result" == *"--log-level"* ]]
  [[ "$result" == *"--log-file"* ]]
  [[ "$result" == *"--tag-json"* ]]
  [[ "$result" == *"--ginger-args"* ]]
  [[ "$result" == *"--goat-rodeo-args"* ]]
}

@test "survey inventory: --log-level completes levels" {
  result="$(complete_at "spice survey inventory myapp /path --log-level ")"
  [[ "$result" == *"debug"* ]]
  [[ "$result" == *"info"* ]]
  [[ "$result" == *"warn"* ]]
  [[ "$result" == *"error"* ]]
  [[ "$result" == *"trace"* ]]
}

@test "survey inventory: --threads has no completions" {
  result="$(complete_at "spice survey inventory myapp /path --threads ")"
  [ -z "$result" ]
}

@test "survey inventory: --tag-json has no completions" {
  result="$(complete_at "spice survey inventory myapp /path --tag-json ")"
  [ -z "$result" ]
}

# ── Pass subcommand ──────────────────────────────────────────────────────────

@test "pass: completes decode" {
  result="$(complete_at "spice pass ")"
  [[ "$result" == *"decode"* ]]
}

@test "pass: completes --help" {
  result="$(complete_at "spice pass ")"
  [[ "$result" == *"--help"* ]]
}

@test "pass decode: completes --help and --version" {
  result="$(complete_at "spice pass decode ")"
  [[ "$result" == *"--help"* ]]
  [[ "$result" == *"--version"* ]]
}

@test "pass decode: no extra subcommands" {
  result="$(complete_at "spice pass decode ")"
  [[ "$result" != *"survey"* ]]
  [[ "$result" != *"inventory"* ]]
}
