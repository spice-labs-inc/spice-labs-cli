#!/usr/bin/env bash
set -euo pipefail

# To Install : `curl -sSf https://install.spicelabs.io | bash`

TARGET_DIR="${HOME}/.local/bin"
SCRIPT_URL="https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice"

echo "📦 Installing spice to ${TARGET_DIR}"

mkdir -p "$TARGET_DIR"
curl -fsSL "$SCRIPT_URL" -o "$TARGET_DIR/spice"
chmod +x "$TARGET_DIR/spice"

if [[ ":$PATH:" != *":$TARGET_DIR:"* ]]; then
  echo "⚠️  ${TARGET_DIR} is not in your PATH. Add this line to your shell profile:"
  echo "  export PATH=\"\$PATH:$TARGET_DIR\""
else
  echo "✅ spice installed and ready to use"
fi

if [[ -z "${SPICE_PASS:-}" ]]; then
  echo "⚠️  SPICE_PASS is not set. Set it in your shell env to use the CLI:"
  echo "  export SPICE_PASS=\"your-secret-token\""
fi

if ! command -v docker &> /dev/null; then
  echo "⚠️  Docker is not installed or not in PATH. The spice CLI uses Docker unless JVM mode is enabled."
  echo "  → Install Docker from https://docs.docker.com/get-docker/"
fi
