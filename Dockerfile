# syntax=docker/dockerfile:1
# Multi-target Dockerfile for spice-labs-cli.
#
# Targets:
#   deps     — resolves the Maven dependency cache from pom.xml only, so it
#              caches across source changes. Published to GHCR as a build-cache
#              image (see .github/workflows/build.yml).
#   builder  — compiles spice-labs-cli + assembles the fat JAR + stages ancho.
#              Built FROM deps so the Maven cache is already warm.
#   spice    — runtime image: JRE + fat JAR + JFR config + wrapper scripts.
#   test     — deps + runs `mvn verify`. Used by CI so tests run against the
#              exact dependency image that produced the runtime JAR.
#
# This mirrors the allspice Dockerfile's structure (deps/builder/<runtime>/test)
# so CI can reuse the same build-deps -> test/build-image flow.

# ---- dependency cache -------------------------------------------------------
# Keyed only on pom.xml. Source changes do not invalidate this layer, so the
# Maven local repository is reused across every source-only commit.
# Pinned to $BUILDPLATFORM so Maven resolution runs on the build host's native
# arch (no QEMU emulation); the portable .m2 cache is shared to all platforms.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS deps
WORKDIR /workspace

ENV MAVEN_CONFIG="-B -ntp"

# Install Maven + the OS deps the runtime image will need later. git is
# required by the git-commit-id-maven-plugin at build time.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git maven \
        bash coreutils findutils \
    && rm -rf /var/lib/apt/lists/*

# Copy only the build-definition file. Any change here busts the cache; that is
# the whole point — a dependency change rebuilds the layer, a source change
# does not.
COPY pom.xml ./

# Pre-fetch all resolvable dependencies. spice-plugin-api, goatrodeo and
# ginger-j resolve from GitHub Packages (one repo each under spice-labs-inc),
# which needs auth even for public reads. The token is a BuildKit secret mount
# (never a build-arg): a throwaway settings.xml carries it into the resolve and
# is deleted before the layer commits, so it cannot land in the pushed cache
# image or in layer history. The || true lets the bulk of the Maven cache
# (picocli, slf4j, logback, junit, okhttp, etc.) be fetched regardless.
#
# -U because this layer is pushed to a registry cache and restored by later runs.
# Without it, a version that was missing when the layer was built stays missing:
# Maven records the failure in the local repo and honours that record instead of
# asking again. A dependency released after a failed build would then never
# resolve, however many times CI was re-run.
RUN --mount=type=secret,id=gh_token <<'SCRIPT'
set -eu
TOKEN=""
if [ -f /run/secrets/gh_token ]; then
  TOKEN="$(cat /run/secrets/gh_token)"
fi
mkdir -p ~/.m2
{
  echo "<settings>"
  echo "  <servers>"
  for id in github-spice-labs-goatrodeo github-spice-labs-ginger github-spice-labs-plugin-api github-spice-labs-ancho; do
    printf "    <server><id>%s</id><username>SpicyGrzl</username><password>%s</password></server>\n" "$id" "$TOKEN"
  done
  echo "  </servers>"
  echo "  <profiles>"
  echo "    <profile>"
  echo "      <id>github</id>"
  echo "      <repositories>"
  echo "        <repository><id>github-spice-labs-goatrodeo</id><url>https://maven.pkg.github.com/spice-labs-inc/goatrodeo</url></repository>"
  echo "        <repository><id>github-spice-labs-ginger</id><url>https://maven.pkg.github.com/spice-labs-inc/ginger-j</url></repository>"
  echo "        <repository><id>github-spice-labs-plugin-api</id><url>https://maven.pkg.github.com/spice-labs-inc/spice-plugin-api</url><snapshots><enabled>true</enabled></snapshots></repository>"
  echo "        <repository><id>github-spice-labs-ancho</id><url>https://maven.pkg.github.com/spice-labs-inc/ancho</url></repository>"
  echo "      </repositories>"
  echo "    </profile>"
  echo "  </profiles>"
  echo "  <activeProfiles><activeProfile>github</activeProfile></activeProfiles>"
  echo "</settings>"
} > ~/.m2/settings.xml
mvn -B -ntp -U dependency:resolve || true
rm -f ~/.m2/settings.xml
SCRIPT

# ---- builder ----------------------------------------------------------------
# Compiles spice-labs-cli and assembles the fat JAR. Built FROM deps so the
# Maven cache is already warm. GitHub Packages auth (goatrodeo_3, ginger-j,
# ancho) is supplied by the same BuildKit secret mount as the deps stage.
FROM deps AS builder

WORKDIR /workspace
COPY . .

# The fat JAR + ancho agent are assembled by the shade + dependency-plugin
# bindings in pom.xml. `package` produces:
#   target/spice-labs-cli-<version>.jar            (thin)
#   target/spice-labs-cli-<version>-fat.jar        (shaded, the runtime JAR)
#   target/ancho.jar                                (copied by maven-dependency-plugin)
# VERSION is set by the release workflow (publish.yml) so the JARs carry the
# release version; PR builds leave it unset and ship the pom's default
# (0.0.1-SNAPSHOT).
#
# The settings.xml is written for this RUN only from the mounted secret and
# deleted afterwards, so the token never reaches the image.
ARG VERSION=""
RUN --mount=type=secret,id=gh_token <<'SCRIPT'
set -eu
TOKEN=""
if [ -f /run/secrets/gh_token ]; then
  TOKEN="$(cat /run/secrets/gh_token)"
fi
mkdir -p ~/.m2
{
  echo "<settings>"
  echo "  <servers>"
  for id in github-spice-labs-goatrodeo github-spice-labs-ginger github-spice-labs-plugin-api github-spice-labs-ancho github; do
    printf "    <server><id>%s</id><username>SpicyGrzl</username><password>%s</password></server>\n" "$id" "$TOKEN"
  done
  echo "  </servers>"
  echo "  <profiles>"
  echo "    <profile>"
  echo "      <id>github</id>"
  echo "      <repositories>"
  echo "        <repository><id>github-spice-labs-goatrodeo</id><url>https://maven.pkg.github.com/spice-labs-inc/goatrodeo</url></repository>"
  echo "        <repository><id>github-spice-labs-ginger</id><url>https://maven.pkg.github.com/spice-labs-inc/ginger-j</url></repository>"
  echo "        <repository><id>github-spice-labs-plugin-api</id><url>https://maven.pkg.github.com/spice-labs-inc/spice-plugin-api</url><snapshots><enabled>true</enabled></snapshots></repository>"
  echo "        <repository><id>github-spice-labs-ancho</id><url>https://maven.pkg.github.com/spice-labs-inc/ancho</url></repository>"
  echo "      </repositories>"
  echo "    </profile>"
  echo "  </profiles>"
  echo "  <activeProfiles><activeProfile>github</activeProfile></activeProfiles>"
  echo "</settings>"
} > ~/.m2/settings.xml
if [ -n "${VERSION}" ]; then
  mvn -B -ntp versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false
fi
mvn -B -ntp -U -DskipTests package
rm -f ~/.m2/settings.xml
SCRIPT

# ---- test ------------------------------------------------------------------
# The test target reuses the deps cache and runs `mvn verify`. Used by CI
# (build.yml `test` job) so tests run against the exact dependency image that
# produced the runtime JAR. settings.xml is supplied by CI via env/secret.
# Declared BEFORE the runtime stage so the runtime `spice` stage is the
# default target (the last FROM in the file).
FROM deps AS test
WORKDIR /workspace
COPY . .
# spice-plugin-api is resolved remotely (see builder settings.xml).
ENTRYPOINT ["mvn", "-B", "-ntp"]
CMD ["verify"]

# ---- runtime ----------------------------------------------------------------
# Slim runtime: JRE only, no JDK, no Maven. The fat JAR is the only artifact;
# the JFR config and wrapper scripts mirror what the install/release flow ships.
FROM eclipse-temurin:21-jre AS spice
ARG VERSION="unknown"
# oras pulls OCI/Docker images by name for `spice survey image`. Baked in so no
# host-side oras (or docker daemon) is needed. TARGETARCH selects the matching
# release binary for multi-arch builds (linux/amd64, linux/arm64).
ARG ORAS_VERSION=1.3.3
ARG TARGETARCH
WORKDIR /opt/spice-labs-cli

# Install oras for image pulls. Needs a shell + download tool in the JRE image.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates tar \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/oras.tar.gz \
        "https://github.com/oras-project/oras/releases/download/v${ORAS_VERSION}/oras_${ORAS_VERSION}_linux_${TARGETARCH}.tar.gz" \
    && tar -xzf /tmp/oras.tar.gz -C /usr/local/bin oras \
    && chmod +x /usr/local/bin/oras \
    && rm -f /tmp/oras.tar.gz

# Expose the release version to the running process (set by publish.yml; PR
# builds default to "unknown").
ENV SPICE_VERSION=${VERSION}

COPY --from=builder /workspace/target/*-fat.jar ./spice-labs-cli.jar
COPY --from=builder /workspace/target/ancho.jar ./ancho.jar
# Plugin jars (empty in a public build); placed on the classpath alongside the CLI.
# Collected from plugins/*/dist/*.jar by the Maven build.
COPY --from=builder /workspace/target/plugins/ ./plugins/
COPY --from=builder /workspace/src/main/resources/jfr/spice-jfr.jfc ./spice-jfr.jfc
COPY --from=builder /workspace/spice ./spice
COPY --from=builder /workspace/spice.ps1 ./spice.ps1
# No path manifest is baked into the image: `spice path-manifest` renders it from the
# live command model, so it stays correct when the enterprise/federal images layer
# further plugins on top of this one. install.sh seeds it, and the wrapper refreshes
# it per image ID.

# -cp (not -jar) so plugins/* joins the classpath; the wildcard is quoted so the JVM,
# not the shell, expands it. Main class is named explicitly.
ENTRYPOINT ["sh", "-c", "\
  JVM_ARGS=\"${SPICE_LABS_JVM_ARGS:--XX:MaxRAMPercentage=75}\" && \
  exec java $JVM_ARGS -cp \"/opt/spice-labs-cli/spice-labs-cli.jar:/opt/spice-labs-cli/plugins/*\" io.spicelabs.cli.SpiceLabsCLI \"$@\"", "--"]
CMD ["--help"]
