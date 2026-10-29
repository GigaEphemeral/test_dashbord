package com.javalearn.lab.chaos.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.precheck.PrecheckProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * B5-01a wiring. The topology service reuses the existing Docker gateway,
 * Prometheus instant-query gateway, ledger repository and instance-config
 * repository; it adds no datasource, scheduler or HTTP client.
 *
 * <p>Docker is optional ({@code lab.docker.enabled=false}); the service then
 * reports every node {@code UNKNOWN}/NO_DATA instead of failing the context.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TopologyProperties.class)
public class TopologyConfig {

    @Bean
    public TopologyService topologyService(TopologyProperties properties,
                                           PrecheckProperties precheckProperties,
                                           InstanceConfigProperties instanceProperties,
                                           ObjectProvider<ContainerGateway> gatewayProvider,
                                           PrometheusGateway prometheusGateway,
                                           InstanceConfigRepository instanceConfigRepository,
                                           RunLedgerRepository runLedgerRepository,
                                           ObjectMapper objectMapper,
                                           Clock ledgerClock) {
        properties.validate();
        return new TopologyService(properties, precheckProperties, instanceProperties,
                gatewayProvider.getIfAvailable(), prometheusGateway,
                instanceConfigRepository, runLedgerRepository, objectMapper, ledgerClock);
    }
}
