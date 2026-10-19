#!/bin/sh
# To Install : `curl -sSfL https://install.spicelabs.io | sh`
#
# Interactive when a terminal is available: asks for a credential, where to
# install, and which shell init scripts to update. Options, which also work when
# piped (`curl ... | sh -s -- --yes`):
#
#   -y, --yes        accept the defaults without asking (also SPICE_INSTALL_NONINTERACTIVE=1)
#   --dir DIR        install the spice script into DIR
#   --no-modify-rc   leave shell init scripts untouched
#
# Changes are made as a transaction: everything is downloaded, generated and
# written beside its destination first, then moved into place, and if anything
# fails before the last move, every change already made is undone.

set -eu

SCRIPT_URL="https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice"
IMAGE="${SPICE_IMAGE:-spicelabs/spice-labs-cli}"
TAG="${SPICE_IMAGE_TAG:-latest}"
DEFAULT_DIR="${HOME}/.local/bin"
DATA_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}/spice"
COMPLETION_DIR="${HOME}/.local/share/spice/completions"
CONFIG_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/spice"
CREDENTIALS_FILE="$CONFIG_DIR/credentials"
FISH_CONFIG="${XDG_CONFIG_HOME:-${HOME}/.config}/fish/config.fish"
BLOCK_BEGIN="# >>> spice >>>"
BLOCK_END="# <<< spice <<<"
NL='
'
CR=$(printf '\r')
BS=$(printf '\b')
DEL=$(printf '\177')
ESC=$(printf '\033')
EOT=$(printf '\004')
NAK=$(printf '\025')

say() { printf '%s\n' "$*"; }
die() { printf '❌ %s\n' "$*" >&2; exit 1; }

