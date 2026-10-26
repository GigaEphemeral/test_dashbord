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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP contract parity with the JDK 21 shell's hot read path: same path, same
 * Result envelope, same field names, same seed data, so one k6 script (see
 * lab/k6/lib/flow.js product()) can target either runtime unchanged.
 * The annotations are kept byte-identical to ActuatorEndpointsTest so both
 * classes share ONE cached context and the demo seed script runs once per
 * in-memory database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@AutoConfigureMetrics
class ProductApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void readsSeededProductWithTheJdk21Envelope() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/products/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(response.getBody());
        // The shared platform-common Result also exposes isSuccess() as "success";
        // the JDK 21 shell serialises the same four fields.
        assertThat(fieldNames(body)).isEqualTo(sorted("code", "data", "message", "success"));
        assertThat(body.get("code").asText()).isEqualTo("0");

        JsonNode data = body.get("data");
        assertThat(fieldNames(data)).isEqualTo(sorted("id", "name", "price", "status", "description"));
        assertThat(data.get("id").asLong()).isEqualTo(1L);
        assertThat(data.get("name").asText()).isEqualTo("极客机械键盘");
        assertThat(data.get("status").asText()).isEqualTo("ON_SALE");
        // The two-decimal scale the MySQL DECIMAL(10,2) seed produces.
        assertThat(data.get("price").decimalValue().stripTrailingZeros().toPlainString()).isEqualTo("299");
        assertThat(response.getBody()).contains("\"price\":299.00");
    }

    @Test
    void readsProduct20WhichTheK6SeckillFlowUses() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/products/20", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = MAPPER.readTree(response.getBody()).get("data");
        assertThat(data.get("id").asLong()).isEqualTo(20L);
        assertThat(data.get("name").asText()).isEqualTo("智能插座");
    }

    @Test
    void answersTheResultEnvelopeForAnUnknownProduct() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/products/9999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("40401");
        assertThat(body.get("data").isNull()).isTrue();
    }

    @Test
    void answersBadRequestForANonNumericId() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/products/not-a-long", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(MAPPER.readTree(response.getBody()).get("code").asText()).isEqualTo("40001");
    }

    @Test
    void helloReportsTheInstanceTag() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/hello", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(fieldNames(body)).isEqualTo(sorted("message", "instance"));
        assertThat(body.get("message").asText()).isEqualTo("hello");
        assertThat(body.get("instance").asText()).isNotEmpty();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<String>();
        for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        // Jackson's property order is not part of the contract; compare as a set.
        Collections.sort(names);
        return names;
    }

    private static List<String> sorted(String... names) {
        List<String> sorted = new ArrayList<String>(Arrays.asList(names));
        Collections.sort(sorted);
        return sorted;
    }
}
