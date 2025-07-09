FROM eclipse-temurin:21-jre
WORKDIR /opt/spice-labs-cli

RUN mkdir -p /mnt/input /mnt/output

COPY ./target/spice-labs-cli-*-fat.jar ./spice-labs-cli.jar
COPY spice ./spice
COPY spice.ps1 ./spice.ps1

ENTRYPOINT ["sh", "-c", "\
  JVM_ARGS=\"${SPICE_LABS_JVM_ARGS:--XX:MaximumHeapSizePercent=75}\" && \
  exec java $JVM_ARGS -jar /opt/spice-labs-cli/spice-labs-cli.jar \"$@\"", "--"]
