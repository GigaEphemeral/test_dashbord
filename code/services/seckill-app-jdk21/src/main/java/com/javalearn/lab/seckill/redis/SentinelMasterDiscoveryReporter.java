package com.javalearn.lab.seckill.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisServer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Startup master-discovery evidence (Task M3-1b, design §8.2 "客户端通过
 * Sentinel 发现主节点").
 *
 * <p>Runs ONLY in sentinel mode. After the context is ready it asks the
 * Sentinels for the monitored masters ({@code SENTINEL masters} through
 * {@code RedisSentinelConnection#masters()}) and picks the entry whose NAME is
 * the configured master name — discovery strictly BY NAME, never by a
 * hard-coded address — logging the resolved host:port so the container logs
 * carry the proof the ha drill needs. The probe deliberately uses the Sentinel
 * connection (not the data connection): that is the one path that must answer
 * during a failover window.</p>
 *
 * <p>The budget stays open after the first answer for
 * {@code app.redis.sentinel.discovery-report-attempts} probes
 * ({@code …discovery-report-interval-ms} apart), and logs every time the
 * reported address CHANGES: a failover that happens inside that observation
 * window is therefore visible in the logs of a container that was never
 * restarted. Raising the two knobs widens the window without a code change.</p>
 *
 * <p>Boot is never blocked: the work happens on a single daemon thread, every
 * exception is logged and swallowed (工程规约 §1 for both knobs). This mirrors
 * the lazy-init contract RedisOutageCacheIT enforces for Redis itself.</p>
 */
public class SentinelMasterDiscoveryReporter
        implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SentinelMasterDiscoveryReporter.class);

    private final LettuceConnectionFactory connectionFactory;
    private final RedisProperties redisProperties;
    private final RedisAccessProperties accessProperties;
    private final Executor executor;

    public SentinelMasterDiscoveryReporter(LettuceConnectionFactory connectionFactory,
                                           RedisProperties redisProperties,
                                           RedisAccessProperties accessProperties) {
        this(connectionFactory, redisProperties, accessProperties,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "redis-sentinel-discovery-report");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** Test seam: run the probe inline instead of on the daemon thread. */
    SentinelMasterDiscoveryReporter(LettuceConnectionFactory connectionFactory,
                                    RedisProperties redisProperties,
                                    RedisAccessProperties accessProperties,
                                    Executor executor) {
        this.connectionFactory = connectionFactory;
        this.redisProperties = redisProperties;
        this.accessProperties = accessProperties;
        this.executor = executor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        executor.execute(this::reportDiscovery);
    }

    /**
     * Runs the configured probe window; returns the LAST address any Sentinel
     * reported for the master name (null when nothing ever answered) so tests
     * can assert the by-NAME discovery without scraping logs.
     */
    String reportDiscovery() {
        RedisSentinelConfiguration sentinelConfig = connectionFactory.getSentinelConfiguration();
        if (sentinelConfig == null) {
            log.warn("Redis mode=sentinel but the connection factory has no Sentinel"
                    + " configuration; master discovery cannot be proven");
            return null;
        }
        String masterName = sentinelConfig.getMaster().getName();
        int attempts = accessProperties.getSentinel().getDiscoveryReportAttempts();
        long intervalMs = accessProperties.getSentinel().getDiscoveryReportIntervalMs();
        String lastLogged = null;
        String lastAnswer = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleep(intervalMs);
            }
            String answer = probe(masterName);
            if (answer == null) {
                continue;
            }
            lastAnswer = answer;
            if (!answer.equals(lastLogged)) {
                lastLogged = answer;
                log.info("Sentinel discovered master '{}' at {} (probe {}/{}, sentinels {})",
                        masterName, answer, attempt, attempts,
                        redisProperties.getSentinel().getNodes());
            }
        }
        if (lastAnswer == null) {
            log.warn("Redis mode=sentinel: master '{}' was not discovered in {} probes;"
                    + " the app keeps starting and degrades to MySQL reads (Task 14 contract)",
                    masterName, attempts);
        }
        return lastAnswer;
    }

    /**
     * One {@code SENTINEL masters} probe, answered BY NAME.
     *
     * <p>API reality (Spring Data Redis 3.5): {@code RedisSentinelConnection}
     * exposes {@code masters()} (the RESP {@code SENTINEL masters} reply mapped
     * into {@code RedisServer}, whose name is the MONITORED master name) — there
     * is no {@code getMasterAddrForName} accessor, so the by-NAME discovery is
     * the name filter below. It is a strict filter: an address from a differently
     * named master is never accepted, and a Sentinel group that reports no
     * master under {@code mymaster} is logged as such instead of guessing.</p>
     */
    private String probe(String masterName) {
        try (RedisSentinelConnection connection = connectionFactory.getSentinelConnection()) {
            List<RedisServer> reported = new ArrayList<>();
            connection.masters().forEach(reported::add);
            RedisServer master = reported.stream()
                    .filter(server -> masterName.equals(server.getName()))
                    .findFirst()
                    .orElse(null);
            if (master == null) {
                log.warn("Sentinel reported no master named '{}' (seen: {})",
                        masterName, reported.stream().map(RedisServer::getName).toList());
                return null;
            }
            if (master.getHost() == null || master.getHost().isBlank()
                    || master.getHost().startsWith("-") || master.getPort() <= 0) {
                log.warn("Sentinel master '{}' has no usable address yet (flags: {}, down-after"
                        + " not elapsed or failover in progress)", masterName, master.getFlags());
                return null;
            }
            return master.getHost() + ":" + master.getPort();
        } catch (Exception ex) {
            log.warn("Sentinel master discovery probe failed for '{}': {}",
                    masterName, ex.toString());
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
