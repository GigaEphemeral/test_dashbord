package com.javalearn.lab.chaos.cluster;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ExecResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the InnoDB Cluster membership view through the container gateway and
 * publishes the D11 metrics (design §8.3).
 *
 * <p><b>Isolation.</b> The probe owns one dedicated daemon scheduler with a
 * single thread, so a slow or hung {@code docker exec} can never occupy the
 * chaos-action scheduler or delay a TTL revert. The scheduler is created and
 * shut down with this bean; no thread is ever created per tick.</p>
 *
 * <p><b>Failure policy.</b> Every exception is caught inside {@link #pollOnce()};
 * nothing escapes into the scheduler (a thrown exception would silently cancel
 * a {@code scheduleAtFixedRate} task). A failed probe increments
 * {@code mysql_cluster_probe_failure_total}, sets {@code mysql_cluster_probe_up}
 * to 0 and keeps the last successfully published member/primary series - the
 * panel can therefore tell "probe broken" (probe_up=0, last-known topology
 * still visible) from "cluster degraded" (probe_up=1 with a member not ONLINE).
 * Only a successful response replaces or clears series.</p>
 *
 * <p><b>Stale series.</b> Member and primary meters are published through two
 * {@link MultiGauge} instances. Each successful poll re-registers the complete
 * row set, and MultiGauge removes any meter whose tag set is absent, so a member
 * that disappeared or moved ONLINE→OFFLINE (role/state are labels) can never
 * leave a phantom {@code =1} series behind.</p>
 *
 * <p><b>Logging.</b> A WARN is emitted on a probe state transition or a
 * membership change, never once per tick: repeated failures and an unchanged
 * topology log at DEBUG.</p>
 *
 * <p><b>Self-detection.</b> The probe execs into a fixed node, but Group
 * Replication can elect any member as PRIMARY after a failover. When a
 * successful probe sees its own target as the primary, one WARN is logged per
 * state change: a drill that kills that node also kills the probe, and every
 * {@code mysql_cluster_*} series freezes until the node is resumed. Membership
 * reports {@code MEMBER_HOST} ({@code mysql-N}), while the probe target is the
 * container name ({@code jl-mysql-N}); the mapping is an explicit prefix strip,
 * never a substring/contains guess.</p>
 *
 * <p><b>Secrets.</b> The controller never holds the MySQL password. The
 * default command runs a bash script inside the node that expands
 * {@code MYSQL_PWD} from the container's own environment (the variable name is
 * configurable, the value never leaves the container). Only the SQL text (on
 * stdin) and two non-secret values (admin user name, password variable name)
 * cross the gateway.</p>
 */
public class MysqlClusterMonitor implements AutoCloseable {

    public static final String METRIC_MEMBER_STATE = "mysql_cluster_member_state";
    public static final String METRIC_PRIMARY_INFO = "mysql_cluster_primary_info";
    public static final String METRIC_MEMBER_COUNT = "mysql_cluster_member_count";
    public static final String METRIC_PROBE_UP = "mysql_cluster_probe_up";
    public static final String METRIC_PROBE_SUCCESS = "mysql_cluster_probe_success_total";
    public static final String METRIC_PROBE_FAILURE = "mysql_cluster_probe_failure_total";

    public static final String TAG_MEMBER = "member";
    public static final String TAG_ROLE = "role";
    public static final String TAG_STATE = "state";

    /** Exec environment consumed by the default command; neither value is a secret. */
    public static final String ENV_ADMIN_USER = "MYSQL_CLUSTER_ADMIN_USER";
    public static final String ENV_PASSWORD_VARIABLE = "MYSQL_CLUSTER_PASSWORD_ENV";
    /** SQL travels as an env var: docker-java hangs when an exec attaches stdin (see step-31). */
    public static final String ENV_QUERY = "MYSQL_CLUSTER_QUERY";

