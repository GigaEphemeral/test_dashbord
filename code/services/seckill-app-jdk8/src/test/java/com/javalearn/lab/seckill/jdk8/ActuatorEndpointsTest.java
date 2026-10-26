package com.javalearn.lab.seckill.jdk8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observability surface the D7 panel scrapes: /actuator/health (plus the
 * container-gate group), /actuator/info and /actuator/prometheus carrying the
 * same common tags the JDK 21 shell exports.
 * {@code @AutoConfigureMetrics} is required: Spring Boot's test support sets
 * management.metrics.export.defaults.enabled=false, which would otherwise drop
 * the Prometheus registry from the context (verified in the conditions report).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@AutoConfigureMetrics
class ActuatorEndpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthAndReadinessReportUp() throws Exception {
        assertThat(statusOf("/actuator/health")).isEqualTo(HttpStatus.OK);
        assertThat(jsonOf("/actuator/health").get("status").asText()).isEqualTo("UP");
        // compose healthcheck path: the JDK 21 shell exposes the same group name.
        assertThat(jsonOf("/actuator/health/app").get("status").asText()).isEqualTo("UP");
        assertThat(jsonOf("/actuator/health/readiness").get("status").asText()).isEqualTo("UP");
    }

    @Test
    void infoIdentifiesTheRuntime() throws Exception {
        JsonNode app = jsonOf("/actuator/info").get("app");
        assertThat(app.get("name").asText()).isEqualTo("seckill-app-jdk8");
        assertThat(app.get("shell").asText()).isEqualTo("seckill-app-jdk8");
    }

    @Test
    void prometheusCarriesJvmMetricsAndTheSharedCommonTags() {
        // One read-path call first so the timer series exists.
        restTemplate.getForEntity("/api/products/1", String.class);

        String scrape = restTemplate.getForObject("/actuator/prometheus", String.class);
        assertThat(scrape).isNotNull();
        assertThat(scrape).contains("jvm_memory_used_bytes");
        // app / env / instance / jvm_version: the exact four-tag contract the
        // JDK 21 shell emits via platform-observability commonTags (pinned in
        // its own ObservabilityIntegrationTest), asserted here by name AND default value.
        assertThat(scrape).contains("app=\"seckill-app-jdk8\"");
        assertThat(scrape).contains("env=\"dev\"");
        assertThat(scrape).contains("instance=\"app-jdk8-local\"");
        assertThat(scrape).contains("jvm_version=\"");
        assertThat(scrape).contains("http_server_requests_seconds_count");
        // percentile histogram, so histogram_quantile(... p99 ...) works on both shells.
        assertThat(scrape).contains("http_server_requests_seconds_bucket");
    }

    private HttpStatus statusOf(String path) {
        return restTemplate.getForEntity(path, String.class).getStatusCode();
    }

    private JsonNode jsonOf(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return MAPPER.readTree(response.getBody());
    }
}
