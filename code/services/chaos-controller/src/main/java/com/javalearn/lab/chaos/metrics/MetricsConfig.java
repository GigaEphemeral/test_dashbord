package com.javalearn.lab.chaos.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.time.Clock;

/**
 * Wires the B1-03 aggregation layer: the validated catalog (startup failure if
 * the resource is missing/invalid), the Prometheus gateway, and the service that
 * also writes best-effort {@code lab_metric_window} rows through the existing
 * ledger repository. No new datastore, middleware or HTTP client dependency.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfig {

    @Bean
    public MetricCatalog metricCatalog(MetricsProperties properties, ResourceLoader resourceLoader) {
        properties.validate();
        return MetricCatalog.load(resourceLoader, properties.getCatalogLocation(),
                properties.getMaxKeys());
    }

    @Bean
    public PrometheusGateway prometheusGateway(MetricsProperties properties,
                                               ObjectMapper objectMapper) {
        return new HttpPrometheusGateway(properties, objectMapper);
    }

    @Bean
    public MetricsService metricsService(MetricsProperties properties, MetricCatalog metricCatalog,
                                         PrometheusGateway prometheusGateway,
                                         RunLedgerRepository runLedgerRepository,
                                         ObjectMapper objectMapper, Clock ledgerClock) {
        return new MetricsService(properties, metricCatalog, prometheusGateway,
                runLedgerRepository, objectMapper, ledgerClock);
    }
}
