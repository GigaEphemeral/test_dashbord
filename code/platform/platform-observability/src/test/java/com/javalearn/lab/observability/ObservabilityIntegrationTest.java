package com.javalearn.lab.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
// Boot's test auto-configuration disables metric export unless we opt in.
@AutoConfigureObservability
@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=observability-test",
                "observability.env=test",
                "observability.instance=test-1",
                // Keep the test client's raw outgoing URI out of the scraped body.
                "management.observations.enable.http.client.requests=false"
        })
class ObservabilityIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exposesPrometheusMetricsWithCommonTagsAndUriTemplate() {
        ResponseEntity<String> productResponse = restTemplate.getForEntity("/products/123", String.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String metrics = restTemplate.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("http_server_requests_seconds_bucket")
                .contains("app=\"observability-test\"")
                .contains("env=\"test\"")
                .contains("instance=\"test-1\"")
                .contains("jvm_version=")
                .contains("uri=\"/products/{id}\"")
                .doesNotContain("uri=\"/products/123\"");
    }

    @Test
    void writesJsonConsoleLogsWithApplicationMetadata(CapturedOutput output) throws Exception {
        logger.info("json-log-probe");

        String logLine = output.getOut().lines()
                .filter(line -> line.contains("json-log-probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No captured log line contained the json-log-probe marker"));

        JsonNode logEvent = new ObjectMapper().readTree(logLine);
        assertThat(logEvent.path("app").asText()).isEqualTo("observability-test");
        assertThat(logEvent.path("env").asText()).isEqualTo("test");
        assertThat(logEvent.path("instance").asText()).isEqualTo("test-1");
        assertThat(logEvent.path("message").asText()).isNotBlank();
    }
}