# Shows a path with $HOME abbreviated to ~
tildify() {
  case "$1" in
    "$HOME"/*) printf '~%s' "${1#"$HOME"}" ;;
    *) printf '%s' "$1" ;;
  esac
}

# Paths, commands and variable names are shown in cyan italics on a terminal
CODE_ON=""
CODE_OFF=""
if [ -t 1 ] && [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
  CODE_ON=$(printf '\033[3;36m')
  CODE_OFF=$(printf '\033[23;39m')
fi
code() { printf '%s%s%s' "$CODE_ON" "$*" "$CODE_OFF"; }
path() { code "$(tildify "$1")"; }

# Whether newline-separated LIST contains the line ITEM
contains_line() {
  case "$NL$1$NL" in
    *"$NL$2$NL"*) return 0 ;;
    *) return 1 ;;
  esac
}

on_path() {
  case ":$PATH:" in
    *":$1:"*) return 0 ;;
    *) return 1 ;;
  esac
}

# ── Transaction ──────────────────────────────────────────────────────────────
#
# JOURNAL records each committed change, newest first, as three lines: the kind
# (dir or file), the target, and the backup of what it replaced (empty if it
# replaced nothing). PREPARED lists the new files staged beside their targets.

STAGE=""
JOURNAL=""
PREPARED=""
STTY_SAVED=""

rollback() {
  [ -n "$JOURNAL" ] || return 0
  say "↩️  Installation failed; undoing the changes made so far." >&2
  while IFS= read -r kind && IFS= read -r target && IFS= read -r backup; do
    case "$kind" in
      dir) rmdir "$target" 2>/dev/null || true ;;
      file)
        if [ -z "$backup" ]; then
          rm -f "$target"
        elif [ -L "$target" ]; then
          cat "$backup" > "$target" && rm -f "$backup"
        else
          mv -f "$backup" "$target"
        fi
        ;;
    esac
  done <<EOF
$JOURNAL
EOF
  JOURNAL=""
}

cleanup() {
  status=$?
  if [ -n "$STTY_SAVED" ]; then
    stty "$STTY_SAVED" </dev/tty 2>/dev/null || true
    printf '\n' >/dev/tty 2>/dev/null || true
  fi
  if [ -n "$PREPARED" ]; then
    printf '%s\n' "$PREPARED" | while IFS= read -r f; do
      [ -z "$f" ] || rm -f "$f"
    done
  fi
  [ "$status" -eq 0 ] || rollback
  [ -z "$STAGE" ] || rm -rf "$STAGE"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# Creates DIR and any missing parents, journalling each so rollback removes them
make_dir() {
  md_missing=""
  md_d=$1
  while [ ! -d "$md_d" ]; do
    md_missing="$md_d$NL$md_missing"
    md_d=$(dirname "$md_d")
  done
  mkdir -p "$1"
  # Parents are journalled before children, so rollback removes children first
  while IFS= read -r md_d; do
    [ -z "$md_d" ] || JOURNAL="dir$NL$md_d$NL$NL$JOURNAL"
  done <<EOF
$md_missing
EOF
}

# Copies SOURCE beside TARGET, ready to be moved into place by commit_file
prepare_file() {
  pf_new="$2.spice-new.$$"
  PREPARED="$PREPARED$NL$pf_new"
  if [ -f "$2" ]; then
    cp -p "$2" "$pf_new"       # keeps the target's permissions
    cat "$1" > "$pf_new"
  else
    cp "$1" "$pf_new"
  fi
}

# Moves the file prepared for TARGET into place, keeping a backup of the old one
commit_file() {
  cf_new="$1.spice-new.$$"
  cf_backup=""
  if [ -f "$1" ]; then
    cf_backup="$1.spice-old.$$"
    cp -p "$1" "$cf_backup"
  fi
  JOURNAL="file$NL$1$NL$cf_backup$NL$JOURNAL"
  if [ -L "$1" ]; then
    # Write through symlinks, so files managed by a dotfiles repo stay managed
    cat "$cf_new" > "$1"
    rm -f "$cf_new"
  else
    mv -f "$cf_new" "$1"
  fi
}

# Ends the transaction: the changes are kept, and the backups are not needed
finish() {
  while IFS= read -r kind && IFS= read -r target && IFS= read -r backup; do
    [ -z "$backup" ] || rm -f "$backup"
  done <<EOF
$JOURNAL
EOF
  JOURNAL=""
}

# ── Prompts ──────────────────────────────────────────────────────────────────
#
# Prompts read from /dev/tty, because when the script is piped from curl its
# standard input is the script itself.

ask() {
  printf '%s' "$1" >/dev/tty
  IFS= read -r REPLY </dev/tty || { printf '\n' >/dev/tty; die "Cancelled; nothing was changed."; }
  [ -n "$REPLY" ] || REPLY=$2
}

confirm() {
  while :; do
    if [ "$2" = y ]; then ask "$1 [Y/n] " y; else ask "$1 [y/N] " n; fi
    case "$REPLY" in
      [Yy]|[Yy][Ee][Ss]) return 0 ;;
      [Nn]|[Nn][Oo]) return 1 ;;
    esac
  done
}

# Reads a line, echoing a * for each character. Canonical mode is off, because
# it limits a line to 1024 bytes on macOS and a pasted credential can be
# longer, so editing keys are handled here: Backspace, and Ctrl-U to erase
# everything. Echo and canonical mode change before the prompt appears, so
# nothing typed after it is shown.
ask_secret() {
  STTY_SAVED=$(stty -g </dev/tty)
  stty -echo -icanon min 1 time 0 </dev/tty
  printf '%s' "$1" >/dev/tty
  REPLY=""
  as_done=0
  while [ "$as_done" = 0 ]; do
    # Whatever has been typed or pasted so far; x keeps trailing newlines
    as_chunk=$(dd bs=4096 count=1 2>/dev/null </dev/tty; printf x)
    as_chunk=${as_chunk%x}
    [ -n "$as_chunk" ] || break
    as_echo=""
    while [ -n "$as_chunk" ]; do
      as_rest=${as_chunk#?}
      as_c=${as_chunk%"$as_rest"}
      as_chunk=$as_rest
      case "$as_c" in
        "$NL"|"$CR"|"$EOT") as_done=1; break ;;
        "$BS"|"$DEL")
          if [ -n "$REPLY" ]; then
            REPLY=${REPLY%?}
            as_echo="$as_echo$BS $BS"
          fi
          ;;
        "$NAK")
          while [ -n "$REPLY" ]; do
            REPLY=${REPLY%?}
            as_echo="$as_echo$BS $BS"
          done
          ;;
        # Drop the rest of an escape sequence, such as an arrow key
        "$ESC") break ;;
        [[:cntrl:]]) ;;
        *) REPLY="$REPLY$as_c"; as_echo="$as_echo*" ;;
      esac
    done
    printf '%s' "$as_echo" >/dev/tty
  done
  stty "$STTY_SAVED" </dev/tty
  STTY_SAVED=""
  printf '\n' >/dev/tty
}

# ── Credential ───────────────────────────────────────────────────────────────

CRED_VAR=""
CRED_VALUE=""

credential_saved() { [ -f "$CREDENTIALS_FILE" ]; }

choose_credential() {
  say ""
  say "🔑 $(code spice) needs a Spice License or a Spice Pass to run."
  [ -z "${SPICE_LICENSE:-}" ] || say "   $(code SPICE_LICENSE) is already set in this shell."
  [ -z "${SPICE_PASS:-}" ] || say "   $(code SPICE_PASS) is already set in this shell."
  ! credential_saved || say "   A credential is already saved in $(path "$CREDENTIALS_FILE")."
  say ""
  say "  1) Spice License: issued to your organization by Spice Labs"
  say "  2) Spice Pass: downloaded from your Spice Labs project dashboard"
  say "  3) Skip"
  say ""
  while :; do
    ask "Which do you have? [3] " 3
    case "$REPLY" in
      1) CRED_VAR=SPICE_LICENSE; cc_name="Spice License"; break ;;
      2) CRED_VAR=SPICE_PASS; cc_name="Spice Pass"; break ;;
      3) return 0 ;;
    esac
  done

  while :; do
    ask_secret "Paste your $cc_name, or the path to a file containing it: "
    cc_input=$REPLY
    case "$cc_input" in
      "~"/*) cc_input="$HOME/${cc_input#"~/"}" ;;
    esac
    if [ -f "$cc_input" ]; then
      cc_input=$(cat "$cc_input")
    fi
    CRED_VALUE=$(printf '%s' "$cc_input" | tr -d '[:space:]')
    if [ -z "$CRED_VALUE" ]; then
      say "   No $cc_name entered; skipping."
      CRED_VAR=""
      return 0
    fi
    # The credentials file must parse the same way in sh and fish, so the value
    # is limited to the characters a token can contain.
    case "$CRED_VALUE" in
      *[!A-Za-z0-9._~+/=-]*) say "   That doesn't look like a $cc_name. Try again, or press Enter to skip." ;;
      *) return 0 ;;
    esac
  done
}

# Writes the credentials file to FILE: the existing one, with CRED_VAR replaced
write_credentials() {
  {
    if credential_saved; then
      grep -v "^export $CRED_VAR=" "$CREDENTIALS_FILE" || true
    else
      say "# Spice Labs credentials, loaded by the spice block in your shell init script."
      say "# Every line here must be valid in sh, bash, zsh and fish."
    fi
    say "export $CRED_VAR='$CRED_VALUE'"
  } > "$1"
  chmod 600 "$1"
}

# ── Install directory ────────────────────────────────────────────────────────

INSTALL_DIR=""
EXISTING=""

# Makes a directory absolute and tidy, or fails if it can't be used
normalize_dir() {
  nd=$1
  case "$nd" in
    "~") nd=$HOME ;;
    "~"/*) nd="$HOME/${nd#"~/"}" ;;
    /*) ;;
    *) nd="$PWD/$nd" ;;
  esac
  while :; do
    case "$nd" in
      ?*/) nd=${nd%/} ;;
      *) break ;;
    esac
  done
  # The directory is written into shell init scripts and PATH
  case "$nd" in
    *[\"\$\`\\:]*|*"$NL"*) return 1 ;;
  esac
  printf '%s' "$nd"
}

