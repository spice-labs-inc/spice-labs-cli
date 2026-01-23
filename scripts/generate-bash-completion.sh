#!/usr/bin/env bash
set -euo pipefail

# Generate a Bash completion script for the Spice Labs CLI using picocli.
# Output: target/spice.bash

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

CLI_MAIN_CLASS="io.spicelabs.cli.SpiceLabsCLI"
OUT_FILE="target/spice.bash"
CMD_NAME="spice"

# Ensure target/ exists
mkdir -p target

# Build (needed so dependencies/classpath are available)
mvn -DskipTests package

# Generate completion script
mvn -q -DskipTests \
  -Dexec.mainClass=picocli.AutoComplete \
  -Dexec.classpathScope=compile \
  -Dexec.args="--force --name ${CMD_NAME} --completionScript ${OUT_FILE} ${CLI_MAIN_CLASS}" \
  org.codehaus.mojo:exec-maven-plugin:3.3.0:java

# Ensure completion works for both "spice" and "./spice" (common during dev).
# Derive the completion function name from the generated script and register both.
fn="$(grep -E '^[[:space:]]*complete[[:space:]].*-F[[:space:]]+' "${OUT_FILE}" | sed -n 's/.*-F \([^ ]*\).*/\1/p' | head -n1 || true)"
if [ -n "${fn}" ]; then
  if ! grep -qE "^[[:space:]]*complete[[:space:]].*-F[[:space:]]+${fn}[[:space:]]+spice([[:space:]]|$)" "${OUT_FILE}"; then
    echo "complete -F ${fn} spice" >> "${OUT_FILE}"
  fi
  if ! grep -qE "^[[:space:]]*complete[[:space:]].*-F[[:space:]]+${fn}[[:space:]]+\\./spice([[:space:]]|$)" "${OUT_FILE}"; then
    echo "complete -F ${fn} ./spice" >> "${OUT_FILE}"
  fi
fi

echo "Generated: ${OUT_FILE}"
echo
echo "To enable for current shell:"
echo "  source ${OUT_FILE}"
echo
echo "To install for your user (Debian/Ubuntu):"
echo "  mkdir -p ~/.local/share/bash-completion/completions"
echo "  cp ${OUT_FILE} ~/.local/share/bash-completion/completions/${CMD_NAME}"
