# Stage 1: Build with Maven and run tests
FROM maven:3.9.6-eclipse-temurin-21 as builder
WORKDIR /build

# Copy project files
COPY pom.xml .
COPY src ./src
COPY spice ./spice
COPY spice.ps1 ./spice.ps1

# Build fat jar and run tests
RUN mvn clean package -Pfatjar

# Stage 2: Runtime image with just the CLI
FROM eclipse-temurin:21-jre
WORKDIR /opt/spice-labs-cli

COPY --from=builder /build/target/spice-labs-cli-fat.jar ./spice-labs-cli.jar
COPY --from=builder /build/spice ./spice
COPY --from=builder /build/spice.ps1 ./spice.ps1

ENTRYPOINT ["java", "-jar", "/opt/spice-labs-cli/spice-labs-cli.jar"]
