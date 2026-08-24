#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2025-26 Spice Labs, Inc. & Contributors
#
# Regenerates the two spliced regions in the wrapper scripts:
#
#   BEGIN/END SHARED path-manifest.bash  — the canonical argument-walking code,
#     kept in shared/path-manifest.bash so `spice` and allspice's wrapper cannot
#     drift apart.
#   BEGIN/END GENERATED PATH MANIFEST    — the built-in commands' path manifest,
#     the last-resort fallback when the image cannot be queried.
#
# Run after changing shared/path-manifest.bash or any command's options.
# PathManifestGoldenTest fails the build when these regions are stale.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SHARED="shared/path-manifest.bash"
MANIFEST="target/spice.path-manifest"

# ── Render the built-ins manifest ────────────────────────────────────────────

echo "Rendering ${MANIFEST}..."
mvn -q -DskipTests compile
CP_FILE="$(mktemp)"
trap 'rm -f "$CP_FILE"' EXIT
mvn -q dependency:build-classpath "-Dmdep.outputFile=$CP_FILE" -Dmdep.includeScope=runtime
mkdir -p target
java -cp "target/classes:$(cat "$CP_FILE")" io.spicelabs.cli.PathManifest "$MANIFEST"

# ── Splice ───────────────────────────────────────────────────────────────────

# splice <file> <begin-marker> <end-marker> <payload-file>
splice() {
  local file="$1" begin="$2" end="$3" payload="$4" tmp
  grep -qF "$begin" "$file" || { echo "❌ $file: missing marker $begin" >&2; exit 1; }
  grep -qF "$end" "$file" || { echo "❌ $file: missing marker $end" >&2; exit 1; }
  tmp="$(mktemp)"
  awk -v begin="$begin" -v end="$end" -v payload="$payload" '
    index($0, begin) { print; while ((getline line < payload) > 0) print line; skip = 1; next }
    index($0, end)   { skip = 0 }
    !skip            { print }
  ' "$file" >"$tmp"
  mv "$tmp" "$file"
  chmod +x "$file" 2>/dev/null || true
}

BODY="$(mktemp)"
trap 'rm -f "$CP_FILE" "$BODY"' EXIT

# The shared library, verbatim.
cp "$SHARED" "$BODY"
splice spice "# ── BEGIN SHARED path-manifest.bash ──" \
             "# ── END SHARED path-manifest.bash ──" "$BODY"

# The manifest, wrapped in the function the library calls for its final fallback.
{
  echo "mf_embedded_manifest() {"
  echo "  cat <<'SPICE_EMBEDDED_MANIFEST'"
  cat "$MANIFEST"
  echo "SPICE_EMBEDDED_MANIFEST"
  echo "}"
} >"$BODY"
splice spice "# ── BEGIN GENERATED PATH MANIFEST ──" \
             "# ── END GENERATED PATH MANIFEST ──" "$BODY"

# PowerShell mirrors the same library and the same manifest.
if [ -f spice.ps1 ]; then
  cp shared/path-manifest.ps1 "$BODY"
  splice spice.ps1 "# ── BEGIN SHARED path-manifest.ps1 ──" \
                   "# ── END SHARED path-manifest.ps1 ──" "$BODY"
  {
    echo "\$MfEmbeddedManifest = @'"
    cat "$MANIFEST"
    echo "'@"
  } >"$BODY"
  splice spice.ps1 "# ── BEGIN GENERATED PATH MANIFEST ──" \
                   "# ── END GENERATED PATH MANIFEST ──" "$BODY"
fi

bash -n spice
echo "✅ spice and spice.ps1 updated from $SHARED"