# Whether DIR is writable, or could be created
dir_writable() {
  dw=$1
  while [ ! -d "$dw" ]; do dw=$(dirname "$dw"); done
  [ -w "$dw" ]
}

find_existing() {
  fe=$(command -v spice 2>/dev/null) || return 0
  case "$fe" in
    /*) EXISTING=$(dirname "$fe") ;;
  esac
}

choose_dir() {
  cd_list=""
  for cd_d in "$EXISTING" "$DEFAULT_DIR"; do
    [ -n "$cd_d" ] || continue
    contains_line "$cd_list" "$cd_d" || cd_list="$cd_list$cd_d$NL"
  done
  cd_ifs=$IFS
  IFS=:
  set -f
  for cd_d in $PATH; do
    case "$cd_d" in /*) ;; *) continue ;; esac
    cd_d=$(normalize_dir "$cd_d") || continue
    [ -d "$cd_d" ] && [ -w "$cd_d" ] || continue
    contains_line "$cd_list" "$cd_d" || cd_list="$cd_list$cd_d$NL"
  done
  set +f
  IFS=$cd_ifs

  say ""
  say "📁 Where should $(code spice) be installed?"
  say ""
  cd_n=0
  while IFS= read -r cd_d; do
    [ -n "$cd_d" ] || continue
    cd_n=$((cd_n + 1))
    cd_note=""
    [ "$cd_d" != "$EXISTING" ] || cd_note="  (current install)"
    on_path "$cd_d" || cd_note="$cd_note  (will be added to $(code PATH))"
    say "  $cd_n) $(path "$cd_d")$cd_note"
  done <<EOF
$cd_list
EOF
  cd_other=$((cd_n + 1))
  say "  $cd_other) Somewhere else"
  say ""

  while :; do
    ask "Please select a location [1]: " 1
    case "$REPLY" in
      *[!0-9]*|"") continue ;;
    esac
    if [ "$REPLY" -ge 1 ] && [ "$REPLY" -le "$cd_n" ]; then
      INSTALL_DIR=$(printf '%s' "$cd_list" | sed -n "${REPLY}p")
      return 0
    fi
    if [ "$REPLY" -eq "$cd_other" ]; then
      while :; do
        ask "Directory: " ""
        [ -n "$REPLY" ] || continue
        if ! cd_d=$(normalize_dir "$REPLY"); then
          say "   Directory names can't contain quotes, \$, \`, \\ or :."
        elif ! dir_writable "$cd_d"; then
          say "   You can't write to $(path "$cd_d")."
        else
          INSTALL_DIR=$cd_d
          return 0
        fi
      done
    fi
  done
}

# ── Shell init scripts ───────────────────────────────────────────────────────

RC_FILES=""

# Whether FILE already has spice lines: a block, or lines from older installers
has_spice() {
  [ -f "$1" ] && grep -qxF -e "$BLOCK_BEGIN" -e "# Spice CLI tab completion" "$1"
}

# Lists the candidate init scripts, then their default selection, separated by
# a line holding only "--"
rc_candidates() {
  rcc_shell=$(basename "${SHELL:-sh}")
  rcc_all=""
  rcc_default=""
  for rcc_f in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile" "$FISH_CONFIG" "$HOME/.profile"; do
    rcc_login=0
    case "$rcc_shell:$rcc_f" in
      zsh:*/.zshrc|bash:*/.bashrc|fish:*/config.fish) rcc_login=1 ;;
      # On macOS, Terminal starts bash as a login shell, which reads only
      # .bash_profile; it often loads .bashrc itself, and then doesn't need us
      bash:*/.bash_profile)
        if [ "$(uname -s)" = Darwin ] && [ -f "$rcc_f" ] && ! grep -q bashrc "$rcc_f"; then
          rcc_login=1
        fi
        ;;
    esac
    [ -f "$rcc_f" ] || [ "$rcc_login" = 1 ] || continue
    rcc_all="$rcc_all$rcc_f$NL"
    if [ "$rcc_login" = 1 ] || has_spice "$rcc_f"; then
      rcc_default="$rcc_default$rcc_f$NL"
    fi
  done
  printf '%s--\n%s' "$rcc_all" "$rcc_default"
}

