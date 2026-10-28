package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.ledger.EnvLockService;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerWriter;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Stage2 B2-02b-1 wiring. Beans are created even when
 * {@code lab.docker.enabled=false}: the controller context must boot without a
 * Docker client, and every executor call then degrades to 503/50303 instead of
 * failing the context (same pattern as {@code PrecheckConfig}).
 */
@Configuration(proxyBeanMethods = false)
public class BenchExecConfig {

    @Bean
    public BenchScriptsHostDirResolver benchScriptsHostDirResolver(
            BenchExecProperties properties, ObjectProvider<DockerClient> clients) {
        DockerClient client = clients.getIfAvailable();
        BenchScriptsHostDirResolver.MountSource source = client == null
                ? containerName -> {
                    throw unavailable();
                }
                : new DockerControllerMountSource(client);
        return new BenchScriptsHostDirResolver(properties, source);
    }

    @Bean
    public K6ContainerGateway k6ContainerGateway(BenchExecProperties properties,
                                                 ObjectProvider<DockerClient> clients) {
        DockerClient client = clients.getIfAvailable();
        return client == null
                ? new UnavailableK6ContainerGateway()
                : new DockerK6ContainerGateway(client, properties);
    }

    @Bean
    public BenchRunExecutor benchRunExecutor(BenchExecProperties properties,
                                             K6ContainerGateway gateway) {
        return new BenchRunExecutor(properties, gateway);
    }

    /**
     * Stage2 B2-02b-2a: exit/timeout watchdog and startup orphan cleanup. It
     * owns a dedicated daemon thread (no {@code @EnableScheduling}); the first
     * tick runs immediately, and a ledger that is not ready simply defers.
     */
    @Bean(destroyMethod = "stop")
    public BenchRunWatchdog benchRunWatchdog(BenchExecProperties properties,
                                             LedgerProperties ledgerProperties,
                                             LedgerBootstrap ledgerBootstrap,
                                             RunLedgerRepository runLedgerRepository,
                                             LedgerWriter ledgerWriter,
                                             EnvLockService envLockService,
                                             BenchRunExecutor benchRunExecutor,
                                             BenchScenarioCatalog benchScenarioCatalog,
                                             ObjectMapper objectMapper,
                                             Clock ledgerClock) {
        BenchRunWatchdog watchdog = new BenchRunWatchdog(properties, ledgerProperties,
                ledgerBootstrap, runLedgerRepository, ledgerWriter, envLockService,
                benchRunExecutor, benchScenarioCatalog, objectMapper, ledgerClock);
        watchdog.start();
        return watchdog;
    }

    private static BenchUnavailableException unavailable() {
        return new BenchUnavailableException(
                "lab.docker.enabled=false: no Docker client is wired, so the k6 executor is unavailable");
    }

    /** Daemon-less gateway: every operation answers 503/50303. */
    static final class UnavailableK6ContainerGateway implements K6ContainerGateway {

        @Override
        public void ensureImage(String image) {
            throw unavailable();
        }

        @Override
        public String createAndStart(BenchLaunchPlan plan) {
            throw unavailable();
        }

        @Override
        public Snapshot inspect(String runId) {
            throw unavailable();
        }

        @Override
        public LogChunk logs(String containerName, int tail, int maxBytes) {
            throw unavailable();
        }

        @Override
        public boolean stop(String containerName, int graceSeconds) {
            throw unavailable();
        }

        @Override
        public boolean remove(String containerName) {
            throw unavailable();
        }

        @Override
        public List<ContainerSummary> listK6Containers() {
            throw unavailable();
        }
    }
}
