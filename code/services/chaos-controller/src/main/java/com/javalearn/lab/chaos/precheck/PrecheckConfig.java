package com.javalearn.lab.chaos.precheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.metrics.HttpPrometheusGateway;
import com.javalearn.lab.chaos.metrics.MetricsProperties;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.traffic.TrafficService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Wires the B1-04 pre-check.
 *
 * <p>The Prometheus client is a second instance of the existing B1-03 gateway:
 * same base URL and connect timeout ({@code lab.metrics.*}), but the request
 * budget comes from {@code lab.precheck.prometheus-timeout-seconds}. Sharing
 * the B1-03 bean would silently apply the (longer) aggregation timeout to the
 * start gate; a separate bean keeps both budgets honest without a new HTTP
 * dependency.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrecheckProperties.class)
public class PrecheckConfig {

    /**
     * Read-only Docker facts. {@code lab.docker.enabled=false} still gets a bean
     * (the controller context boots without a Docker client); every call then
     * degrades like an unreachable daemon instead of failing the context.
     */
    @Bean
    public PrecheckDockerFacts precheckDockerFacts(ObjectProvider<DockerClient> clients) {
        DockerClient client = clients.getIfAvailable();
        return client == null ? new UnavailablePrecheckDockerFacts() : new DockerClientFacts(client);
    }

    @Bean(name = "precheckPrometheusGateway")
    public PrometheusGateway precheckPrometheusGateway(MetricsProperties metrics,
                                                       PrecheckProperties precheck,
                                                       ObjectMapper objectMapper) {
        return new HttpPrometheusGateway(metrics.getPrometheusUrl(),
                metrics.getConnectTimeoutSeconds(), precheck.getPrometheusTimeoutSeconds(),
                objectMapper);
    }

    @Bean
    public RunPrecheckService runPrecheckService(PrecheckProperties properties,
                                                 LedgerProperties ledgerProperties,
                                                 LedgerBootstrap bootstrap,
                                                 RunLedgerRepository repository,
                                                 PrecheckDockerFacts dockerFacts,
                                                 TrafficService trafficService,
                                                 ChaosActionService chaosActions,
                                                 ToxiproxyService toxiproxy,
                                                 ChaosProperties chaosProperties,
                                                 @Qualifier("precheckPrometheusGateway")
                                                 PrometheusGateway prometheus,
                                                 com.javalearn.lab.chaos.instance.InstanceConfigRepository instanceConfigs,
                                                 Clock ledgerClock) {
        properties.validate();
        return new RunPrecheckService(properties, ledgerProperties, bootstrap, repository,
                dockerFacts, trafficService, chaosActions, toxiproxy, chaosProperties,
                prometheus, instanceConfigs, ledgerClock);
    }

    /** A daemon-less facts source: every query fails like an unreachable Docker. */
    static final class UnavailablePrecheckDockerFacts implements PrecheckDockerFacts {

        @Override
        public List<LabContainerFacts> runningLabContainers() {
            throw unavailable();
        }

        @Override
        public LabContainerFacts inspect(String containerName) {
            throw unavailable();
        }

        @Override
        public long memoryTotalBytes() {
            throw unavailable();
        }

        private static DockerUnavailableException unavailable() {
            return new DockerUnavailableException(
                    "lab.docker.enabled=false: no Docker client is wired");
        }
    }
}