choose_rc() {
  cr_out=$(rc_candidates)
  cr_all=$(printf '%s\n' "$cr_out" | sed '/^--$/,$d')
  cr_default=$(printf '%s\n' "$cr_out" | sed '1,/^--$/d')

  if [ "$INTERACTIVE" = 0 ]; then
    RC_FILES=$cr_default
    return 0
  fi

  if [ -z "$cr_all" ]; then
    return 0
  fi

  if [ "$(printf '%s\n' "$cr_all" | wc -l)" -eq 1 ]; then
    say ""
    if confirm "📝 Set up $(code spice) in $(path "$cr_all")?" y; then RC_FILES=$cr_all; fi
    return 0
  fi

  say ""
  say "📝 Which shell init scripts should set up $(code spice)?"
  say ""
  cr_n=0
  cr_nums=""
  while IFS= read -r cr_f; do
    cr_n=$((cr_n + 1))
    if contains_line "$cr_default" "$cr_f"; then
      cr_nums="$cr_nums${cr_nums:+ }$cr_n"
    fi
    say "  $cr_n) $(path "$cr_f")"
  done <<EOF
$cr_all
EOF
  say ""
  [ -n "$cr_nums" ] || cr_nums=none

  while :; do
    ask "Please specify numbers, separated by spaces or $(code none) [$cr_nums]: " "$cr_nums"
    [ "$REPLY" != none ] || { RC_FILES=""; return 0; }
    RC_FILES=""
    cr_ok=1
    for cr_i in $REPLY; do
      case "$cr_i" in
        *[!0-9]*) cr_ok=0; break ;;
      esac
      if [ "$cr_i" -lt 1 ] || [ "$cr_i" -gt "$cr_n" ]; then cr_ok=0; break; fi
      cr_f=$(printf '%s\n' "$cr_all" | sed -n "${cr_i}p")
      contains_line "$RC_FILES" "$cr_f" || RC_FILES="$RC_FILES$cr_f$NL"
    done
    [ "$cr_ok" = 0 ] || return 0
  done
}

