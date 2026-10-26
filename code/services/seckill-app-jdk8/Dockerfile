# syntax=docker/dockerfile:1
# D7 dual-JDK comparison (design section 10): the Java 8 shell.
# Build context: code/ (the Maven reactor root), same as the JDK 21 image.
#
# The build runs on Temurin 21 because the reactor's Maven plugins require a
# modern JDK; the module itself compiles with maven.compiler.release=8, so the
# produced classes are Java 8 bytecode (class file version 52) and load on the
# JRE 8 runtime stage below.
FROM maven:3.9-eclipse-temurin-21 AS build

ARG OTEL_AGENT_VERSION=2.10.0

WORKDIR /workspace
# Only the reactor pieces needed by the JDK 8 shell. The other service modules
# are still referenced by the reactor, so their POMs must be present.
COPY pom.xml ./
COPY platform ./platform
COPY services ./services

RUN mvn -q -pl services/seckill-app-jdk8 -am -DskipTests package

# Same OTel Java agent version as the JDK 21 instances: the startup-time
# comparison must not be skewed by instrumenting one runtime and not the
# other. The agent supports Java 8. Fail the build if it is not downloadable.
RUN curl -fsSL --retry 3 -o /opt/opentelemetry-javaagent.jar \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
    && test -s /opt/opentelemetry-javaagent.jar

# JRE 8 (not JDK): this shell is a read-only comparison target, and the thread
# lab / snapshot features that need jcmd live only in the JDK 21 shell.
FROM eclipse-temurin:8-jre-alpine

ARG OTEL_AGENT_VERSION=2.10.0
LABEL org.opencontainers.image.title="java-learn-lab seckill-app (JDK 8, EOL teaching-only)" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT" \
      io.opentelemetry.javaagent.version="${OTEL_AGENT_VERSION}"

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /opt/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar
COPY --from=build /workspace/services/seckill-app-jdk8/target/seckill-app-jdk8-*.jar /app/app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

# Heap cap is injected through JAVA_TOOL_OPTIONS by compose (design 10.2:
# -Xmx512m on both runtimes) so the flag stays externalized. Everything else
# mirrors the JDK 21 entrypoint flags that keep the comparison honest.
ENTRYPOINT ["java", \
  "-javaagent:/app/otel/opentelemetry-javaagent.jar", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
