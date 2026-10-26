package com.javalearn.lab.seckill.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
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
 * through the read pool. The {@code dbHealthContributor} bean below is what
 * keeps that promise for health checks.</p>
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

    /**
     * Actuator {@code db} health over the primary DataSource only.
     *
     * <p>Task 15 review fix: when several DataSource beans exist, Boot's
     * {@code DataSourceHealthContributorAutoConfiguration} builds a composite
     * that calls {@code getConnection()} on <em>each</em> one directly —
     * including the raw {@code readDataSource}. The Docker healthcheck then
     * starts the read pool seconds after boot, which (a) violates the design
     * rule "health uses the primary DataSource (write for non-transactional
     * calls)" and (b) destroys the meaning of the verify.sh check that treats
     * the appearance of {@code hikaricp_connections_max{pool="read-pool"}} as
     * evidence that a read-only transaction ran. Declaring a bean named
     * {@code dbHealthContributor} makes the auto-configuration back off
     * ({@code @ConditionalOnMissingBean(name = {"dbHealthIndicator",
     * "dbHealthContributor"})}).</p>
     *
     * <p>Without a validation query the indicator uses {@code isValid()}, the
     * same behaviour Boot had for the single lazy datasource before this
     * override; a non-transactional health connection routes to the write pool
     * by the routing default.</p>
     */
    @Bean(name = "dbHealthContributor")
    public HealthContributor dbHealthContributor(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceHealthIndicator(dataSource);
    }
}
