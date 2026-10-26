package com.javalearn.lab.seckill;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Shared MySQL 8.4.4 container for full-context tests in this module.
 *
 * <p>This is the Testcontainers singleton-container pattern, implemented with a
 * static initializer instead of {@code @Container} + {@code @Testcontainers}.
 * The JUnit extension stops a static container after each test class; because
 * Spring caches the application context (and therefore the JDBC URL) across
 * classes, a restart on a new random port would leave every later class with a
 * stale connection URL. Starting once per Surefire JVM keeps one fixed port and
 * one Flyway migration history for all IT classes. Ryuk removes the container
 * when the JVM exits.</p>
 */
public abstract class MySqlIntegrationTestSupport {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("seckill")
            .withUsername("seckill")
            .withPassword("seckill")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("serverTimezone", "UTC");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
