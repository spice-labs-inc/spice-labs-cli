# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Spice Labs, Inc. & Contributors
#
# ─────────────────────────────────────────────────────────────────────────────
# Path-manifest driven argument walking.
#
# CANONICAL SOURCE: spice-labs-cli/shared/path-manifest.bash
# This block is spliced verbatim into the `spice` wrapper and vendored into
# allspice's `allspice` wrapper. Edit it here; CI checks the copies match.
#
# The wrappers run on the HOST, before `docker run`, so they must know which
# arguments are host filesystem paths before the JVM (and its picocli model)
# exists. Rather than hardcode a flag list — which drifted from the commands,
# and could never cover third-party plugins — they read a *path manifest*
# rendered from the live command model inside the image. See PathManifest.java.
#
# Mounting strategy: identity mounts (`-v $dir:$dir`), so a path's container
# location equals its host location and arguments need only be absolutised.
# The one exception is a path under a reserved directory (mounting the host's
# /opt over the image's would destroy the installation), which is remapped to
# /mnt/spice/N and recorded in SPICE_PATH_MAP for PathTranslator to reverse.
#
# Bash 3.2 (macOS system bash) — no associative arrays, no ${var,,}, no mapfile.
# Lookup tables are therefore single strings delimited by \001 (record) and
# \002 (key/value), searched with parameter expansion rather than loops.
# ─────────────────────────────────────────────────────────────────────────────

MF_REC=$'\001'
MF_SEP=$'\002'

MF_ROOT="spice"
MF_CMDS=""            # \001<cmdpath>\001…  — command nodes
MF_ATTRS=""           # \001<cmdpath>|<name>\002<attrs>\001…  — scoped lookup
MF_INHERITED=""       # same, but only args marked `inherit`
MF_FLAT=""            # \001<name>\002<attrs>\001…  — union across all commands
MF_RESERVED_EXACT=""  # :/:/usr:…: — matched exactly, never by prefix
MF_RESERVED_PREFIX="" # :/opt/allspice:…: — matched by prefix

# Outputs, consumed by the caller after walk_args.
MF_ARGS=()
MF_VOLUMES=()
MF_PATH_MAP=()
MF_RESULT=""

MF_SEEN_DIRS=""       # :dir:… — directories already mounted
MF_IDENTITY_DIRS=":"  # :dir:… — of those, the ones mounted at their host path
MF_MOUNT_MAP=""       # \001<hostdir>\002<containerdir>\001… — assigned targets
MF_MOUNT_N=0

# ── Loading ──────────────────────────────────────────────────────────────────

# Resolve a manifest into MF_* tables. Every tier is best-effort: an unreadable
# file, a failed `docker run`, or an image too old to know `path-manifest` all
# fall through to the next tier, and the embedded built-ins manifest is the
# floor. This must never abort the CLI — at worst the wrapper behaves as it did
# before manifests existed.
mf_load() {
  local text=""

  # 1. Explicit override (tests, air-gapped installs).
  if [ -n "${SPICE_PATH_MANIFEST:-}" ] && [ -f "${SPICE_PATH_MANIFEST}" ]; then
    text="$(cat "${SPICE_PATH_MANIFEST}" 2>/dev/null)" || text=""
  fi

  # 2. Per-image cache, refreshed from the image when cold.
  if [ -z "$text" ]; then
    text="$(mf_refresh)" || text=""
  fi

  # 3. Manifest installed alongside the wrapper by install.sh. Each wrapper sets
  #    MF_INSTALLED_MANIFEST to its own file: loading another CLI's manifest
  #    would describe a command tree this wrapper never sees.
  if [ -z "$text" ]; then
    local installed="${MF_INSTALLED_MANIFEST:-${XDG_DATA_HOME:-$HOME/.local/share}/spice/path-manifest}"
    [ -f "$installed" ] && { text="$(cat "$installed" 2>/dev/null)" || text=""; }
  fi

  # 4. Built-ins, embedded in this script at build time.
  if [ -z "$text" ]; then
    text="$(mf_embedded_manifest)" || text=""
  fi

  mf_parse "$text"
}

