package com.javalearn.lab.chaos.cluster;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlClusterMonitorTest {

    private static final String HEALTHY = "mysql-1\t3306\tONLINE\tPRIMARY\n"
            + "mysql-2\t3306\tONLINE\tSECONDARY\n"
            + "mysql-3\t3306\tONLINE\tSECONDARY\n";

    private static final String PRIMARY_MOVED_TO_2 = "mysql-1\t3306\tONLINE\tSECONDARY\n"
            + "mysql-2\t3306\tONLINE\tPRIMARY\n"
            + "mysql-3\t3306\tONLINE\tSECONDARY\n";

    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private MysqlClusterMonitor monitor;
    private ListAppender<ILoggingEvent> logs;
    private Logger monitorLogger;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.close();
        }
        if (logs != null) {
            monitorLogger.detachAppender(logs);
            logs.stop();
        }
        registry.close();
    }

    @Test
    void publishesBoundedMembershipAndPrimarySeries() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(true));
        monitor.pollOnce();

        String scrape = registry.scrape();
        assertThat(scrape).contains("mysql_cluster_member_state{member=\"mysql-1\",role=\"PRIMARY\",state=\"ONLINE\"} 1.0");
        assertThat(scrape).contains("mysql_cluster_member_state{member=\"mysql-2\",role=\"SECONDARY\",state=\"ONLINE\"} 1.0");
        assertThat(scrape).contains("mysql_cluster_primary{member=\"mysql-1\"} 1.0");
        assertThat(scrape).contains("mysql_cluster_probe_up 1.0");
        assertThat(scrape).contains("mysql_cluster_member_count 3.0");
        assertThat(gateway.execCalls.get()).isEqualTo(1);
    }

    @Test
    void aFailedProbeKeepsTheLastMemberSeriesAndFlagsProbeDown() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(true));
        monitor.pollOnce();
        gateway.result = new ExecResult("", "ERROR 1045 (28000): denied", 1, false);

        monitor.pollOnce();

        String scrape = registry.scrape();
        assertThat(scrape).contains("mysql_cluster_probe_up 0.0");
        assertThat(scrape).contains("mysql_cluster_member_state{member=\"mysql-1\"");
        assertThat(scrape).contains("mysql_cluster_probe_failure_total 1.0");
    }

    @Test
    void aTimedOutProbeIsAFailureSample() {
        FakeGateway gateway = new FakeGateway(new ExecResult("", "", null, true));
        monitor = newMonitor(gateway, properties(true));

        monitor.pollOnce();

        String scrape = registry.scrape();
        assertThat(scrape).contains("mysql_cluster_probe_up 0.0");
        assertThat(scrape).contains("mysql_cluster_probe_failure_total 1.0");
        assertThat(scrape).doesNotContain("mysql_cluster_member_state{");
    }

    @Test
    void aProbeThatLosesAMemberDropsItsSeries() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(true));
        monitor.pollOnce();
        gateway.result = new ExecResult("mysql-1\t3306\tONLINE\tPRIMARY\n"
                + "mysql-2\t3306\tONLINE\tSECONDARY\n", "", 0, false);

        monitor.pollOnce();

        String scrape = registry.scrape();
        assertThat(scrape).contains("mysql_cluster_member_count 2.0");
        assertThat(scrape).doesNotContain("mysql-3");
    }

    @Test
    void disabledMonitorRegistersNoMetersAndNeverExecs() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(false));

        monitor.pollOnce();

        assertThat(gateway.execCalls.get()).isZero();
        assertThat(registry.find(MysqlClusterMonitor.METRIC_MEMBER_STATE).meters()).isEmpty();
        assertThat(registry.find(MysqlClusterMonitor.METRIC_PROBE_UP).meters()).isEmpty();
    }

    @Test
    void membershipHostMappingStripsTheLabPrefixOnly() {
        assertThat(MysqlClusterMonitor.membershipHost("jl-mysql-1")).isEqualTo("mysql-1");
        assertThat(MysqlClusterMonitor.membershipHost("jl-mysql-10")).isEqualTo("mysql-10");
        assertThat(MysqlClusterMonitor.membershipHost("mysql-2")).isEqualTo("mysql-2");
    }

    @Test
    void warnsOnceWhenTheProbeTargetIsThePrimary() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(true, "jl-mysql-1"));
        attachLogCapture();

        // Two successful ticks with an unchanged topology: one WARN, not one per tick.
        monitor.pollOnce();
        monitor.pollOnce();

        List<String> warnings = selfPrimaryWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("jl-mysql-1 (mysql-1) is the current PRIMARY")
                .contains("mysql_cluster_* freezes")
                .contains("CHAOS_MYSQL_CLUSTER_CONTAINER");
    }

    @Test
    void doesNotWarnWhenTheProbeTargetIsNotThePrimary() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        // jl-mysql-3 is a secondary in every fixture.
        monitor = newMonitor(gateway, properties(true, "jl-mysql-3"));
        attachLogCapture();
        monitor.pollOnce();

        assertThat(selfPrimaryWarnings()).isEmpty();
        assertThat(registry.scrape()).contains("mysql_cluster_probe_up 1.0");
    }

    @Test
    void doesNotWarnWhenTheContainerNameOnlySharesAPrefixWithTheHost() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        // jl-mysql-10 maps to mysql-10: matching is whole-string, so the
        // mysql-1 PRIMARY row must not be mistaken for the probe target.
        monitor = newMonitor(gateway, properties(true, "jl-mysql-10"));
        attachLogCapture();
        monitor.pollOnce();

        assertThat(selfPrimaryWarnings()).isEmpty();
        assertThat(registry.scrape()).contains("mysql_cluster_probe_up 1.0");
    }

    @Test
    void warnsAgainOnlyAfterThePrimaryMovesAwayAndBack() {
        FakeGateway gateway = new FakeGateway(new ExecResult(HEALTHY, "", 0, false));
        monitor = newMonitor(gateway, properties(true, "jl-mysql-1"));
        attachLogCapture();

        monitor.pollOnce();
        assertThat(selfPrimaryWarnings()).hasSize(1);

        // Primary moves to mysql-2: the target is drill-safe again, no new WARN.
        gateway.result = new ExecResult(PRIMARY_MOVED_TO_2, "", 0, false);
        monitor.pollOnce();
        assertThat(selfPrimaryWarnings()).hasSize(1);

        // A later failover promotes mysql-1 again: one new WARN per state change.
        gateway.result = new ExecResult(HEALTHY, "", 0, false);
        monitor.pollOnce();
        monitor.pollOnce();
        assertThat(selfPrimaryWarnings()).hasSize(2);
    }

    private void attachLogCapture() {
        monitorLogger = (Logger) LoggerFactory.getLogger(MysqlClusterMonitor.class);
        logs = new ListAppender<>();
        logs.start();
        monitorLogger.addAppender(logs);
    }

    private List<String> selfPrimaryWarnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("is the current PRIMARY"))
                .toList();
    }

    private MysqlClusterMonitor newMonitor(FakeGateway gateway, ChaosProperties properties) {
        return new MysqlClusterMonitor(gateway, properties, registry, scheduler);
    }

    private static ChaosProperties properties(boolean enabled) {
        return properties(enabled, null);
    }

    private static ChaosProperties properties(boolean enabled, String container) {
        ChaosProperties properties = new ChaosProperties();
        properties.getMysqlCluster().setEnabled(enabled);
        if (container != null) {
            properties.getMysqlCluster().setContainer(container);
        }
        return properties;
    }

    private static final class FakeGateway implements ContainerGateway {

        private final AtomicInteger execCalls = new AtomicInteger();
        private volatile ExecResult result;

        private FakeGateway(ExecResult result) {
            this.result = result;
        }

        @Override
        public List<ContainerView> listLabContainers() {
            return List.of();
        }

        @Override
        public String tailLogs(String containerName, int tail) {
            return "";
        }

        @Override
        public ContainerView inspectLabContainer(String containerName) {
            return new ContainerView(containerName, "running", "healthy");
        }

        @Override
        public void stop(String containerName, int graceSeconds) {
        }

        @Override
        public void start(String containerName) {
        }

        @Override
        public void restart(String containerName, int graceSeconds) {
        }

        @Override
        public void signal(String containerName, String signal) {
        }

        @Override
        public ExecResult execInContainer(String containerName, List<String> command,
                                          List<String> env, String stdin, int timeoutSeconds) {
            execCalls.incrementAndGet();
            return result;
        }
    }
}
