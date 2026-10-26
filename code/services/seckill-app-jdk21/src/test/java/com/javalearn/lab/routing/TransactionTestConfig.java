package com.javalearn.lab.routing;

import com.javalearn.lab.seckill.datasource.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal Spring context for {@link ReadWriteRoutingTest}: transaction
 * management over the primary (lazy) datasource plus two independent H2
 * databases with distinct marker rows.
 *
 * <p>{@code @SpringJUnitConfig} does not run Spring Boot auto-configuration, so
 * the {@code @ConfigurationProperties} bean methods of
 * {@link DataSourceConfig} need Boot's binding infrastructure explicitly;
 * {@code @EnableConfigurationProperties} registers it.</p>
 *
 * <p>The marker tables are created through {@link DriverManager} rather than
 * through the Hikari pools on purpose: it keeps both pools cold until the test
 * actually runs a statement, so the test can observe that the read pool is
 * initialized on demand by a read-only transaction.</p>
 *
 * <p>This fixture lives in {@code com.javalearn.lab.routing}, deliberately
 * outside the application's component-scan root ({@code com.javalearn.lab.seckill}).
 * Test classes are on the test classpath, so a {@code @Configuration} inside
 * the scanned tree would be picked up by every {@code @SpringBootTest} and its
 * {@code DataSourceTransactionManager} would replace the auto-configured JPA
 * transaction manager (JPA writes then fail with "no transaction is in
 * progress").</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableConfigurationProperties
public class TransactionTestConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        // The injected DataSource is the @Primary LazyConnectionDataSourceProxy:
        // transaction begin must not pin a physical connection.
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public MarkerTables markerTables(
            @Qualifier("writeDataSource") HikariDataSource writeDataSource,
            @Qualifier("readDataSource") HikariDataSource readDataSource) throws SQLException {
        initialize(writeDataSource, "write");
        initialize(readDataSource, "read");
        return new MarkerTables();
    }

    private static void initialize(HikariDataSource dataSource, String marker) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                dataSource.getJdbcUrl(), dataSource.getUsername(), dataSource.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS routing_marker");
            statement.execute("CREATE TABLE routing_marker (marker VARCHAR(16) PRIMARY KEY)");
            statement.execute("INSERT INTO routing_marker (marker) VALUES ('" + marker + "')");
        }
    }

    /** Marker bean: forces {@link #markerTables} to run during context startup. */
    public static final class MarkerTables {
    }
}
