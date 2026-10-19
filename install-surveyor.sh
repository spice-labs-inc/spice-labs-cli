#!/bin/sh
# To Install : `curl -sSfL https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/install-surveyor.sh | sh`
#
# Interactive when a terminal is available: asks for a credential, where to
# install, and which shell init scripts to update. Docker must be installed and
# running. A Spice License selects its edition's image, which is pulled through
# the Spice Labs registry (SPICE_REGISTRY overrides it) after logging in to it;
# otherwise the image is spicelabs/spice-labs-cli, from Docker Hub. Options,
# which also work when piped (`curl ... | sh -s -- --yes`):
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
DEFAULT_IMAGE="spicelabs/spice-labs-cli:${SPICE_IMAGE_TAG:-latest}"
# The registry (bael) that the licensed editions' images are pulled through
REGISTRY="${SPICE_REGISTRY:-surveyor.spicelabs.io}"
DEFAULT_DIR="${HOME}/.local/bin"
DATA_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}/spice"
COMPLETION_DIR="${HOME}/.local/share/spice/completions"
CONFIG_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/spice"
CREDENTIALS_FILE="$CONFIG_DIR/credentials"
ENVIRONMENT_FILE="$CONFIG_DIR/environment"
DOCKER_CONFIG_FILE="${DOCKER_CONFIG:-${HOME}/.docker}/config.json"
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
# (dir, file or login), the target, and the backup of what it replaced (empty if
# it replaced nothing). PREPARED lists the new files staged beside their targets.
#
# A registry login is undone by logging out, but only if there was no login for
# that registry before: Docker can't give back a login it has replaced. A pulled
# image isn't removed, as it's only a cache, and removing it would just mean
# downloading it again on the next attempt.

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
      login) docker logout "$target" >/dev/null 2>&1 || true ;;
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

# Removes TARGET, keeping a backup so rollback can restore it
commit_remove() {
  rm_backup="$1.spice-old.$$"
  cp -p "$1" "$rm_backup"
  JOURNAL="file$NL$1$NL$rm_backup$NL$JOURNAL"
  rm -f "$1"
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

# Reads a long line, such as a pasted credential, which canonical mode would
# truncate at 1024 bytes on macOS. With canonical mode off, editing keys are
# handled here: Backspace, and Ctrl-U to erase everything. Each character is
# echoed as MASK, if given, or else as itself. The terminal's own echo is off
# before the prompt appears, so nothing typed after it is shown unmasked.
ask_long() {
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
        *) REPLY="$REPLY$as_c"; as_echo="$as_echo${2:-$as_c}" ;;
      esac
    done
    printf '%s' "$as_echo" >/dev/tty
  done
  stty "$STTY_SAVED" </dev/tty
  STTY_SAVED=""
  printf '\n' >/dev/tty
}

# ── Credential ───────────────────────────────────────────────────────────────
#
# A Spice License and a Spice Pass are both JWTs. Their claims are read here
# without verifying the signature, only to describe the credential and to choose
# the image for its edition: spice verifies a License itself when it runs.

CRED_VAR=""
CRED_VALUE=""

credential_saved() { [ -f "$CREDENTIALS_FILE" ]; }

# The value of VAR in the credentials file, if it's there
saved_value() {
  [ -f "$CREDENTIALS_FILE" ] || return 0
  sed -n "s/^export $1='\(.*\)'\$/\1/p" "$CREDENTIALS_FILE" | tail -n 1
}

b64url_decode() {
  bd=$(printf '%s' "$1" | tr -- '-_' '+/')
  case $((${#bd} % 4)) in
    2) bd="$bd==" ;;
    3) bd="$bd=" ;;
  esac
  # macOS before 13 has only -D; openssl is the last resort
  bd_out=$(printf '%s' "$bd" | base64 -d 2>/dev/null) \
    || bd_out=$(printf '%s' "$bd" | base64 -D 2>/dev/null) \
    || bd_out=$(printf '%s' "$bd" | openssl base64 -d -A 2>/dev/null) \
    || return 1
  printf '%s' "$bd_out"
}

# The value of the string or number claim NAME in JSON. A pattern, not a parser:
# enough for the flat claims read here, and nothing depends on it but messages
# and the choice of image.
claim() {
  printf '%s' "$1" | tr -d '\n' \
    | sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\).*/\1/p'
}