check_rc() {
  [ ! -f "$1" ] || [ -w "$1" ] || die "Can't write to $(path "$1")."
  dir_writable "$(dirname "$1")" || die "Can't write to $(path "$(dirname "$1")")."
  cr_begins=0
  cr_ends=0
  if [ -f "$1" ]; then
    cr_begins=$(grep -cxF "$BLOCK_BEGIN" "$1" || true)
    cr_ends=$(grep -cxF "$BLOCK_END" "$1" || true)
  fi
  if [ "$cr_begins" -gt 1 ] || [ "$cr_begins" != "$cr_ends" ]; then
    die "$(path "$1") has a damaged $(code spice) block. Remove the lines between $(code "$BLOCK_BEGIN") and $(code "$BLOCK_END"), then run the installer again."
  fi
}

# Whether FILE's spice block adds DIR to PATH
block_adds_path() {
  [ -f "$1" ] && sed -n "/^$BLOCK_BEGIN\$/,/^$BLOCK_END\$/p" "$1" | grep -F PATH | grep -qF "$2"
}

# Writes the spice block for init script FILE. PATH gets the install directory
# if it isn't there already, or if this block put it there in an earlier run.
rc_block() {
  rb_path=0
  if ! on_path "$INSTALL_DIR" || block_adds_path "$1" "$INSTALL_DIR"; then rb_path=1; fi
  rb_creds=0
  if credential_saved || [ -n "$CRED_VAR" ]; then rb_creds=1; fi

  say "$BLOCK_BEGIN"
  say "# Added by the spice installer, which rewrites this block when run again."
  case "$1" in
    */config.fish)
      if [ "$rb_path" = 1 ]; then
        say "if not contains -- \"$INSTALL_DIR\" \$PATH"
        say "    set -gx PATH \$PATH \"$INSTALL_DIR\""
        say "end"
      fi
      if [ "$rb_creds" = 1 ]; then
        say "if test -f \"$CREDENTIALS_FILE\""
        say "    source \"$CREDENTIALS_FILE\""
        say "end"
      fi
      ;;
    *)
      if [ "$rb_path" = 1 ]; then
        say "case \":\$PATH:\" in *\":$INSTALL_DIR:\"*) ;; *) export PATH=\"\$PATH:$INSTALL_DIR\" ;; esac"
      fi
      if [ "$rb_creds" = 1 ]; then
        say "if [ -f \"$CREDENTIALS_FILE\" ]; then . \"$CREDENTIALS_FILE\"; fi"
      fi
      case "$1" in
        */.zshrc)
          # bashcompinit needs compinit, which frameworks like oh-my-zsh load already
          say "(( \$+functions[compdef] )) || { autoload -U compinit && compinit; }"
          say "autoload -U +X bashcompinit 2>/dev/null && bashcompinit 2>/dev/null"
          say "if [ -f \"$COMPLETION_DIR/spice.bash\" ]; then . \"$COMPLETION_DIR/spice.bash\"; fi"
          ;;
        */.bashrc|*/.bash_profile)
          say "if [ -f \"$COMPLETION_DIR/spice.bash\" ]; then . \"$COMPLETION_DIR/spice.bash\"; fi"
          ;;
      esac
      ;;
  esac
  say "$BLOCK_END"
}

