package com.javalearn.lab.seckill;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Shared MySQL 8.4.4 container for full-context tests in this module.
 *
 * <p>The container field is static, so Testcontainers starts it once and keeps
 * it alive for every test class in the Surefire JVM fork. Each Spring context
 * still runs Flyway against the same schema; migrations are idempotent because
 * Flyway records them in {@code flyway_schema_history}.</p>
 */
public abstract class MySqlIntegrationTestSupport {

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("seckill")
            .withUsername("seckill")
            .withPassword("seckill")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("serverTimezone", "UTC");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
