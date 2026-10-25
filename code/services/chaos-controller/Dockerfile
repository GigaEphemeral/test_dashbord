# syntax=docker/dockerfile:1
# Build context: code/ (the Maven reactor root).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
# Only the reactor pieces needed by the controller. The other service modules
# are still referenced by the reactor, so their POMs must be present.
COPY pom.xml ./
COPY platform ./platform
COPY services ./services

RUN mvn -q -pl services/chaos-controller -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="java-learn-lab chaos-controller" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT"

WORKDIR /app
COPY --from=build /workspace/services/chaos-controller/target/chaos-controller-*.jar /app/app.jar

# Lab-only: runs as root so it can open the mounted Docker socket. The token
# filter plus the lab=java-learn whitelist in the gateway are the guards.
EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