# Writes init script FILE with its spice block replaced by BLOCK, or appended.
# Lines that older installers appended without a block are removed.
rewrite_rc() {
  rr_in=/dev/null
  [ ! -f "$1" ] || rr_in=$1
  awk -v begin="$BLOCK_BEGIN" -v end="$BLOCK_END" -v blockfile="$2" \
      -v legacy="# Spice CLI tab completion" -v completion="$COMPLETION_DIR/spice.bash" '
    function emit(line) {
      while ((getline line < blockfile) > 0) print line
      close(blockfile)
      done = 1
    }
    function flush() { for (; blanks > 0; blanks--) print "" }
    $0 == begin { flush(); if (!done) emit(); inblock = 1; next }
    inblock { if ($0 == end) inblock = 0; next }
    $0 == legacy { inlegacy = 1; if (!done) { flush(); emit() } next }
    inlegacy && (index($0, completion) || $0 ~ /^autoload -U \+X bashcompinit/) { next }
    { inlegacy = 0 }
    $0 == "" { blanks++; next }
    { flush(); print; printed = 1 }
    END {
      if (!done && printed && blanks == 0) print ""
      flush()
      if (!done) emit()
    }
  ' "$rr_in" > "$3"
}

# ── Main ─────────────────────────────────────────────────────────────────────

usage() {
  say "Usage: $(code "install.sh [--yes] [--dir DIR] [--no-modify-rc]")"
}

