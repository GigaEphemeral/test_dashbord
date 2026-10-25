# platform-observability

Reusable observability starter for the JDK 21 Spring Boot application shells.

## Usage

```xml
<dependency>
  <groupId>com.javalearn.lab</groupId>
  <artifactId>platform-observability</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `spring.application.name` | `app` | Value of the common `app` metric tag and JSON log field |
| `observability.env` | `local` | Value of the common `env` metric tag and JSON log field |
| `observability.instance` | `local` | Value of the common `instance` metric tag and JSON log field |

Set all three in each application shell, for example:

```yaml
spring:
  application:
    name: seckill-app
observability:
  env: dev
  instance: app-1
```

## What the starter provides

- Micrometer common tags on every meter: `app`, `env`, `instance`, `jvm.version`
  (rendered as `jvm_version` by Prometheus).
- Actuator endpoint exposure defaults: `health,info,metrics,prometheus,threaddump,env`.
- `http.server.requests` percentiles histogram and SLO buckets from 50ms to 2s,
  with metrics labeled by URI template (`/products/{id}`, never the raw path).
- JSON console logging through the bundled `logback-spring.xml`: UTC timestamps,
  MDC entries, and the `app`/`env`/`instance` fields.

All values above are defaults; properties defined by the application take precedence.

## Metrics endpoint

Scrape Prometheus text format from:

```
GET /actuator/prometheus
```

## Trace correlation

The starter does not pull in an OpenTelemetry SDK or tracing client. Attach the
OTel Java agent to the application JVM (`-javaagent:opentelemetry-javaagent.jar`);
the agent injects trace/span identifiers into the MDC and the bundled JSON
encoder emits them alongside the existing log fields.
