package com.javalearn.lab.chaos.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires B3-04: a dedicated read-only business pool plus the pure evaluator, the
 * RabbitMQ management probe and the ledger-backed store.
 *
 * <p>The pool sets {@code initialization-fail-timeout=-1}, so a down business
 * MySQL never blocks controller startup; reconciliation simply answers
 * DEGRADED. It is also flagged read-only. The ledger template is reused for
 * {@code lab_run_check}, never the business pool.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconcileProperties.class)
public class ReconcileConfig {

    @Bean(name = "reconcileBusinessDataSource", destroyMethod = "close")
    public HikariDataSource reconcileBusinessDataSource(ReconcileProperties properties) {
        properties.validate();
        HikariConfig config = new HikariConfig();
        config.setPoolName("reconcile-business");
        config.setJdbcUrl(properties.activeUrl());
        config.setUsername(properties.getUser());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        config.setReadOnly(true);
        // Never open a connection at pool creation: reconciliation is optional
        // and a down business DB must not keep the controller from starting.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean(name = "reconcileBusinessJdbcTemplate")
    public JdbcTemplate reconcileBusinessJdbcTemplate(
            @Qualifier("reconcileBusinessDataSource") DataSource dataSource,
            ReconcileProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(properties.getQueryTimeoutSeconds());
        jdbc.setFetchSize(64);
        return jdbc;
    }

    @Bean
    public BusinessSnapshotReader businessSnapshotReader(
            @Qualifier("reconcileBusinessJdbcTemplate") JdbcTemplate jdbcTemplate,
            Clock ledgerClock) {
        return new BusinessSnapshotReader(jdbcTemplate, ledgerClock);
    }

    @Bean
    public RabbitQueueGateway rabbitQueueGateway(ReconcileProperties properties,
                                                 ObjectMapper objectMapper) {
        return new HttpRabbitQueueGateway(properties, objectMapper);
    }

    @Bean
    public ReconciliationStore reconciliationStore(
            @Qualifier("ledgerJdbcTemplate") JdbcTemplate ledgerJdbcTemplate) {
        return new ReconciliationStore(ledgerJdbcTemplate);
    }

    @Bean
    public ReconciliationEvaluator reconciliationEvaluator() {
        return new ReconciliationEvaluator();
    }

    @Bean
    public ReconciliationService reconciliationService(ReconcileProperties properties,
                                                       BusinessSnapshotReader reader,
                                                       RabbitQueueGateway rabbitQueueGateway,
                                                       PrometheusGateway prometheusGateway,
                                                       ReconciliationStore store,
                                                       ReconciliationEvaluator evaluator,
                                                       ObjectMapper objectMapper,
                                                       Clock ledgerClock) {
        return new ReconciliationService(properties, reader, rabbitQueueGateway, prometheusGateway,
                store, evaluator, objectMapper, ledgerClock);
    }
}
