#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

CLI_MAIN_CLASS="io.spicelabs.cli.SpiceLabsCLI"
OUT_FILE="target/spice.bash"
CMD_NAME="spice"

mkdir -p target

mvn -q -DskipTests package

mvn -q -DskipTests \
  -Dexec.mainClass=picocli.AutoComplete \
  -Dexec.classpathScope=runtime \
  -Dexec.args="--force --name ${CMD_NAME} --completionScript ${OUT_FILE} ${CLI_MAIN_CLASS}" \
  org.codehaus.mojo:exec-maven-plugin:3.3.0:java

# Sanity check that important current options are present
for opt in --command --log-level --ginger-args --goat-rodeo-args --use-static-metadata; do
  if ! grep -q -- "$opt" "$OUT_FILE"; then
    echo "Completion generation failed sanity check: missing $opt in $OUT_FILE" >&2
    exit 1
  fi
done

fn="$(
  grep -E '^[[:space:]]*complete[[:space:]].*-F[[:space:]]+' "${OUT_FILE}" \
    | sed -n 's/.*-F \([^ ]*\).*/\1/p' \
    | head -n1 || true
)"

if [ -n "${fn}" ]; then
  grep -qE "^[[:space:]]*complete[[:space:]].*-F[[:space:]]+${fn}[[:space:]]+spice([[:space:]]|$)" "${OUT_FILE}" \
    || echo "complete -F ${fn} spice" >> "${OUT_FILE}"

  grep -qE "^[[:space:]]*complete[[:space:]].*-F[[:space:]]+${fn}[[:space:]]+\\./spice([[:space:]]|$)" "${OUT_FILE}" \
    || echo "complete -F ${fn} ./spice" >> "${OUT_FILE}"

  grep -qE "^[[:space:]]*complete[[:space:]].*-F[[:space:]]+${fn}[[:space:]]+${HOME}/\.local/bin/spice([[:space:]]|$)" "${OUT_FILE}" \
    || echo "complete -F ${fn} ${HOME}/.local/bin/spice" >> "${OUT_FILE}"
fi

echo "Generated: ${OUT_FILE}"
echo
echo "To enable for current shell:"
echo "  source ${OUT_FILE}"
echo
echo "To install for your user:"
echo "  mkdir -p ~/.local/share/bash-completion/completions"
echo "  cp ${OUT_FILE} ~/.local/share/bash-completion/completions/${CMD_NAME}"