# Stage: Runtime image with prebuilt CLI JAR
FROM eclipse-temurin:21-jre
WORKDIR /opt/spice-labs-cli

# Copy in the pre-downloaded fat JAR and CLI wrappers
COPY spice-labs-cli-fat.jar ./spice-labs-cli.jar
COPY spice ./spice
COPY spice.ps1 ./spice.ps1

ENTRYPOINT ["java", "-jar", "/opt/spice-labs-cli/spice-labs-cli.jar"]
