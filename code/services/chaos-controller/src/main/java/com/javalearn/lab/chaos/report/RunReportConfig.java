package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchThresholdService;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.traffic.InstanceStatsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the B5-03 report; every dependency already exists for other reads. */
@Configuration(proxyBeanMethods = false)
public class RunReportConfig {

    @Bean
    public RunReportService runReportService(RunLedgerRepository repository,
                                             MetricsService metricsService,
                                             InstanceStatsService instanceStats,
                                             BenchThresholdService thresholds,
                                             InstanceConfigRepository configRepository,
                                             InstanceConfigProperties configProperties,
                                             ObjectMapper objectMapper, Clock ledgerClock) {
        return new RunReportService(repository, metricsService, instanceStats, thresholds,
                configRepository, configProperties, objectMapper, ledgerClock);
    }
}
