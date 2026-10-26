package com.javalearn.lab.seckill.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Explicit write/read datasource pair for the application tier.
 *
 * <p>Each pool is a plain {@link HikariDataSource} bound to
 * {@code app.datasource.write.*} / {@code app.datasource.read.*}. The primary
 * {@link DataSource} bean is a {@link LazyConnectionDataSourceProxy} around
 * {@link ReadWriteRoutingDataSource}. The proxy is not cosmetic: Spring starts
 * a transaction (and binds the transaction's read-only flag) before the
 * transaction manager asks for a JDBC connection, and the routing key is
 * resolved when the physical connection is fetched. Without the lazy proxy the
 * connection would be fetched during transaction begin, before the read-only
 * flag is visible, and every read-only transaction would silently use the
 * write pool.</p>
 *
 * <p>JPA, Flyway, the actuator health indicator and {@code JdbcTemplate} all
 * use this primary bean, so non-transactional work (schema migration, health
 * probes) always goes through the write pool and read-only transactions go
 * through the read pool.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * Write pool. {@code jdbc-url} is deliberately used instead of Boot's
     * {@code url} property so the {@link HikariDataSource} binder is exercised
     * directly (no {@code DataSourceProperties} involved).
     */
    @Bean
    @ConfigurationProperties("app.datasource.write")
    public HikariDataSource writeDataSource() {
        return new HikariDataSource();
    }

    /**
     * Read pool. Only used for connections acquired by read-only transactions
     * ({@code @Transactional(readOnly = true)}).
     */
    @Bean
    @ConfigurationProperties("app.datasource.read")
    public HikariDataSource readDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public ReadWriteRoutingDataSource routingDataSource(
            @Qualifier("writeDataSource") HikariDataSource writeDataSource,
            @Qualifier("readDataSource") HikariDataSource readDataSource) {
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.WRITE, writeDataSource);
        targets.put(DataSourceType.READ, readDataSource);

        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource();
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * The one DataSource every other component sees. {@code @Primary} makes
     * Spring Boot's JPA/Flyway/actuator/JdbcTemplate auto-configuration pick it
     * while the two Hikari pools remain available for routing and tests.
     */
    @Bean
    @Primary
    public DataSource dataSource(ReadWriteRoutingDataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
