package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.MySqlIntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack behaviour of the product cache while Redis is unreachable
 * (Task 14 review fix for the container-health concern).
 *
 * <p>The application is started against a closed TCP port instead of a live
 * Redis, which reproduces a Redis outage: every L2 call fails fast with a
 * connection failure. Three things must hold, matching design §8.2/§15
 * scenario 8 (Redis failover must not break the app tier):</p>
 *
 * <ol>
 *   <li>product reads degrade to MySQL and still return 200, counting
 *       {@code cache_l2_errors}; the second read is served from L1;</li>
 *   <li>the root {@code /actuator/health} stays honest (503, redis DOWN);</li>
 *   <li>the container health gate {@code /actuator/health/app} excludes only
 *       redis and keeps reporting UP with the db component present, so Docker
 *       does not mark the instance unhealthy during a Redis outage.</li>
 * </ol>
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "observability.env=test",
                "observability.instance=test-outage-1",
                // Only for this test: expose component membership so the
                // assertions can prove which contributors the app group has.
                "management.endpoint.health.show-details=always"
        })
@Testcontainers
class RedisOutageCacheIT extends MySqlIntegrationTestSupport {

    /** Port of a socket we opened and closed; connecting to it is refused. */
    private static final int DEAD_REDIS_PORT = findClosedPort();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void deadRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> DEAD_REDIS_PORT);
    }

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void productReadDegradesToMysqlAndCountsL2Errors() {
        double errorsBefore = l2Errors();
        double l1HitsBefore = counter("cache_l1_requests", "result", "hit");

        ResponseEntity<String> first = restTemplate.getForEntity("/api/products/1", String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"code\":\"0\"").contains("\"id\":1");
        // One error for the failed GET, one for the failed SET (or more if
        // lettuce retries); the read must still have fallen through to MySQL.
        assertThat(l2Errors()).isGreaterThanOrEqualTo(errorsBefore + 2.0);

        // Second read is served from the local L1 even with Redis still down.
        ResponseEntity<String> second = restTemplate.getForEntity("/api/products/1", String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(l1HitsBefore + 1.0);
    }

    @Test
    void rootHealthStaysHonestAboutTheOutage() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(health.getBody()).contains("\"status\":\"DOWN\"").contains("redis");
    }

    @Test
    void appHealthGroupExcludesRedisAndStaysUp() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health/app", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        // The gate must still carry the real contributors (no silent empty
        // group), and must not carry redis.
        assertThat(health.getBody()).contains("\"db\"").contains("\"ping\"").doesNotContain("redis");
    }

    private double l2Errors() {
        return counter("cache_l2_errors");
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