main() {
  ASSUME_YES=0
  MODIFY_RC=1
  while [ $# -gt 0 ]; do
    case "$1" in
      -y|--yes) ASSUME_YES=1 ;;
      --dir) [ $# -ge 2 ] || die "$(code --dir) needs a directory."; INSTALL_DIR=$2; shift ;;
      --dir=*) INSTALL_DIR=${1#--dir=} ;;
      --no-modify-rc) MODIFY_RC=0 ;;
      -h|--help) usage; exit 0 ;;
      *) usage >&2; die "Unknown option: $1" ;;
    esac
    shift
  done

  INTERACTIVE=1
  if [ "$ASSUME_YES" = 1 ] || [ "${SPICE_INSTALL_NONINTERACTIVE:-}" = 1 ]; then
    INTERACTIVE=0
  elif ! (: </dev/tty) 2>/dev/null; then
    INTERACTIVE=0
    say "💡 No terminal to ask questions on, so installing with the defaults."
  fi

  # 1. Questions. Nothing is changed yet.

  find_existing
  if [ -n "$INSTALL_DIR" ]; then
    INSTALL_DIR=$(normalize_dir "$INSTALL_DIR") || die "$(code --dir) can't contain quotes, \$, \`, \\ or :."
  elif [ "$INTERACTIVE" = 1 ]; then
    choose_dir
  else
    INSTALL_DIR=${EXISTING:-$DEFAULT_DIR}
  fi
  dir_writable "$INSTALL_DIR" || die "Can't write to $(path "$INSTALL_DIR")."

  [ "$INTERACTIVE" = 0 ] || choose_credential
  [ "$MODIFY_RC" = 0 ] || choose_rc
  while IFS= read -r f; do
    [ -z "$f" ] || check_rc "$f"
  done <<EOF
$RC_FILES
EOF

  # 2. Download and generate everything into a staging directory.

  STAGE=$(mktemp -d)
  say ""
  say "📦 Downloading $(code spice)"
  curl -fsSL "$SCRIPT_URL" -o "$STAGE/spice" || die "Couldn't download $(code "$SCRIPT_URL")."
  IFS= read -r first < "$STAGE/spice" || first=""
  case "$first" in
    "#!"*) ;;
    *) die "The download from $(code "$SCRIPT_URL") isn't a script." ;;
  esac
  chmod +x "$STAGE/spice"

  # Plugin-aware tab completion and the path manifest both come from the
  # configured image (no static fallback), so they reflect whatever plugins
  # (e.g. `registry`) it ships. Both need Docker and the image, and are skipped
  # gracefully otherwise. SKIP_PULL keeps stdout clean for capture. The manifest
  # tells the first invocation which arguments to bind-mount; the wrapper
  # refreshes it itself when the image changes.
  have_completion=0
  if command -v docker >/dev/null 2>&1 && docker pull -q "${IMAGE}:${TAG}" >/dev/null 2>&1 \
     && SPICE_LABS_CLI_SKIP_PULL=1 "$STAGE/spice" generate-completion > "$STAGE/spice.bash" 2>/dev/null \
     && [ -s "$STAGE/spice.bash" ]; then
    have_completion=1
  fi
  have_manifest=0
  if SPICE_LABS_CLI_SKIP_PULL=1 SPICE_SKIP_MANIFEST_REFRESH=1 \
       "$STAGE/spice" path-manifest > "$STAGE/path-manifest" 2>/dev/null \
     && grep -q '^# spice-path-manifest ' "$STAGE/path-manifest"; then
    have_manifest=1
  fi

  [ -z "$CRED_VAR" ] || write_credentials "$STAGE/credentials"

  rc_changed=""
  n=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$((n + 1))
    rc_block "$f" > "$STAGE/block.$n"
    rewrite_rc "$f" "$STAGE/block.$n" "$STAGE/rc.$n"
    if [ -f "$f" ] && cmp -s "$f" "$STAGE/rc.$n"; then continue; fi
    rc_changed="$rc_changed$n$NL$f$NL"
  done <<EOF
$RC_FILES
EOF

  # 3. Confirm.

  say ""
  say "Ready to install:"
  say "  • $(code spice) in $(path "$INSTALL_DIR")"
  [ "$have_completion" = 0 ] || say "  • tab completion generated from $(code "${IMAGE}:${TAG}")"
  [ -z "$CRED_VAR" ] || say "  • $(code "$CRED_VAR") saved in $(path "$CREDENTIALS_FILE")"
  while IFS= read -r n && IFS= read -r f; do
    [ -z "$f" ] || say "  • $(code spice) set up in $(path "$f")"
  done <<EOF
