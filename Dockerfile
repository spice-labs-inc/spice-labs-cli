# Multi-target Dockerfile for spice-labs-cli.
#
# Targets:
#   deps     — resolves the Maven dependency cache from pom.xml only, so it
#              caches across source changes. Published to GHCR as a build-cache
#              image (see .github/workflows/build.yml).
#   builder  — compiles spice-labs-cli + assembles the fat JAR + stages ancho.
#              Built FROM deps so the Maven cache is already warm.
#   spice    — runtime image: JRE + fat JAR + syft + JFR config + wrapper scripts.
#   test     — deps + runs `mvn verify`. Used by CI so tests run against the
#              exact dependency image that produced the runtime JAR.
#
# This mirrors the allspice Dockerfile's structure (deps/builder/<runtime>/test)
# so CI can reuse the same build-deps -> test/build-image flow.

# ---- dependency cache -------------------------------------------------------
# Keyed only on pom.xml. Source changes do not invalidate this layer, so the
# Maven local repository is reused across every source-only commit.
FROM eclipse-temurin:21-jdk AS deps
WORKDIR /workspace

ENV MAVEN_CONFIG="-B -ntp"

# Install Maven + the OS deps the runtime image will need later (syft is added
# in the runtime stage via a COPY --from). git is required by the
# git-commit-id-maven-plugin at build time.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git maven \
        bash coreutils findutils \
    && rm -rf /var/lib/apt/lists/*

# Copy only the build-definition file. Any change here busts the cache; that is
# the whole point — a dependency change rebuilds the layer, a source change
# does not.
COPY pom.xml ./

# Pre-fetch all resolvable dependencies. spice-bom + spice-plugin-api live in
# this repo (bom/, shared/plugin-api/) and are installed in the builder stage,
# so this resolve fails on those — the || true lets the bulk of the Maven cache
# (picocli, slf4j, logback, junit, okhttp, etc.) be fetched regardless.
RUN mvn -B -ntp dependency:resolve || true

# ---- builder ----------------------------------------------------------------
# Compiles spice-labs-cli and assembles the fat JAR. Built FROM deps so the
# Maven cache is already warm. The settings.xml providing GitHub Packages auth
# for goatrodeo_3 / ginger-j / ancho is supplied at build time via the
# GITHUB_TOKEN env; CI writes it before invoking mvn.
FROM deps AS builder

WORKDIR /workspace
COPY . .

# Write a settings.xml for GitHub Packages auth (goatrodeo_3, ginger-j, ancho).
# GH_TOKEN is passed as a build arg from CI or docker_build.sh.
ARG GH_TOKEN=""
RUN mkdir -p ~/.m2 && cat > ~/.m2/settings.xml <<SXML
<settings>
  <servers>
    <server><id>github-spice-labs-goatrodeo</id><username>SpicyGrzl</username><password>${GH_TOKEN}</password></server>
    <server><id>github-spice-labs-ginger</id><username>SpicyGrzl</username><password>${GH_TOKEN}</password></server>
    <server><id>github-spice-labs-ancho</id><username>SpicyGrzl</username><password>${GH_TOKEN}</password></server>
    <server><id>github</id><username>SpicyGrzl</username><password>${GH_TOKEN}</password></server>
  </servers>
  <profiles>
    <profile>
      <id>default-repos</id>
      <repositories>
        <repository><id>github-spice-labs-goatrodeo</id><url>https://maven.pkg.github.com/spice-labs-inc/goatrodeo</url></repository>
        <repository><id>github-spice-labs-ginger</id><url>https://maven.pkg.github.com/spice-labs-inc/ginger-j</url></repository>
        <repository><id>github-spice-labs-ancho</id><url>https://maven.pkg.github.com/spice-labs-inc/ancho</url></repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>default-repos</activeProfile></activeProfiles>
</settings>
SXML

# Install the in-repo shared modules (the plugin SPI and the BOM) into the local
# Maven repo first, so the CLI build resolves spice-plugin-api version-less
# through the imported spice-bom. These live here (shared/plugin-api, bom/), not
# in a peer repo.
RUN mvn -B -ntp -DskipTests install -f shared/plugin-api/pom.xml \
 && mvn -B -ntp install -f bom/pom.xml

# The fat JAR + ancho agent are assembled by the shade + dependency-plugin
# bindings in pom.xml. `package` produces:
#   target/spice-labs-cli-<version>.jar            (thin)
#   target/spice-labs-cli-<version>-fat.jar        (shaded, the runtime JAR)
#   target/ancho.jar                                (copied by maven-dependency-plugin)
RUN mvn -B -ntp -DskipTests package

# ---- runtime ----------------------------------------------------------------
# Slim runtime: JRE only, no JDK, no Maven. The fat JAR is the only artifact;
# syft is layered in for SBOM generation; the JFR config and wrapper scripts
# mirror what the install/release flow ships.
FROM eclipse-temurin:21-jre AS spice
WORKDIR /opt/spice-labs-cli

COPY --from=anchore/syft:v1.30.0 /syft /usr/bin/syft
COPY --from=builder /workspace/target/*-fat.jar ./spice-labs-cli.jar
COPY --from=builder /workspace/target/ancho.jar ./ancho.jar
# Plugin jars (empty in a public build); placed on the classpath alongside the CLI.
# Collected from plugins/*/dist/*.jar by the Maven build.
COPY --from=builder /workspace/target/plugins/ ./plugins/
COPY --from=builder /workspace/src/main/resources/jfr/spice-jfr.jfc ./spice-jfr.jfc
COPY --from=builder /workspace/spice ./spice
COPY --from=builder /workspace/spice.ps1 ./spice.ps1

# -cp (not -jar) so plugins/* joins the classpath; the wildcard is quoted so the JVM,
# not the shell, expands it. Main class is named explicitly.
ENTRYPOINT ["sh", "-c", "\
  JVM_ARGS=\"${SPICE_LABS_JVM_ARGS:--XX:MaxRAMPercentage=75}\" && \
  exec java $JVM_ARGS -cp \"/opt/spice-labs-cli/spice-labs-cli.jar:/opt/spice-labs-cli/plugins/*\" io.spicelabs.cli.SpiceLabsCLI \"$@\"", "--"]
CMD ["--help"]

# ---- test ------------------------------------------------------------------
# The test target reuses the deps cache and runs `mvn verify`. Used by CI
# (build.yml `test` job) so tests run against the exact dependency image that
# produced the runtime JAR. settings.xml is supplied by CI via env/secret.
FROM deps AS test
WORKDIR /workspace
COPY . .
# Same in-repo shared modules the builder installs, so `mvn verify` resolves
# spice-plugin-api + spice-bom.
RUN mvn -B -ntp -DskipTests install -f shared/plugin-api/pom.xml \
 && mvn -B -ntp install -f bom/pom.xml
ENTRYPOINT ["mvn", "-B", "-ntp"]
CMD ["verify"]
