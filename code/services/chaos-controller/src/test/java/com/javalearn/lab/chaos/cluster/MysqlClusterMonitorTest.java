package com.javalearn.lab.chaos.cluster;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlClusterMonitorTest {

    private static final String HEALTHY = "mysql-1\t3306\tONLINE\tPRIMARY\n"
            + "mysql-2\t3306\tONLINE\tSECONDARY\n"
            + "mysql-3\t3306\tONLINE\tSECONDARY\n";

    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private MysqlClusterMonitor monitor;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.close();
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

    private MysqlClusterMonitor newMonitor(FakeGateway gateway, ChaosProperties properties) {
        return new MysqlClusterMonitor(gateway, properties, registry, scheduler);
    }

    private static ChaosProperties properties(boolean enabled) {
        ChaosProperties properties = new ChaosProperties();
        properties.getMysqlCluster().setEnabled(enabled);
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