$rc_changed
EOF
  if [ "$INTERACTIVE" = 1 ] && ! confirm "Proceed with installation?" y; then
    say "Nothing was changed."
    exit 0
  fi

  # 4. Stage every file beside its destination, then move them all into place.

  make_dir "$INSTALL_DIR"
  prepare_file "$STAGE/spice" "$INSTALL_DIR/spice"
  if [ "$have_completion" = 1 ]; then
    make_dir "$COMPLETION_DIR"
    prepare_file "$STAGE/spice.bash" "$COMPLETION_DIR/spice.bash"
  fi
  if [ "$have_manifest" = 1 ]; then
    make_dir "$DATA_DIR"
    prepare_file "$STAGE/path-manifest" "$DATA_DIR/path-manifest"
  fi
  if [ -n "$CRED_VAR" ]; then
    make_dir "$CONFIG_DIR"
    # Never readable by others, not even while staged
    old_umask=$(umask)
    umask 077
    prepare_file "$STAGE/credentials" "$CREDENTIALS_FILE"
    umask "$old_umask"
    chmod 600 "$CREDENTIALS_FILE.spice-new.$$"
  fi
  while IFS= read -r n && IFS= read -r f; do
    [ -n "$f" ] || continue
    make_dir "$(dirname "$f")"
    prepare_file "$STAGE/rc.$n" "$f"
  done <<EOF
$rc_changed
EOF

  commit_file "$INSTALL_DIR/spice"
  [ "$have_completion" = 0 ] || commit_file "$COMPLETION_DIR/spice.bash"
  [ "$have_manifest" = 0 ] || commit_file "$DATA_DIR/path-manifest"
  [ -z "$CRED_VAR" ] || commit_file "$CREDENTIALS_FILE"
  while IFS= read -r n && IFS= read -r f; do
    [ -z "$f" ] || commit_file "$f"
  done <<EOF
$rc_changed
EOF
  finish

  # 5. Report.

  say ""
  say "✅ $(code spice) installed in $(path "$INSTALL_DIR")"
  if [ -n "$EXISTING" ] && [ "$EXISTING" != "$INSTALL_DIR" ]; then
    say "💡 Another $(code spice) is still installed in $(path "$EXISTING"); remove it if you no longer need it."
  fi

  if [ "$have_completion" = 0 ]; then
    say "💡 Skipped tab completion (needs Docker + the $(code spice) image). Generate it later with:"
    say "   $(code "spice generate-completion > \"$COMPLETION_DIR/spice.bash\"")"
  fi

  if [ -n "$RC_FILES" ]; then
    say "✅ Shell init scripts set up: open a new shell to start using $(code spice)"
  else
    say "💡 To set up your shell, add these lines to its init script:"
    on_path "$INSTALL_DIR" || say "   $(code "export PATH=\"\$PATH:$INSTALL_DIR\"")"
    if credential_saved; then
      say "   $(code "[ -f \"$CREDENTIALS_FILE\" ] && . \"$CREDENTIALS_FILE\"")"
    fi
    say "   $(code "[ -f \"$COMPLETION_DIR/spice.bash\" ] && . \"$COMPLETION_DIR/spice.bash\"")"
  fi

  if [ -z "${SPICE_PASS:-}" ] && [ -z "${SPICE_LICENSE:-}" ] && ! credential_saved; then
    say "⚠️  Neither $(code SPICE_PASS) nor $(code SPICE_LICENSE) is set. Run the installer again to save one, or set it in your shell env:"
    say "   $(code "export SPICE_PASS=\"your-secret-token\"")"
    say "   $(code "export SPICE_LICENSE=\"your-license\"")"
  fi

  if ! command -v docker >/dev/null 2>&1; then
    say "⚠️  Docker is not installed or not in $(code PATH). The $(code spice) CLI uses Docker unless JVM mode is enabled."
    say "  → Install Docker from $(code https://docs.docker.com/get-docker/)"
  fi
}

main "$@"
