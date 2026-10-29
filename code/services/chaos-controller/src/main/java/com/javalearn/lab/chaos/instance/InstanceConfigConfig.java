package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * Stage2 B4-01/B4-02 wiring, deliberately separate from {@code LedgerConfig} so
 * the ledger bean graph stays untouched. The configuration repository reuses the
 * existing ledger JdbcTemplate (same dedicated {@code lab_ledger} schema).
 *
 * <p>{@link ContainerGateway} and {@link InstanceRebuildGateway} are optional:
 * with {@code lab.docker.enabled=false} the readback and apply endpoints still
 * exist but answer {@code UNAVAILABLE}/503 instead of failing the context.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstanceConfigProperties.class)
public class InstanceConfigConfig {

    @Bean
    public InstanceConfigValidator instanceConfigValidator(InstanceConfigProperties properties) {
        return new InstanceConfigValidator(properties);
    }

    @Bean
    public InstanceConfigRepository instanceConfigRepository(
            @Qualifier("ledgerJdbcTemplate") JdbcTemplate ledgerJdbcTemplate) {
        return new InstanceConfigRepository(ledgerJdbcTemplate);
    }

    @Bean
    public InstanceConfigService instanceConfigService(InstanceConfigProperties properties,
                                                       InstanceConfigValidator validator,
                                                       InstanceConfigRepository repository,
                                                       RunLedgerRepository runLedgerRepository,
                                                       LedgerProperties ledgerProperties,
                                                       LedgerBootstrap ledgerBootstrap,
                                                       ObjectMapper objectMapper,
                                                       Clock clock,
                                                       ObjectProvider<ContainerGateway> gatewayProvider) {
        return new InstanceConfigService(properties, validator, repository, runLedgerRepository,
                ledgerProperties, ledgerBootstrap, objectMapper, clock, gatewayProvider);
    }

    /** B4-02: Docker rebuild implementation (same daemon socket as the gateway). */
    @Bean
    @ConditionalOnProperty(name = "lab.docker.enabled", havingValue = "true", matchIfMissing = true)
    public InstanceRebuildGateway instanceRebuildGateway(
            com.github.dockerjava.api.DockerClient dockerClient,
            ObjectProvider<ContainerGateway> containerGatewayProvider,
            InstanceConfigProperties properties) {
        ContainerGateway containerGateway = containerGatewayProvider.getIfAvailable();
        if (containerGateway == null) {
            throw new IllegalStateException("InstanceRebuildGateway requires a ContainerGateway; "
                    + "check lab.docker.enabled");
        }
        return new DockerInstanceRebuildGateway(dockerClient, containerGateway, properties);
    }

    /** B4-02: HTTP probes use the JDK client; no Docker is needed to construct it. */
    @Bean
    public InstanceProbeGateway instanceProbeGateway() {
        return new HttpInstanceProbeGateway();
    }

    @Bean
    public InstanceRebuildService instanceRebuildService(
            InstanceConfigProperties properties,
            InstanceConfigValidator validator,
            InstanceConfigRepository repository,
            RunLedgerRepository runLedgerRepository,
            LedgerProperties ledgerProperties,
            LedgerBootstrap ledgerBootstrap,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<InstanceRebuildGateway> rebuildGatewayProvider,
            ObjectProvider<ContainerGateway> containerGatewayProvider,
            PrometheusGateway prometheusGateway,
            InstanceProbeGateway probeGateway,
            @Value("${lab.token}") String labToken) {
        return new InstanceRebuildService(properties, validator, repository, runLedgerRepository,
                ledgerProperties, ledgerBootstrap, objectMapper, clock, rebuildGatewayProvider,
                containerGatewayProvider, prometheusGateway, probeGateway, labToken);
    }
}
