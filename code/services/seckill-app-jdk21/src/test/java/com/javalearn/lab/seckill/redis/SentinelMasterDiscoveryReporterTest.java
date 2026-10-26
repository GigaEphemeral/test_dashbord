package com.javalearn.lab.seckill.redis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisServer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The master-discovery EVIDENCE path of Task M3-1b, asserted with a mocked
 * Sentinel connection (no live Sentinel needed): the app asks the Sentinels and
 * accepts an address ONLY for the configured master NAME (never a hard-coded
 * one), logs the resolved host:port, honours the externalised probe budget,
 * and survives a dead Sentinel group without throwing (Task 14 degradation
 * contract).
 */
class SentinelMasterDiscoveryReporterTest {

    private LettuceConnectionFactory connectionFactory;
    private RedisSentinelConnection sentinelConnection;
    private RedisProperties redisProperties;
    private RedisAccessProperties accessProperties;
    private org.slf4j.Logger reporterLogger;
    private ListAppender<ILoggingEvent> appender;

    /**
     * Builds the {@code RedisServer} exactly the way Spring Data's Lettuce
     * Sentinel adapter does from the RESP {@code SENTINEL masters} reply, so the
     * name/host mapping under test is the production one.
     */
    private static RedisServer reportedServer(String name, String ip, int port, String flags) {
        Properties properties = new Properties();
        properties.setProperty("name", name);
        properties.setProperty("ip", ip);
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("flags", flags);
        return RedisServer.newServerFrom(properties);
    }

