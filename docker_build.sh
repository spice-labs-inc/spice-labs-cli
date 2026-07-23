#!/usr/bin/env bash
# Build the spice-labs-cli Docker images locally.
#
# Uses BuildKit. Builds the OSS `spice` image by default, then layers the
# enterprise and federal images on top. Pass a target name as $1 to build
# only one stage (deps, builder, spice, test, enterprise, federal).
#
# Images are tagged:
#   ghcr.io/spice-labs-inc/spice-labs-cli:dev
#   ghcr.io/spice-labs-inc/spice-labs-cli-enterprise:dev
#   ghcr.io/spice-labs-inc/spice-labs-cli-federal:dev
#
# The Dockerfile's builder stage resolves spice-bom + spice-plugin-api from
# GitHub Packages (spice-labs-inc/spice-bom, spice-labs-inc/spice-plugin-api).
#
# The enterprise/federal images layer the allspice image on top of the OSS
# image. The allspice repo MUST be checked out beside this repo (../allspice)
# and its Docker image built first (../allspice/docker_build.sh).
#
# Private Maven dependencies (goatrodeo_3, ginger-j, ancho) require GitHub
# Packages auth. Set GH_TOKEN in the environment before building:
#
#   export GH_TOKEN=ghp_...
#   ./docker_build.sh
#
# The builder stage writes a settings.xml from $GH_TOKEN at build time. If
# GH_TOKEN is unset, the build will fail resolving those dependencies.

set -euo pipefail

TARGET="${1:-all}"
export DOCKER_BUILDKIT=1

OSS_IMAGE="ghcr.io/spice-labs-inc/spice-labs-cli"
ENTERPRISE_IMAGE="ghcr.io/spice-labs-inc/spice-labs-cli-enterprise"
FEDERAL_IMAGE="ghcr.io/spice-labs-inc/spice-labs-cli-federal"
TAG="dev"
# allspice has no semver release yet; pr-2 is the interim tag. For local dev,
# use the most recently built local allspice image if one exists. Otherwise fall
# back to the published interim tag. Override with ALLSPICE_IMAGE/ALLSPICE_TAG.
ALLSPICE_IMAGE="${ALLSPICE_IMAGE:-}"
ALLSPICE_TAG="${ALLSPICE_TAG:-}"
if [ -z "$ALLSPICE_IMAGE" ] && [ -z "$ALLSPICE_TAG" ]; then
  ALLSPICE_TAG=$(docker images --filter=reference='local_allspice:*' --format '{{.Tag}}' 2>/dev/null | head -1 || true)
  if [ -n "$ALLSPICE_TAG" ]; then
    ALLSPICE_IMAGE="local_allspice"
    echo "Using most recent local allspice image: ${ALLSPICE_IMAGE}:${ALLSPICE_TAG}"
  else
    ALLSPICE_IMAGE="ghcr.io/spice-labs-inc/allspice"
    ALLSPICE_TAG="pr-2"
    echo "No local allspice image found; using ${ALLSPICE_IMAGE}:${ALLSPICE_TAG}"
  fi
else
  ALLSPICE_IMAGE="${ALLSPICE_IMAGE:-ghcr.io/spice-labs-inc/allspice}"
  ALLSPICE_TAG="${ALLSPICE_TAG:-pr-2}"
fi

# --- pre-flight checks -------------------------------------------------------

if [ -z "${GH_TOKEN:-}" ]; then
  echo "WARNING: GH_TOKEN is not set. The build will fail if private"
  echo "Maven dependencies (goatrodeo_3, ginger-j, ancho) cannot be resolved."
  echo "  export GH_TOKEN=ghp_..."
  echo ""
fi

# --- build helpers ------------------------------------------------------------

build_oss() {
  echo "📦 Building OSS image: ${OSS_IMAGE}:${TAG}"
  docker build \
    --build-arg GH_TOKEN="${GH_TOKEN:-}" \
    -t "${OSS_IMAGE}:${TAG}" \
    --target spice \
    .
  echo "✅ Built ${OSS_IMAGE}:${TAG}"
}

build_enterprise() {
  echo "📦 Building enterprise image: ${ENTERPRISE_IMAGE}:${TAG}"
  docker build \
    -f Dockerfile.enterprise \
    --build-arg SPICE_IMAGE="${OSS_IMAGE}" \
    --build-arg SPICE_TAG="${TAG}" \
    --build-arg ALLSPICE_IMAGE="${ALLSPICE_IMAGE}" \
    --build-arg ALLSPICE_TAG="${ALLSPICE_TAG}" \
    -t "${ENTERPRISE_IMAGE}:${TAG}" \
    .
  echo "✅ Built ${ENTERPRISE_IMAGE}:${TAG}"
}

build_federal() {
  echo "📦 Building federal image: ${FEDERAL_IMAGE}:${TAG}"
  docker build \
    -f Dockerfile.federal \
    --build-arg SPICE_IMAGE="${OSS_IMAGE}" \
    --build-arg SPICE_TAG="${TAG}" \
    --build-arg ALLSPICE_IMAGE="${ALLSPICE_IMAGE}" \
    --build-arg ALLSPICE_TAG="${ALLSPICE_TAG}" \
    -t "${FEDERAL_IMAGE}:${TAG}" \
    .
  echo "✅ Built ${FEDERAL_IMAGE}:${TAG}"
}

# --- dispatch ----------------------------------------------------------------

case "${TARGET}" in
  all)
    build_oss
    build_enterprise
    build_federal
    echo ""
    echo "🎉 All images built:"
    echo "   ${OSS_IMAGE}:${TAG}"
    echo "   ${ENTERPRISE_IMAGE}:${TAG}"
    echo "   ${FEDERAL_IMAGE}:${TAG}"
    ;;
  spice|deps|builder|test)
    # Raw Dockerfile targets (no enterprise/federal layering)
    docker build \
      --build-arg GH_TOKEN="${GH_TOKEN:-}" \
      -t "${OSS_IMAGE}:${TAG}" \
      --target "${TARGET}" \
      . && echo "Successfully built ${OSS_IMAGE}:${TAG} (target: ${TARGET})"
    ;;
  enterprise)
    build_oss
    build_enterprise
    ;;
  federal)
    build_oss
    build_federal
    ;;
  *)
    echo "Unknown target: ${TARGET}"
    echo "Usage: $0 [all|spice|deps|builder|test|enterprise|federal]"
    exit 1
    ;;
esac
