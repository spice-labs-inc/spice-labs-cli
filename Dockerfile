FROM eclipse-temurin:21-jre
WORKDIR /opt/spice-labs-cli

COPY --from=anchore/syft:v1.30.0 /syft /usr/bin
COPY ./target/spice-labs-cli-*-fat.jar ./spice-labs-cli.jar
COPY ./target/ancho.jar ./ancho.jar
# Plugin jars (empty in a public build); placed on the classpath alongside the CLI.
COPY ./target/plugins/ ./plugins/
COPY ./src/main/resources/jfr/spice-jfr.jfc ./spice-jfr.jfc
COPY spice ./spice
COPY spice.ps1 ./spice.ps1


# -cp (not -jar) so plugins/* joins the classpath; the wildcard is quoted so the JVM,
# not the shell, expands it. Main class is named explicitly.
ENTRYPOINT ["sh", "-c", "\
  JVM_ARGS=\"${SPICE_LABS_JVM_ARGS:--XX:MaxRAMPercentage=75}\" && \
  exec java $JVM_ARGS -cp \"/opt/spice-labs-cli/spice-labs-cli.jar:/opt/spice-labs-cli/plugins/*\" io.spicelabs.cli.SpiceLabsCLI \"$@\"", "--"]
