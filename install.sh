#!/usr/bin/env bash
set -euo pipefail

# To Install : `curl -sSfL https://install.spicelabs.io | bash`

TARGET_DIR="${HOME}/.local/bin"
COMPLETION_DIR="${HOME}/.local/share/spice/completions"
SCRIPT_URL="https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice"
COMPLETION_URL="https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice.bash"

echo "📦 Installing spice to ${TARGET_DIR}"

mkdir -p "$TARGET_DIR"
mkdir -p "$COMPLETION_DIR"
curl -fsSL "$SCRIPT_URL" -o "$TARGET_DIR/spice"
chmod +x "$TARGET_DIR/spice"
curl -fsSL "$COMPLETION_URL" -o "$COMPLETION_DIR/spice.bash" 2>/dev/null || true

if [[ ":$PATH:" != *":$TARGET_DIR:"* ]]; then
  echo "⚠️  ${TARGET_DIR} is not in your PATH. Add this line to your shell profile:"
  echo "  export PATH=\"\$PATH:$TARGET_DIR\""
else
  echo "✅ spice installed and ready to use"
fi

# ── Tab completion ───────────────────────────────────────────────────────────

SOURCE_LINE="[ -f \"$COMPLETION_DIR/spice.bash\" ] && source \"$COMPLETION_DIR/spice.bash\""
COMPLETION_INSTALLED=0
for rc in "${HOME}/.bashrc" "${HOME}/.zshrc"; do
  [ -f "$rc" ] || continue
  if ! grep -qF "$COMPLETION_DIR/spice.bash" "$rc" 2>/dev/null; then
    printf '\n# Spice CLI tab completion\n%s\n' "$SOURCE_LINE" >> "$rc"
  fi
  COMPLETION_INSTALLED=1
done
if [ "$COMPLETION_INSTALLED" = "1" ]; then
  echo "✅ Tab completion installed (restart your shell or source your profile to activate)"
else
  echo "💡 To enable tab completion, add this to your shell profile:"
  echo "  $SOURCE_LINE"
fi

if [[ -z "${SPICE_PASS:-}" ]]; then
  echo "⚠️  SPICE_PASS is not set. Set it in your shell env to use the CLI:"
  echo "  export SPICE_PASS=\"your-secret-token\""
fi

if ! command -v docker &> /dev/null; then
  echo "⚠️  Docker is not installed or not in PATH. The spice CLI uses Docker unless JVM mode is enabled."
  echo "  → Install Docker from https://docs.docker.com/get-docker/"
fi
