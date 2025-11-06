FROM eclipse-temurin:21-jre
WORKDIR /opt/spice-labs-cli

COPY --from=anchore/syft:v1.30.0 /syft /usr/bin
COPY ./target/spice-labs-cli-*-fat.jar ./spice-labs-cli.jar
COPY spice ./spice
COPY spice.ps1 ./spice.ps1


ENTRYPOINT ["sh", "-c", "\
  JVM_ARGS=\"${SPICE_LABS_JVM_ARGS:--XX:MaxRAMPercentage=75}\" && \
  exec java $JVM_ARGS -jar /opt/spice-labs-cli/spice-labs-cli.jar \"$@\"", "--"]
