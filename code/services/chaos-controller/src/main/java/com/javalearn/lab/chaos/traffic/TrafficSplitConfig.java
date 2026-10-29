package com.javalearn.lab.chaos.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the B5-02 split service; the gateway and run-window resolver are shared. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficSplitProperties.class)
public class TrafficSplitConfig {

    @Bean
    public InstanceStatsService instanceStatsService(MetricsService metricsService,
                                                     // Two gateways exist (metrics + precheck);
                                                     // the split reads the metrics one.
                                                     @Qualifier("prometheusGateway")
                                                     PrometheusGateway gateway,
                                                     TrafficSplitProperties properties,
                                                     InstanceConfigRepository configRepository,
                                                     ObjectMapper objectMapper) {
        properties.validate();
        return new InstanceStatsService(metricsService, gateway, properties,
                configRepository, objectMapper);
    }
}