# Sets KIND (license, pass or unknown), EDITION, and EXPIRES (in seconds since
# the epoch) for the credential TOKEN
inspect_credential() {
  KIND=unknown
  EDITION=""
  EXPIRES=""
  case "$1" in
    *.*.*) ;;
    *) return 0 ;;
  esac
  ic_header=$(b64url_decode "$(printf '%s' "$1" | cut -d. -f1)") || return 0
  ic_payload=$(b64url_decode "$(printf '%s' "$1" | cut -d. -f2)") || return 0
  # The same markers spice checks: a License's typ header and x-type claim
  ic_type=$(claim "$ic_payload" x-type)
  if [ "$(claim "$ic_header" typ)" = spice-license+jwt ] || [ "$ic_type" = license ]; then
    KIND=license
  elif [ -n "$ic_type" ]; then
    KIND=pass
  fi
  EDITION=$(claim "$ic_payload" x-edition)
  EXPIRES=$(claim "$ic_payload" exp)
  case "$EXPIRES" in
    *[!0-9]*) EXPIRES="" ;;
  esac
}

expired() { [ -n "$EXPIRES" ] && [ "$EXPIRES" -lt "$(date +%s)" ]; }

# Formats seconds since the epoch as a date, if this system's date can
format_date() {
  date -u -r "$1" '+%-d %B %Y' 2>/dev/null || date -u -d "@$1" '+%-d %B %Y' 2>/dev/null
}

# The editions, as named in surveyor's editions.json
edition_title() {
  case "$1" in
    cli) printf 'Surveyor CLI' ;;
    otpro) printf 'Surveyor OT Pro' ;;
    it) printf 'Surveyor IT' ;;
    itpro) printf 'Surveyor IT Pro' ;;
    *) printf '%s' "$1" ;;
  esac
}

# The image for EDITION, pulled through the registry; nothing for the default
edition_image() {
  case "$1" in
    otpro|it|itpro) printf '%s/spice-labs-inc/surveyor-%s:latest' "$REGISTRY" "$1" ;;
  esac
}

# Describes the credential last inspected, e.g. "a Spice License for Surveyor
# IT Pro, valid until 1 January 2028"
describe_credential() {
  case "$KIND" in
    license)
      dc="a Spice License"
      [ -z "$EDITION" ] || dc="$dc for $(edition_title "$EDITION")"
      ;;
    pass) dc="a Spice Pass" ;;
    *) printf 'a credential of an unknown form'; return 0 ;;
  esac
  if [ -n "$EXPIRES" ]; then
    dc_date=$(format_date "$EXPIRES") || dc_date=""
    if expired; then
      if [ -n "$dc_date" ]; then dc="$dc, which expired on $dc_date"; else dc="$dc, which has expired"; fi
    elif [ -n "$dc_date" ]; then
      dc="$dc, valid until $dc_date"
    fi
  fi
  printf '%s' "$dc"
}

