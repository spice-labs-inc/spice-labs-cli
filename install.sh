#!/usr/bin/env bash
set -euo pipefail

# To Install : `curl -sSfL https://install.spicelabs.io | bash`

TARGET_DIR="${HOME}/.local/bin"
DATA_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}/spice"
COMPLETION_DIR="${HOME}/.local/share/spice/completions"
SCRIPT_URL="https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice"

echo "📦 Installing spice to ${TARGET_DIR}"

mkdir -p "$TARGET_DIR"
mkdir -p "$COMPLETION_DIR"
curl -fsSL "$SCRIPT_URL" -o "$TARGET_DIR/spice"
chmod +x "$TARGET_DIR/spice"

# Generate plugin-aware tab completion from the configured image (no static fallback):
# the script reflects whatever plugins (e.g. `registry`) that image ships. Needs Docker
# and the image; skipped gracefully otherwise. SKIP_PULL keeps stdout clean for capture.
IMAGE="${SPICE_IMAGE:-spicelabs/spice-labs-cli}"
TAG="${SPICE_IMAGE_TAG:-latest}"
if command -v docker &>/dev/null && docker pull -q "${IMAGE}:${TAG}" &>/dev/null \
   && SPICE_LABS_CLI_SKIP_PULL=1 "$TARGET_DIR/spice" generate-completion > "$COMPLETION_DIR/spice.bash.tmp" 2>/dev/null \
   && [ -s "$COMPLETION_DIR/spice.bash.tmp" ]; then
  mv "$COMPLETION_DIR/spice.bash.tmp" "$COMPLETION_DIR/spice.bash"
  echo "✅ Generated tab completion from ${IMAGE}:${TAG}"
else
  rm -f "$COMPLETION_DIR/spice.bash.tmp"
  echo "💡 Skipped tab completion (needs Docker + the spice image). Generate it later with:"
  echo "   spice generate-completion > \"$COMPLETION_DIR/spice.bash\""
fi

# Seed the path manifest from the same image, so the first invocation already
# knows which arguments to bind-mount — including any plugin's. The wrapper
# refreshes this itself when the image changes; this only removes the cost from
# the first run. Skipped gracefully, exactly like completion above.
mkdir -p "$DATA_DIR"
if SPICE_LABS_CLI_SKIP_PULL=1 SPICE_SKIP_MANIFEST_REFRESH=1 \
     "$TARGET_DIR/spice" path-manifest > "$DATA_DIR/path-manifest.tmp" 2>/dev/null \
   && grep -q '^# spice-path-manifest ' "$DATA_DIR/path-manifest.tmp"; then
  mv "$DATA_DIR/path-manifest.tmp" "$DATA_DIR/path-manifest"
else
  rm -f "$DATA_DIR/path-manifest.tmp"
fi

if [[ ":$PATH:" != *":$TARGET_DIR:"* ]]; then
  echo "⚠️  ${TARGET_DIR} is not in your PATH. Add this line to your shell profile:"
  echo "  export PATH=\"\$PATH:$TARGET_DIR\""
else
  echo "✅ spice installed and ready to use"
fi

# ── Tab completion ───────────────────────────────────────────────────────────

BASH_SOURCE_LINE="[ -f \"$COMPLETION_DIR/spice.bash\" ] && source \"$COMPLETION_DIR/spice.bash\""
ZSH_SOURCE_BLOCK="autoload -U +X bashcompinit 2>/dev/null && bashcompinit 2>/dev/null
[ -f \"$COMPLETION_DIR/spice.bash\" ] && source \"$COMPLETION_DIR/spice.bash\""
COMPLETION_INSTALLED=0
for rc in "${HOME}/.bashrc" "${HOME}/.zshrc"; do
  [ -f "$rc" ] || continue
  case "$rc" in
    *.zshrc)
      if ! grep -qF "bashcompinit" "$rc" 2>/dev/null || ! grep -qF "$COMPLETION_DIR/spice.bash" "$rc" 2>/dev/null; then
        printf '\n# Spice CLI tab completion\n%s\n' "$ZSH_SOURCE_BLOCK" >> "$rc"
      fi
      ;;
    *)
      if ! grep -qF "$COMPLETION_DIR/spice.bash" "$rc" 2>/dev/null; then
        printf '\n# Spice CLI tab completion\n%s\n' "$BASH_SOURCE_LINE" >> "$rc"
      fi
      ;;
  esac
  COMPLETION_INSTALLED=1
done
if [ "$COMPLETION_INSTALLED" = "1" ]; then
  echo "✅ Tab completion installed (restart your shell or source your profile to activate)"
else
  echo "💡 To enable tab completion, add this to your shell profile:"
  echo "  $BASH_SOURCE_LINE"
fi

if [[ -z "${SPICE_PASS:-}" ]]; then
  echo "⚠️  SPICE_PASS is not set. Set it in your shell env to use the CLI:"
  echo "  export SPICE_PASS=\"your-secret-token\""
fi

if ! command -v docker &> /dev/null; then
  echo "⚠️  Docker is not installed or not in PATH. The spice CLI uses Docker unless JVM mode is enabled."
  echo "  → Install Docker from https://docs.docker.com/get-docker/"
fi
