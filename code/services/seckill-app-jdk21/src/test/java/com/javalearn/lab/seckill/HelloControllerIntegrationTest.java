package com.javalearn.lab.seckill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
@Testcontainers
class HelloControllerIntegrationTest extends MySqlIntegrationTestSupport {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void helloReturnsMessageAndInstance() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/hello", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .isTrue();

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("message").asText()).isEqualTo("hello");
        assertThat(body.path("instance").asText()).isEqualTo("test-1");
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusMetricsCarryCommonTagsAndUriTemplate() {
        restTemplate.getForEntity("/api/hello", String.class);

        String metrics = restTemplate.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("http_server_requests_seconds_bucket")
                .contains("uri=\"/api/hello\"")
                .contains("app=\"seckill-app\"")
                .contains("env=\"test\"")
                .contains("instance=\"test-1\"")
                .contains("jvm_threads_live_threads")
                .contains("tomcat_threads_busy_threads");
    }

    @Test
    void compilerKeepsParameterNames() throws Exception {
        Method main = SeckillApplication.class.getMethod("main", String[].class);

        assertThat(main.getParameters()[0].isNamePresent()).isTrue();
        assertThat(main.getParameters()[0].getName()).isEqualTo("args");
    }
}
