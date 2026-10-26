# syntax=docker/dockerfile:1
# Build context: code/ (the Maven reactor root).
FROM maven:3.9-eclipse-temurin-21 AS build

ARG OTEL_AGENT_VERSION=2.10.0

WORKDIR /workspace
# Only the reactor pieces needed by the JDK 21 app shell. The other service
# modules are still referenced by the reactor, so their POMs must be present.
COPY pom.xml ./
COPY platform ./platform
COPY services ./services

RUN mvn -q -pl services/seckill-app-jdk21 -am -DskipTests package

# Fail the build if the pinned agent is not downloadable; tracing is not optional.
RUN curl -fsSL --retry 3 -o /opt/opentelemetry-javaagent.jar \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
    && test -s /opt/opentelemetry-javaagent.jar

# JDK (not JRE): the thread-lab snapshot bundle (design 9.3) runs
# `jcmd Thread.print -l`, `GC.heap_info` and `GC.class_histogram` inside the
# container, and `docker exec jl-app-N jcmd 1 Thread.print -l` is the teaching
# command. The cost is image size (~+180 MB), accepted for the local lab.
FROM eclipse-temurin:21-jdk-alpine

ARG OTEL_AGENT_VERSION=2.10.0
LABEL org.opencontainers.image.title="java-learn-lab seckill-app (JDK 21)" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT" \
      io.opentelemetry.javaagent.version="${OTEL_AGENT_VERSION}"

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /opt/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar
COPY --from=build /workspace/services/seckill-app-jdk21/target/seckill-app-jdk21-*.jar /app/app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

ENTRYPOINT ["java", \
  "-javaagent:/app/otel/opentelemetry-javaagent.jar", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