    @BeforeEach
    void setUp() {
        connectionFactory = mock(LettuceConnectionFactory.class);
        sentinelConnection = mock(RedisSentinelConnection.class);
        when(connectionFactory.getSentinelConnection()).thenReturn(sentinelConnection);

        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
        configuration.master("mymaster");
        configuration.sentinel(new RedisNode("sentinel-1", 26379));
        when(connectionFactory.getSentinelConfiguration()).thenReturn(configuration);

        redisProperties = new RedisProperties();
        redisProperties.setSentinel(new RedisProperties.Sentinel());
        redisProperties.getSentinel().setMaster("mymaster");
        redisProperties.getSentinel().setNodes(
                List.of("sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379"));

        accessProperties = new RedisAccessProperties();
        accessProperties.setMode(RedisAccessProperties.MODE_SENTINEL);
        accessProperties.getSentinel().setDiscoveryReportAttempts(3);
        accessProperties.getSentinel().setDiscoveryReportIntervalMs(1);
        accessProperties.validate();

        reporterLogger = LoggerFactory.getLogger(SentinelMasterDiscoveryReporter.class);
        appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) reporterLogger).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) reporterLogger).detachAppender(appender);
        appender.stop();
    }

    private SentinelMasterDiscoveryReporter reporter() {
        // Inline executor: the probe body is what is under test; the daemon
        // thread wiring is covered by the bean-level test in RedisModeSwitchTest.
        Executor inline = Runnable::run;
        return new SentinelMasterDiscoveryReporter(
                connectionFactory, redisProperties, accessProperties, inline);
    }

    @Test
    void discoversTheMasterByNameAndLogsTheResolvedAddress() {
        when(sentinelConnection.masters()).thenReturn(List.of(
                reportedServer("mymaster", "172.28.190.12", 6379, "master")));

        String discovered = reporter().reportDiscovery();

        assertThat(discovered).isEqualTo("172.28.190.12:6379");
        // The whole budget runs on the Sentinel connection, never on a data
        // connection, and the name filter (not any address) decided the answer.
        verify(sentinelConnection, times(3)).masters();
        assertThat(messages(Level.INFO)).anySatisfy(line -> assertThat(line)
                .contains("Sentinel discovered master 'mymaster' at 172.28.190.12:6379")
                .contains("[sentinel-1:26379, sentinel-2:26379, sentinel-3:26379]"));
    }

    @Test
    void logsEachChangeOfTheReportedMasterWithinTheProbeWindow() {
        // The failover evidence: probe 1 reports the old master, probes 2/3 the
        // promoted one; both lines appear although the app was never restarted.
        when(sentinelConnection.masters())
                .thenReturn(List.of(reportedServer("mymaster", "172.28.190.11", 6379, "master,sdown")))
                .thenReturn(List.of(reportedServer("mymaster", "172.28.190.12", 6379, "master")))
                .thenReturn(List.of(reportedServer("mymaster", "172.28.190.12", 6379, "master")));

        String discovered = reporter().reportDiscovery();

        assertThat(discovered).isEqualTo("172.28.190.12:6379");
        List<String> info = messages(Level.INFO);
        assertThat(info).hasSize(2); // the unchanged third probe adds no noise
        assertThat(String.join("\n", info))
                .contains("172.28.190.11:6379")
                .contains("172.28.190.12:6379");
    }

    @Test
    void aMasterUnderAnotherNameIsNeverAccepted() {
        // Guards the by-NAME contract: an address that is merely present in the
        // reply (wrong master name, e.g. a stray monitor) is not discovery.
        when(sentinelConnection.masters()).thenReturn(List.of(
                reportedServer("othermaster", "172.28.190.99", 6379, "master")));

        assertThat(reporter().reportDiscovery()).isNull();
        assertThat(messages(Level.WARN)).anySatisfy(line -> assertThat(line)
                .contains("no master named 'mymaster'")
                .contains("[othermaster]"));
    }

    @Test
    void aMasterWithoutAnAddressYetIsReportedAsNotDiscovered() {
        // Sentinel answers "-" while a failover is in progress; that must not be
        // logged as a usable master.
        when(sentinelConnection.masters()).thenReturn(List.of(
                reportedServer("mymaster", "-", 6379, "master,o_down")));

        assertThat(reporter().reportDiscovery()).isNull();
        assertThat(messages(Level.WARN)).anySatisfy(line -> assertThat(line)
                .contains("no usable address"));
    }

    @Test
    void deadSentinelsNeverThrowAndTheAppDegrades() {
        when(sentinelConnection.masters()).thenThrow(new RedisConnectionFailureException(
                "Unable to connect to sentinel-1:26379", new IllegalStateException("refused")));

        assertThat(reporter().reportDiscovery()).isNull();

        verify(sentinelConnection, times(3)).masters();
        assertThat(messages(Level.WARN)).anySatisfy(line -> assertThat(line)
                .contains("was not discovered in 3 probes")
                .contains("degrades to MySQL reads"));
    }

    @Test
    void probeBudgetAndConnectionCleanupComeFromConfigurationNotCode() throws Exception {
        accessProperties.getSentinel().setDiscoveryReportAttempts(5);
        accessProperties.validate();
        when(sentinelConnection.masters()).thenReturn(List.of());

        assertThat(reporter().reportDiscovery()).isNull();
        // Exactly the configured number of probes, and every Sentinel connection
        // is closed (try-with-resources) - no leaked Sentinel socket.
        verify(sentinelConnection, times(5)).masters();
        verify(sentinelConnection, times(5)).close();
        verify(connectionFactory, times(5)).getSentinelConnection();
    }

    @Test
    void standaloneFactoryWithoutSentinelConfigurationReportsNothingAndDoesNotProbe() {
        when(connectionFactory.getSentinelConfiguration()).thenReturn(null);
        when(sentinelConnection.masters()).thenReturn(List.of(
                reportedServer("mymaster", "172.28.190.12", 6379, "master")));

        assertThat(reporter().reportDiscovery()).isNull();
        verify(connectionFactory, never()).getSentinelConnection();
        verify(sentinelConnection, never()).masters();
        assertThat(messages(Level.WARN)).anySatisfy(line -> assertThat(line)
                .contains("no Sentinel configuration"));
    }

    private List<String> messages(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
