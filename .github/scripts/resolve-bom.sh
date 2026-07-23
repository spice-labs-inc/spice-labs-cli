#!/usr/bin/env bash
set -euo pipefail

# At release time we want the latest PUBLISHED BOM, not the in-repo SNAPSHOT.
# Ask GitHub Packages for the newest semver version of io.spicelabs:spice-bom.
BOM_VERSION="$(gh api "/orgs/spice-labs-inc/packages/maven/io.spicelabs.spice-bom/versions" --paginate \
  --jq '[.[] | .name | select(test("^[0-9]+\\.[0-9]+\\.[0-9]+$"))] | sort | last')"

if [ -z "${BOM_VERSION}" ]; then
  echo "Failed to resolve the latest published io.spicelabs:spice-bom version from GitHub Packages" >&2
  exit 1
fi

echo "Releasing CLI against spice-bom ${BOM_VERSION}"
mvn --batch-mode dependency:get "-Dartifact=io.spicelabs:spice-bom:${BOM_VERSION}:pom"
mvn --batch-mode versions:set-property -Dproperty=spice-bom.version "-DnewVersion=${BOM_VERSION}" -DgenerateBackupPoms=false