    private static final Logger log = LoggerFactory.getLogger(MysqlClusterMonitor.class);
    private static final int MAX_REASON = 200;
    /** Lab container prefix; membership reports the bare {@code mysql-N} host. */
    private static final String CONTAINER_PREFIX = "jl-";

    private final ContainerGateway gateway;
    private final ChaosProperties.MysqlCluster config;
    private final ScheduledExecutorService scheduler;

    private final MultiGauge memberStateGauge;
    private final MultiGauge primaryGauge;
    private final AtomicInteger probeUp = new AtomicInteger();
    private final AtomicInteger observedMembers = new AtomicInteger();
    private final Counter probeSuccess;
    private final Counter probeFailure;

    /** null until the first probe outcome; true/false afterwards. */
    private Boolean lastProbeOk;
    private Map<String, Observation> lastMembers = Map.of();
    private int lastWarnedMemberCount = -1;
    private long lastPrimaryCount = -1;
    /** Last observed "probe target is PRIMARY"; null until the first successful probe. */
    private Boolean lastSelfPrimary;

    /** Production constructor: owns and starts a dedicated single-thread scheduler. */
    public MysqlClusterMonitor(ContainerGateway gateway, ChaosProperties properties, MeterRegistry registry) {
        this(gateway, properties, registry, newScheduler(properties.getMysqlCluster()), true);
    }

    /**
     * Test seam: an external scheduler that is not started automatically; call
     * {@link #pollOnce()} directly.
     */
    MysqlClusterMonitor(ContainerGateway gateway,
                        ChaosProperties properties,
                        MeterRegistry registry,
                        ScheduledExecutorService scheduler) {
        this(gateway, properties, registry, scheduler, false);
    }

    private MysqlClusterMonitor(ContainerGateway gateway,
                                ChaosProperties properties,
                                MeterRegistry registry,
                                ScheduledExecutorService scheduler,
                                boolean start) {
        this.gateway = gateway;
        this.config = properties.getMysqlCluster();
        this.config.validate();
        if (!config.isEnabled()) {
            // Probe disabled (dev stack without the db-ha tier): no meters, no
            // scheduler, no Docker call. The bean still exists so injection and
            // lifecycle are uniform.
            if (scheduler != null) {
                scheduler.shutdown();
            }
            this.scheduler = null;
            this.memberStateGauge = null;
            this.primaryGauge = null;
            this.probeSuccess = null;
            this.probeFailure = null;
            log.info("MySQL cluster probe is disabled (chaos.mysql-cluster.enabled=false)");
            return;
        }
        this.scheduler = scheduler;
        this.memberStateGauge = MultiGauge.builder(METRIC_MEMBER_STATE)
                .description("1 for the membership row reported by the InnoDB Cluster probe")
                .register(registry);
        this.primaryGauge = MultiGauge.builder(METRIC_PRIMARY_INFO)
                .description("1 for the member currently reported as PRIMARY")
                .register(registry);
        Gauge.builder(METRIC_PROBE_UP, probeUp, AtomicInteger::doubleValue)
                .description("1 when the last membership probe succeeded, 0 when it failed")
                .register(registry);
        Gauge.builder(METRIC_MEMBER_COUNT, observedMembers, AtomicInteger::doubleValue)
                .description("Member rows observed by the last successful probe")
                .register(registry);
        this.probeSuccess = Counter.builder(METRIC_PROBE_SUCCESS)
                .description("Successful MySQL cluster membership probes")
                .register(registry);
        this.probeFailure = Counter.builder(METRIC_PROBE_FAILURE)
                .description("Failed MySQL cluster membership probes (exec, timeout or parse)")
                .register(registry);
        if (start) {
            scheduler.scheduleAtFixedRate(this::pollOnce, 0, config.getIntervalSeconds(), TimeUnit.SECONDS);
            log.info("MySQL cluster probe scheduled every {}s against {} (expected {} members)",
                    config.getIntervalSeconds(), config.getContainer(), config.getExpectedMemberCount());
        }
    }

