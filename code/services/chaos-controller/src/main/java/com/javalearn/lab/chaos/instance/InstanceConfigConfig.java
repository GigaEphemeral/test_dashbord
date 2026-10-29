package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * Stage2 B4-01 wiring, deliberately separate from {@code LedgerConfig} so the
 * ledger bean graph stays untouched. The configuration repository reuses the
 * existing ledger JdbcTemplate (same dedicated {@code lab_ledger} schema).
 *
 * <p>{@link ContainerGateway} is optional: with {@code lab.docker.enabled=false}
 * the effective endpoint still exists but answers {@code readback=UNAVAILABLE}
 * instead of failing the context.</p>
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
}