# Echo the manifest for $IMAGE_REF, using a cache keyed by the image ID so a
# `docker pull` invalidates it automatically — no TTL, no staleness heuristics.
mf_refresh() {
  [ "${SPICE_SKIP_MANIFEST_REFRESH:-0}" = "1" ] && return 1
  command -v docker >/dev/null 2>&1 || return 1

  local image_id cache_dir cache_file
  image_id="$(docker image inspect --format '{{.Id}}' "$IMAGE_REF" 2>/dev/null)" || return 1
  [ -n "$image_id" ] || return 1

  cache_dir="${SPICE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/spice}/path-manifest"
  # Image IDs are `sha256:<hex>`; the colon is legal in a filename but awkward.
  cache_file="${cache_dir}/${image_id#sha256:}"

  if [ -s "$cache_file" ]; then
    cat "$cache_file" 2>/dev/null
    return 0
  fi

  local text
  text="$(docker run --rm ${PULL_FLAG:+$PULL_FLAG} "$IMAGE_REF" path-manifest 2>/dev/null)" || return 1
  case "$text" in
    *"# spice-path-manifest "*) ;;
    *) return 1 ;;  # an image too old to know the command, or log noise only
  esac

  if mkdir -p "$cache_dir" 2>/dev/null; then
    printf '%s\n' "$text" >"$cache_file" 2>/dev/null || true
    # Keep the cache from growing without bound as images come and go.
    # Cache entries are named after image IDs, so they are always plain hex —
    # `ls` parsing is safe here in a way it would not be for arbitrary names.
    # shellcheck disable=SC2012
    {
      ls -1t "$cache_dir" 2>/dev/null | tail -n +11 | while read -r stale; do
        rm -f "${cache_dir}/${stale}" 2>/dev/null || true
      done
    } || true
  fi
  printf '%s\n' "$text"
}

# Build the lookup tables from manifest text. Unrecognised lines are ignored
# rather than rejected, so log output that leaks onto stdout inside the
# container cannot corrupt the tables.
mf_parse() {
  local kind a b rest key attrs first_cmd=""
  MF_CMDS=""; MF_ATTRS=""; MF_INHERITED=""; MF_FLAT=""
  MF_RESERVED_EXACT=":"; MF_RESERVED_PREFIX=":"

  # Matched anywhere rather than at the start: a warning logged to stdout inside
  # the container would otherwise be enough to reject an otherwise good manifest.
  case "$1" in
    *"# spice-path-manifest 1"$'\n'*) ;;
    *) return 1 ;;  # unknown schema version — leave the tables empty
  esac

  while read -r kind a b rest; do
    case "$kind" in
      C)
        [ -n "$a" ] || continue
        [ -n "$first_cmd" ] || first_cmd="$a"
        MF_CMDS="${MF_CMDS}${MF_REC}${a}"
        ;;
      O|P)
        # A record needs both a command path and a name to be usable at all.
        if [ -z "$a" ] || [ -z "$b" ]; then continue; fi
        # Positionals are keyed by index so they cannot collide with a flag.
        [ "$kind" = "P" ] && b="#${b}"
        key="${a}|${b}"
        attrs=" ${rest} "
        MF_ATTRS="${MF_ATTRS}${MF_REC}${key}${MF_SEP}${attrs}"
        case "$attrs" in *" inherit "*)
          MF_INHERITED="${MF_INHERITED}${MF_REC}${key}${MF_SEP}${attrs}" ;;
        esac
        [ "$kind" = "O" ] && mf_flat_merge "$b" "$attrs"
        ;;
      R)  [ -n "$a" ] && MF_RESERVED_EXACT="${MF_RESERVED_EXACT}${a}:" ;;
      RP) [ -n "$a" ] && MF_RESERVED_PREFIX="${MF_RESERVED_PREFIX}${a}:" ;;
    esac
  done <<EOF