    private static ScheduledExecutorService newScheduler(ChaosProperties.MysqlCluster config) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, config.getThreadName());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Runs one probe. Never throws: exec failures, timeouts, non-zero exits and
     * unparseable output all become a failure sample plus a probe_up=0.
     */
    synchronized void pollOnce() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            List<String> env = List.of(
                    ENV_ADMIN_USER + "=" + config.getAdminUser(),
                    ENV_PASSWORD_VARIABLE + "=" + config.getPasswordEnvVar(),
                    ENV_QUERY + "=" + config.getQuery());
            ExecResult result = gateway.execInContainer(config.getContainer(), config.getCommand(),
                    env, null, config.getExecTimeoutSeconds());
            if (result == null) {
                throw new MysqlClusterProbeException("gateway returned no result");
            }
            if (result.timedOut()) {
                throw new MysqlClusterProbeException("timed out after " + config.getExecTimeoutSeconds() + "s");
            }
            if (!result.succeeded()) {
                throw new MysqlClusterProbeException(failureDetail(result));
            }
            onSuccess(ClusterMembershipParser.parse(result.stdout()));
        } catch (Exception e) {
            onFailure(e);
        }
    }

    private String failureDetail(ExecResult result) {
        StringBuilder detail = new StringBuilder("exit code ").append(result.exitCode());
        String stderr = result.stderr() == null ? "" : result.stderr().strip();
        if (!stderr.isEmpty()) {
            detail.append(": ").append(stderr.lines().findFirst().orElse(""));
        }
        return detail.toString();
    }

    private void onSuccess(ClusterMembership membership) {
        int size = membership.size();
        if (lastProbeOk == null) {
            log.info("MySQL cluster probe started: {} member(s), primary {}",
                    size, membership.primary().map(ClusterMember::member).orElse("none"));
        } else if (!lastProbeOk) {
            log.info("MySQL cluster probe recovered: {} member(s), primary {}",
                    size, membership.primary().map(ClusterMember::member).orElse("none"));
        }
        lastProbeOk = true;
        probeUp.set(1);
        probeSuccess.increment();
        observedMembers.set(size);
        publishMembership(membership);
        logMembershipChanges(membership);
        logSelfPrimary(membership);
    }

    private void publishMembership(ClusterMembership membership) {
        List<MultiGauge.Row<?>> memberRows = new ArrayList<>();
        for (ClusterMember member : membership.members()) {
            memberRows.add(MultiGauge.Row.of(Tags.of(
                            TAG_MEMBER, member.member(),
                            TAG_ROLE, member.role().name(),
                            TAG_STATE, member.state().name()),
                    1));
        }
        // Re-registering the whole set makes MultiGauge drop every absent tag set.
        memberStateGauge.register(memberRows, true);

        List<MultiGauge.Row<?>> primaryRows = new ArrayList<>();
        for (ClusterMember member : membership.primaries()) {
            primaryRows.add(MultiGauge.Row.of(Tags.of(TAG_MEMBER, member.member()), 1));
        }
        primaryGauge.register(primaryRows, true);
    }

    private void logMembershipChanges(ClusterMembership membership) {
        Map<String, Observation> current = new LinkedHashMap<>();
        for (ClusterMember member : membership.members()) {
            current.put(member.member(), new Observation(member.role(), member.state()));
        }

        lastMembers.forEach((name, old) -> {
            if (!current.containsKey(name)) {
                log.warn("MySQL cluster member disappeared: {} (last {}/{}) - its metric series were cleared",
                        name, old.role(), old.state());
            }
        });
        current.forEach((name, now) -> {
            Observation old = lastMembers.get(name);
            if (old == null) {
                log.warn("MySQL cluster member appeared: {} ({}/{})", name, now.role(), now.state());
            } else if (old != now) {
                log.warn("MySQL cluster member {} changed: {}/{} -> {}/{}",
                        name, old.role(), old.state(), now.role(), now.state());
            }
        });

        int expected = config.getExpectedMemberCount();
        int observed = membership.size();
        if (observed != expected && observed != lastWarnedMemberCount) {
            log.warn("MySQL cluster membership has {} member(s), expected {} (chaos.mysql-cluster.expected-member-count)",
                    observed, expected);
        }
        lastWarnedMemberCount = observed;

        long primaries = membership.primaryCount();
        if (lastPrimaryCount >= 0 && lastPrimaryCount != primaries) {
            log.warn("MySQL cluster primary count changed: {} -> {}", lastPrimaryCount, primaries);
        }
        if (primaries > 1 && lastPrimaryCount != primaries) {
            log.warn("MySQL cluster reports multiple primaries (split brain): {}",
                    membership.primaries().stream().map(ClusterMember::member).toList());
        }
        lastPrimaryCount = primaries;

        lastMembers = current;
    }

    /**
     * Maps a probe container name to the {@code MEMBER_HOST} the group reports.
     * Compose sets {@code report_host=mysql-N} while the container is
     * {@code jl-mysql-N}, so only the leading lab prefix is removed. A name
     * without the prefix is returned unchanged; matching is always whole-string
     * (never {@code contains}), so {@code jl-mysql-1} cannot match
     * {@code mysql-10}.
     */
    static String membershipHost(String container) {
        return container.startsWith(CONTAINER_PREFIX)
                ? container.substring(CONTAINER_PREFIX.length())
                : container;
    }

    /**
     * WARNs once per state change when the probed node itself is the PRIMARY.
     * That is the teaching point: the probe looks healthy while the cluster is
     * healthy, but the very drill the lab runs (kill the primary) takes the
     * probe down with its target, and {@code mysql_cluster_*} freezes instead
     * of reporting the failover.
     */
    private void logSelfPrimary(ClusterMembership membership) {
        String selfHost = membershipHost(config.getContainer());
        boolean selfPrimary = membership.primaries().stream()
                .anyMatch(member -> selfHost.equals(member.member()));
        if (selfPrimary && !Boolean.TRUE.equals(lastSelfPrimary)) {
            log.warn("MySQL probe target {} ({}) is the current PRIMARY: a failover drill that kills this node "
                            + "also stops the probe, so mysql_cluster_* freezes until it returns. Point "
                            + "CHAOS_MYSQL_CLUSTER_CONTAINER at a different member.",
                    config.getContainer(), selfHost);
        } else if (!selfPrimary && Boolean.TRUE.equals(lastSelfPrimary)) {
            log.info("MySQL probe target {} ({}) is no longer the PRIMARY (now {}); the probe is drill-safe",
                    config.getContainer(), selfHost,
                    membership.primary().map(ClusterMember::member).orElse("none"));
        }
        lastSelfPrimary = selfPrimary;
    }

    private void onFailure(Exception e) {
        probeUp.set(0);
        probeFailure.increment();
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON) + "...";
        }
        if (!Boolean.FALSE.equals(lastProbeOk)) {
            log.warn("MySQL cluster probe failed against {}: {} - last successful member series are kept, {} = 0",
                    config.getContainer(), reason, METRIC_PROBE_UP);
        } else {
            log.debug("MySQL cluster probe still failing against {}: {}", config.getContainer(), reason);
        }
        lastProbeOk = false;
    }

    /**
     * Stops the dedicated scheduler. {@code shutdownTimeoutSeconds=0} skips the
     * grace period; otherwise the scheduler gets that long to finish an in-flight
     * exec before {@code shutdownNow}. Does not touch meters (the registry is
     * discarded with the application context).
     */
    @Override
    public void close() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (config.getShutdownTimeoutSeconds() <= 0
                    || !scheduler.awaitTermination(config.getShutdownTimeoutSeconds(), TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record Observation(MemberRole role, MemberState state) {
    }
}