choose_credential() {
  say ""
  say "🔑 $(code spice) needs a Spice License or a Spice Pass to run."
  cc_have=0
  for cc_var in SPICE_LICENSE SPICE_PASS; do
    eval "cc_value=\${$cc_var:-}"
    if [ -n "$cc_value" ]; then
      inspect_credential "$cc_value"
      say "   $(code "$cc_var") is set in this shell: $(describe_credential)."
      cc_have=1
    fi
    cc_value=$(saved_value "$cc_var")
    if [ -n "$cc_value" ]; then
      inspect_credential "$cc_value"
      say "   $(code "$cc_var") is saved in $(path "$CREDENTIALS_FILE"): $(describe_credential)."
      cc_have=1
    fi
  done
  if [ "$cc_have" = 1 ]; then cc_skip="keep it"; else cc_skip="skip"; fi
  say ""
  say "Paste your Spice License or Spice Pass, or the path to a file containing it."

  while :; do
    CRED_VAR=""
    ask_long "Credential [Enter to $cc_skip]: "
    cc_input=$REPLY
    case "$cc_input" in
      "~"/*) cc_input="$HOME/${cc_input#"~/"}" ;;
    esac
    if [ -f "$cc_input" ]; then
      cc_input=$(cat "$cc_input")
    fi
    CRED_VALUE=$(printf '%s' "$cc_input" | tr -d '[:space:]')
    [ -n "$CRED_VALUE" ] || return 0
    # The credentials file must parse the same way in sh and fish, so the value
    # is limited to the characters a token can contain.
    case "$CRED_VALUE" in
      *[!A-Za-z0-9._~+/=-]*)
        say "   That isn't a Spice License or a Spice Pass. Try again, or press Enter to $cc_skip."
        continue
        ;;
    esac

    inspect_credential "$CRED_VALUE"
    case "$KIND" in
      license) CRED_VAR=SPICE_LICENSE ;;
      pass) CRED_VAR=SPICE_PASS ;;
      *)
        say "   This doesn't look like a Spice License or a Spice Pass. Which is it?"
        say ""
        say "  1) Spice License: issued to your organization by Spice Labs"
        say "  2) Spice Pass: downloaded from your Spice Labs project dashboard"
        say "  3) Neither: enter it again"
        say ""
        while :; do
          ask "Please select [3]: " 3
          case "$REPLY" in
            1) CRED_VAR=SPICE_LICENSE; break ;;
            2) CRED_VAR=SPICE_PASS; break ;;
            3) break ;;
          esac
        done
        [ -n "$CRED_VAR" ] || continue
        ;;
    esac
    [ "$KIND" = unknown ] || say "   This is $(describe_credential)."
    if expired && ! confirm "   Save it anyway?" n; then continue; fi
    return 0
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

# ── Image ────────────────────────────────────────────────────────────────────
#
# The image is the one for the edition of the Spice License that spice will run
# with, saved as SPICE_IMAGE in the environment file, which the wrapper uses
# instead of its default. A SPICE_IMAGE the user set themselves is left alone.

IMAGE_REF=""
SAVE_IMAGE=""
ENV_ACTION=""
LICENSE_EDITION=""
NEED_LOGIN=0
REGISTRY_PASSWORD=""

# Sets LICENSE_EDITION from the first License among: the credential entered now,
# then those set in this shell or saved, under either variable
find_license_edition() {
  LICENSE_EDITION=""
  for fl_value in "$CRED_VALUE" "${SPICE_LICENSE:-}" "$(saved_value SPICE_LICENSE)" \
                  "${SPICE_PASS:-}" "$(saved_value SPICE_PASS)"; do
    [ -n "$fl_value" ] || continue
    inspect_credential "$fl_value"
    if [ "$KIND" = license ]; then
      LICENSE_EDITION=$EDITION
      return 0
    fi
  done
}

choose_image() {
  ci_ours=""
  if [ -f "$ENVIRONMENT_FILE" ]; then
    ci_ours=$(sed -n "s/^export SPICE_IMAGE='\(.*\)'\$/\1/p" "$ENVIRONMENT_FILE" | tail -n 1)
  fi
  if [ -n "${SPICE_IMAGE:-}" ] && [ "$SPICE_IMAGE" != "$ci_ours" ]; then
    IMAGE_REF=$SPICE_IMAGE
  else
    find_license_edition
    SAVE_IMAGE=$(edition_image "$LICENSE_EDITION")
    IMAGE_REF=${SAVE_IMAGE:-$DEFAULT_IMAGE}
    if [ -n "$SAVE_IMAGE" ] && [ "$SAVE_IMAGE" != "$ci_ours" ]; then
      ENV_ACTION="write"
    elif [ -z "$SAVE_IMAGE" ] && [ -f "$ENVIRONMENT_FILE" ]; then
      ENV_ACTION="remove"
    fi
  fi
  case "$IMAGE_REF" in
    "$REGISTRY"/*) NEED_LOGIN=1 ;;
  esac
}

# Whether the environment file will exist once the installation is done
environment_kept() {
  [ "$ENV_ACTION" = write ] || { [ -f "$ENVIRONMENT_FILE" ] && [ "$ENV_ACTION" != remove ]; }
}

write_environment() {
  say "# Settings for spice, loaded by the spice block in your shell init script."
  say "# The spice installer rewrites this file when run again."
  say "export SPICE_IMAGE='$SAVE_IMAGE'"
}

# The registry can't yet accept a Spice License or Spice Pass, so for now the
# login is the user "download", with the password in SPICE_PASSWORD. Once it
# can, log in with the credential itself instead, and drop SPICE_PASSWORD.
LOGIN_USER=download

choose_registry_password() {
  REGISTRY_PASSWORD=${SPICE_PASSWORD:-}
  if [ -z "$REGISTRY_PASSWORD" ] && [ "$INTERACTIVE" = 1 ]; then
    say ""
    say "🔐 Downloading $(edition_title "$LICENSE_EDITION") needs a login to $(code "$REGISTRY")."
    ask_long "Password for $(code "$LOGIN_USER"): " "*"
    REGISTRY_PASSWORD=$REPLY
  fi
  [ -n "$REGISTRY_PASSWORD" ] \
    || die "Logging in to $(code "$REGISTRY") needs a password. Set $(code SPICE_PASSWORD) to it, then run the installer again."
}

# Logs in to the registry, journalling the login if there wasn't one already
registry_login() {
  rl_new=1
  if [ -f "$DOCKER_CONFIG_FILE" ] && grep -qF "\"$REGISTRY\"" "$DOCKER_CONFIG_FILE"; then
    rl_new=0
  fi
  if ! printf '%s' "$REGISTRY_PASSWORD" \
       | docker login "$REGISTRY" --username "$LOGIN_USER" --password-stdin >/dev/null 2>"$STAGE/login.err"; then
    die "Couldn't log in to $(code "$REGISTRY"): $(cat "$STAGE/login.err")"
  fi
  [ "$rl_new" = 0 ] || JOURNAL="login$NL$REGISTRY$NL$NL$JOURNAL"
}

check_docker() {
  command -v docker >/dev/null 2>&1 \
    || die "spice needs Docker, which isn't installed. Install it from $(code https://docs.docker.com/get-docker/), then run the installer again."
  docker info >/dev/null 2>&1 \
    || die "Docker is installed, but isn't running. Start it, then run the installer again."
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
  rb_env=0
  if environment_kept; then rb_env=1; fi

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
      if [ "$rb_env" = 1 ]; then
        say "if test -f \"$ENVIRONMENT_FILE\""
        say "    source \"$ENVIRONMENT_FILE\""
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
      if [ "$rb_env" = 1 ]; then
        say "if [ -f \"$ENVIRONMENT_FILE\" ]; then . \"$ENVIRONMENT_FILE\"; fi"
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
  say "Usage: $(code "install-surveyor.sh [--yes] [--dir DIR] [--no-modify-rc]")"
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

  check_docker

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
  choose_image
  [ "$NEED_LOGIN" = 0 ] || choose_registry_password
  [ "$MODIFY_RC" = 0 ] || choose_rc
  while IFS= read -r f; do
    [ -z "$f" ] || check_rc "$f"
  done <<EOF
$RC_FILES
EOF

  # 2. Write the new files into a staging directory, and confirm.

  STAGE=$(mktemp -d)
  [ -z "$CRED_VAR" ] || write_credentials "$STAGE/credentials"
  [ "$ENV_ACTION" != write ] || write_environment > "$STAGE/environment"

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

  say ""
  say "Ready to install:"
  say "  • $(code spice) in $(path "$INSTALL_DIR")"
  [ -z "$CRED_VAR" ] || say "  • $(code "$CRED_VAR") saved in $(path "$CREDENTIALS_FILE")"
  [ "$NEED_LOGIN" = 0 ] || say "  • a Docker login to $(code "$REGISTRY")"
  say "  • the image $(code "$IMAGE_REF"), with tab completion from it"
  case "$ENV_ACTION" in
    write) say "  • $(code SPICE_IMAGE) saved in $(path "$ENVIRONMENT_FILE")" ;;
    remove) say "  • $(path "$ENVIRONMENT_FILE") removed, so $(code spice) uses its default image" ;;
  esac
  while IFS= read -r n && IFS= read -r f; do
    [ -z "$f" ] || say "  • $(code spice) set up in $(path "$f")"
  done <<EOF
$rc_changed
EOF
  if [ "$INTERACTIVE" = 1 ] && ! confirm "Proceed with installation?" y; then
    say "Nothing was changed."
    exit 0
  fi

  # 3. Download the script and the image, and generate from the image.

  say ""
  say "📦 Downloading $(code spice)"
  curl -fsSL "$SCRIPT_URL" -o "$STAGE/spice" || die "Couldn't download $(code "$SCRIPT_URL")."
  IFS= read -r first < "$STAGE/spice" || first=""
  case "$first" in
    "#!"*) ;;
    *) die "The download from $(code "$SCRIPT_URL") isn't a script." ;;
  esac
  chmod +x "$STAGE/spice"

  [ "$NEED_LOGIN" = 0 ] || registry_login
  say "📦 Downloading $(code "$IMAGE_REF")"
  if [ -t 1 ]; then
    docker pull "$IMAGE_REF" || die "Couldn't download $(code "$IMAGE_REF")."
  else
    docker pull -q "$IMAGE_REF" >/dev/null || die "Couldn't download $(code "$IMAGE_REF")."
  fi

  # Plugin-aware tab completion and the path manifest both come from the image
  # (no static fallback), so they reflect whatever plugins (e.g. `registry`) it
  # ships, and are skipped gracefully if they can't be generated. SKIP_PULL
  # keeps stdout clean for capture. The manifest tells the first invocation
  # which arguments to bind-mount; the wrapper refreshes it itself when the
  # image changes.
  have_completion=0
  if SPICE_IMAGE="$IMAGE_REF" SPICE_LABS_CLI_SKIP_PULL=1 \
       "$STAGE/spice" generate-completion > "$STAGE/spice.bash" 2>/dev/null \
     && [ -s "$STAGE/spice.bash" ]; then
    have_completion=1
  fi
  have_manifest=0
  if SPICE_IMAGE="$IMAGE_REF" SPICE_LABS_CLI_SKIP_PULL=1 SPICE_SKIP_MANIFEST_REFRESH=1 \
       "$STAGE/spice" path-manifest > "$STAGE/path-manifest" 2>/dev/null \
     && grep -q '^# spice-path-manifest ' "$STAGE/path-manifest"; then
    have_manifest=1
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
  if [ "$ENV_ACTION" = write ]; then
    make_dir "$CONFIG_DIR"
    prepare_file "$STAGE/environment" "$ENVIRONMENT_FILE"
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
  case "$ENV_ACTION" in
    write) commit_file "$ENVIRONMENT_FILE" ;;
    remove) commit_remove "$ENVIRONMENT_FILE" ;;
  esac
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

  if [ -n "$LICENSE_EDITION" ]; then
    say "✅ Using $(edition_title "$LICENSE_EDITION"), from $(code "$IMAGE_REF")"
  fi

  if [ "$have_completion" = 0 ]; then
    say "💡 Couldn't generate tab completion from the image. Generate it later with:"
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
    if [ -f "$ENVIRONMENT_FILE" ]; then
      say "   $(code "[ -f \"$ENVIRONMENT_FILE\" ] && . \"$ENVIRONMENT_FILE\"")"
    fi
    say "   $(code "[ -f \"$COMPLETION_DIR/spice.bash\" ] && . \"$COMPLETION_DIR/spice.bash\"")"
  fi

  if [ -z "${SPICE_PASS:-}" ] && [ -z "${SPICE_LICENSE:-}" ] && ! credential_saved; then
    say "⚠️  Neither $(code SPICE_PASS) nor $(code SPICE_LICENSE) is set. Run the installer again to save one, or set it in your shell env:"
    say "   $(code "export SPICE_PASS=\"your-secret-token\"")"
    say "   $(code "export SPICE_LICENSE=\"your-license\"")"
  fi
}

main "$@"