$1
EOF

  MF_CMDS="${MF_CMDS}${MF_REC}"
  MF_ATTRS="${MF_ATTRS}${MF_REC}"
  MF_INHERITED="${MF_INHERITED}${MF_REC}"
  MF_FLAT="${MF_FLAT}${MF_REC}"
  [ -n "$first_cmd" ] && MF_ROOT="$first_cmd"
  return 0
}

# Merge one option's attributes into the flat (command-agnostic) fallback table.
# The union is deliberately conservative: `create=parent` and never `exists`, so
# that falling back can neither create a directory where a file belongs nor
# reject a path the command would have accepted.
mf_flat_merge() {
  local name="$1" attrs="$2" existing merged
  existing="$(mf_raw_lookup "$MF_FLAT" "$name")" || existing=""
  merged=" "
  case "${existing}${attrs}" in *" value "*) merged="${merged}value " ;; esac
  case "${existing}${attrs}" in *" path "*) merged="${merged}path create=parent " ;; esac
  case "${existing}${attrs}" in *" hostonly "*) merged="${merged}hostonly " ;; esac
  # Prepended, not replaced: mf_raw_lookup finds the first match, so the newest
  # merge wins and the superseded record is simply never read.
  MF_FLAT="${MF_REC}${name}${MF_SEP}${merged}${MF_FLAT}"
}

# ── Lookup ───────────────────────────────────────────────────────────────────

# Echo the attribute string stored for $2 in table $1, or nothing.
#
# The delimiters and the key are quoted so they match literally: only the
# leading `*` is meant as a wildcard. Unquoted, an option name containing a
# glob character would be matched as a pattern rather than looked up.
mf_raw_lookup() {
  local table="$1" key="$2" rest
  rest="${table#*"${MF_REC}${key}${MF_SEP}"}"
  [ "$rest" = "$table" ] && return 1
  printf '%s' "${rest%%"${MF_REC}"*}"
}

# Resolve the attributes of <cmdpath> <name> into MF_A, trying in order:
# the exact command, then any ancestor that declares the arg as inherited, then
# the flat union. The flat tier exists so that a manifest older than the image
# (or missing entirely) degrades to roughly the previous behaviour instead of
# mis-parsing the command line.
mf_attrs() {
  local cmdpath="$1" name="$2" ancestor
  MF_A="$(mf_raw_lookup "$MF_ATTRS" "${cmdpath}|${name}")" && return 0
  ancestor="$cmdpath"
  while [ "$ancestor" != "${ancestor%/*}" ]; do
    ancestor="${ancestor%/*}"
    MF_A="$(mf_raw_lookup "$MF_INHERITED" "${ancestor}|${name}")" && return 0
  done
  MF_A="$(mf_raw_lookup "$MF_FLAT" "$name")" && return 0
  MF_A=""
  return 1
}

mf_is_cmd() {
  case "$MF_CMDS" in *"${MF_REC}${1}${MF_REC}"*) return 0 ;; esac
  return 1
}

# Whether $1 is the leaf name of any command, at any depth. Used where a branch
# scans arguments without tracking its position in the command tree.
mf_is_cmd_token() {
  case "$MF_CMDS" in *"/${1}${MF_REC}"*) return 0 ;; esac
  return 1
}

mf_has() { # $1=attrs $2=attribute
  case "$1" in *" ${2} "*) return 0 ;; esac
  return 1
}

mf_takes_value() { mf_attrs "$1" "$2" || return 1; mf_has "$MF_A" value; }
mf_is_hostonly() { mf_attrs "$1" "$2" || return 1; mf_has "$MF_A" hostonly; }
mf_is_path() { mf_attrs "$1" "$2" || return 1; mf_has "$MF_A" path; }

# Whether a directory must not be shadowed by a bind mount. Filesystem roots
# match exactly — /var must be protected while the macOS temp dirs beneath it
# (/var/folders/…) stay mountable — whereas install directories match by prefix,
# since everything below /opt/allspice belongs to the image.
mf_reserved() {
  local dir="$1" prefix rest
  case "$MF_RESERVED_EXACT" in *":${dir}:"*) return 0 ;; esac
  rest="${MF_RESERVED_PREFIX#:}"
  while [ -n "$rest" ]; do
    prefix="${rest%%:*}"
    rest="${rest#*:}"
    [ -n "$prefix" ] || continue
    [ "$dir" = "$prefix" ] && return 0
    case "$dir" in "${prefix}"/*) return 0 ;; esac
  done
  return 1
}

# ── Mounting ─────────────────────────────────────────────────────────────────

# Resolve one path argument for use inside the container, adding whatever bind
# mount it needs, and leave the result in MF_RESULT.
#
# Sets a global rather than echoing because command substitution would run this
# in a subshell and discard the mounts it registers.
#
# $1 value  $2 create mode (self|parent)  $3 must-already-exist (0|1)
mount_path() {
  local value="$1" create="$2" must_exist="$3"
  local dir dir_abs abs target container

  [[ "$value" == ~* ]] && value="${value/#\~/$HOME}"
  MF_RESULT="$value"

  # A URL or a bare `key=value` is not a host path even on a Path-typed option.
  case "$value" in *://*) return 0 ;; esac

  if [ -e "$value" ]; then
    if [ -d "$value" ]; then dir="$value"; else dir="$(dirname "$value")"; fi
  elif [ "$must_exist" = "1" ]; then
    echo "ERROR ❌ Input path does not exist: $value" >&2
    echo "INFO  Use --help for usage information." >&2
    exit 2
  elif [ "$create" = "self" ]; then
    mkdir -p "$value" 2>/dev/null || return 0
    dir="$value"
  else
    dir="$(dirname "$value")"
    mkdir -p "$dir" 2>/dev/null || return 0
  fi

  # If the directory still isn't there, the value was probably never a path.
  # Pass it through untouched and let the CLI produce the real diagnostic.
  [ -d "$dir" ] || return 0
  dir_abs="$(cd "$dir" 2>/dev/null && pwd)" || return 0
  if [ -d "$value" ]; then
    abs="$dir_abs"
  else
    abs="${dir_abs%/}/$(basename "$value")"
  fi

  target="$(mf_raw_lookup "$MF_MOUNT_MAP" "$dir_abs")" || target=""
  if [ -z "$target" ] && mf_under_identity_mount "$dir_abs"; then
    # Already visible through an ancestor's identity mount; a nested bind mount
    # would add nothing.
    target="$dir_abs"
  fi
  if [ -z "$target" ]; then
    if mf_reserved "$dir_abs"; then
      # Mounting here would hide part of the image, so relocate and record the
      # mapping for PathTranslator to reverse in user-facing messages.
      MF_MOUNT_N=$((MF_MOUNT_N + 1))
      target="/mnt/spice/${MF_MOUNT_N}"
      MF_PATH_MAP+=("${target}:${dir_abs}")
    else
      target="$dir_abs"
    fi
    MF_MOUNT_MAP="${MF_MOUNT_MAP}${MF_REC}${dir_abs}${MF_SEP}${target}${MF_REC}"
    mf_mount "$dir_abs" "$target"
  fi

  # Quoted so a directory whose name contains a glob character is stripped
  # literally rather than matched as a pattern.
  container="${target}${abs#"$dir_abs"}"
  MF_RESULT="$container"
}

# Add a deduped bind mount, recording identity mounts so nested paths can reuse
# them instead of stacking a redundant mount inside one.
mf_mount() {
  local host="$1" container="$2"
  case ":$MF_SEEN_DIRS:" in *":${host}:"*) return 0 ;; esac
  MF_SEEN_DIRS="${MF_SEEN_DIRS}:${host}"
  [ "$host" = "$container" ] && MF_IDENTITY_DIRS="${MF_IDENTITY_DIRS}${host}:"
  MF_VOLUMES+=("-v" "${host}:${container}")
  return 0
}

# Whether $1 is already reachable inside the container via an identity mount of
# one of its ancestors.
mf_under_identity_mount() {
  local dir="$1" rest prefix
  rest="${MF_IDENTITY_DIRS#:}"
  while [ -n "$rest" ]; do
    prefix="${rest%%:*}"
    rest="${rest#*:}"
    [ -n "$prefix" ] || continue
    case "$dir" in "${prefix}"/*) return 0 ;; esac
  done
  return 1
}

# ── Argument walking ─────────────────────────────────────────────────────────

# Rewrite "$@" into MF_ARGS, mounting every argument the manifest calls a path.
walk_args() {
  local cmdpath="$MF_ROOT" posidx=0 pending="" endopts=0
  local arg flag val create must_exist
  MF_ARGS=()

  for arg in "$@"; do
    if [ "$endopts" = "1" ]; then
      MF_ARGS+=("$arg")
      continue
    fi

    # The value of a flag seen on the previous iteration.
    if [ -n "$pending" ]; then
      if mf_is_hostonly "$cmdpath" "$pending"; then
        : # the wrapper handles it; strip both tokens
      elif mf_is_path "$cmdpath" "$pending"; then
        mf_create_args "$MF_A"
        mount_path "$arg" "$create" "$must_exist"
        MF_ARGS+=("$pending" "$MF_RESULT")
      else
        MF_ARGS+=("$pending" "$arg")
      fi
      pending=""
      continue
    fi

    if [ "$arg" = "--" ]; then
      endopts=1
      MF_ARGS+=("$arg")
      continue
    fi

    if [[ "$arg" == -* ]]; then
      flag="${arg%%=*}"
      if [ "$flag" != "$arg" ]; then
        val="${arg#*=}"
        if mf_is_hostonly "$cmdpath" "$flag"; then
          continue
        elif mf_is_path "$cmdpath" "$flag"; then
          mf_create_args "$MF_A"
          mount_path "$val" "$create" "$must_exist"
          MF_ARGS+=("${flag}=${MF_RESULT}")
        else
          MF_ARGS+=("$arg")
        fi
      elif mf_takes_value "$cmdpath" "$arg"; then
        pending="$arg"
      elif mf_is_hostonly "$cmdpath" "$arg"; then
        : # a valueless host-only flag
      else
        MF_ARGS+=("$arg")
      fi
      continue
    fi

    # A positional token descends into a subcommand only before any positional
    # has been consumed here, so a subject that happens to be named `run` or
    # `status` is still treated as a subject.
    if [ "$posidx" = "0" ] && mf_is_cmd "${cmdpath}/${arg}"; then
      cmdpath="${cmdpath}/${arg}"
      MF_ARGS+=("$arg")
      continue
    fi

    if mf_attrs "$cmdpath" "#${posidx}" && mf_has "$MF_A" path; then
      mf_create_args "$MF_A"
      mount_path "$arg" "$create" "$must_exist"
      MF_ARGS+=("$MF_RESULT")
    else
      MF_ARGS+=("$arg")
    fi
    posidx=$((posidx + 1))
  done

  # A trailing flag with no value: pass it through so picocli reports it.
  [ -n "$pending" ] && MF_ARGS+=("$pending")
  return 0
}

# Split an attribute string into the two parameters mount_path needs.
mf_create_args() {
  case "$1" in *" create=self "*) create="self" ;; *) create="parent" ;; esac
  case "$1" in *" exists "*) must_exist="1" ;; *) must_exist="0" ;; esac
}
