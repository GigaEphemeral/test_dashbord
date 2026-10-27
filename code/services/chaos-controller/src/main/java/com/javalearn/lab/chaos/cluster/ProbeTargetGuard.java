package com.javalearn.lab.chaos.cluster;

import com.javalearn.lab.chaos.config.ChaosProperties;

/**
 * Forces the MySQL cluster probe and the fault target apart (Stage2 B0-08
 * slice B, 方案 §10.4 / plan B0-08).
 *
 * <p><b>Why.</b> The membership probe execs into exactly one container
 * ({@code chaos.mysql-cluster.container}, lab default {@code jl-mysql-3}). The
 * db-failover drill kills the PRIMARY. If the drill's target and the probe's
 * home are the same container, the fault kills the observer as well:
 * {@code mysql_cluster_probe_up} and every {@code mysql_cluster_member_state}
 * series freeze at the exact moment the panel is supposed to show the failover,
 * and the drill teaches "the cluster went silent" instead of "the cluster
 * recovered". That is a self-inflicted observation loss (观测自损), not a
 * cluster symptom.</p>
 *
 * <p><b>Rule implemented here (the reject variant).</b> A destructive container
 * action - {@code kill}, {@code freeze} or {@code restart} - whose target is the
 * configured probe container is refused with
 * {@link com.javalearn.lab.chaos.error.ProbeTargetProtectedException}
 * (HTTP 403 / {@code 40302}) before any Docker call. The remedy is stated in the
 * message: move the probe to another member
 * ({@code CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-<n>}), then fault the old
 * target. The alternative - a switch that lets the operator admit the collision
 * explicitly - is deliberately NOT implemented in this step; see the B0-08
 * handover note (Future).</p>
 *
 * <p><b>What stays allowed.</b> {@code start} and {@code resume} never hit this
 * rule: a fault that was allowed to be applied must always be allowed to be
 * undone, and both calls are what bring the probe back. The guard is also
 * inactive when the probe is disabled ({@code enabled=false}, e.g. the dev stack
 * without the db-ha tier) or when no container is configured - there is then no
 * observer to protect.</p>
 *
 * <p><b>Comparison.</b> Whole-name equality against the trimmed configured value,
 * never a prefix or {@code contains} test, so {@code jl-mysql-3} cannot shield
 * {@code jl-mysql-30} and an id reference is checked against the canonical name
 * the caller resolved to (see {@code ChaosActionService}).</p>
 */
public final class ProbeTargetGuard {

    /** Rule key quoted in the refusal; the property the probe container comes from. */
    public static final String RULE_KEY = "chaos.mysql-cluster.container";

    /** Environment variable a lab operator sets to move the probe. */
    public static final String ENV_KEY = "CHAOS_MYSQL_CLUSTER_CONTAINER";

    private final String probeContainer;

    public ProbeTargetGuard(String probeContainer, boolean probeEnabled) {
        String configured = probeContainer == null ? "" : probeContainer.trim();
        this.probeContainer = probeEnabled && !configured.isEmpty() ? configured : null;
    }

    /** Guard built from {@code chaos.mysql-cluster.*}. */
    public static ProbeTargetGuard from(ChaosProperties properties) {
        ChaosProperties.MysqlCluster config = properties.getMysqlCluster();
        return new ProbeTargetGuard(config.getContainer(), config.isEnabled());
    }

    /** The protected container, or {@code null} when the guard is inactive. */
    public String probeContainer() {
        return probeContainer;
    }

    /** True when this guard can refuse anything at all. */
    public boolean active() {
        return probeContainer != null;
    }

    /** True when faulting {@code container} would take the probe down with it. */
    public boolean targetsProbe(String container) {
        return probeContainer != null && container != null && probeContainer.equals(container.trim());
    }

    /**
     * Refusal text naming the rule, the collision and the remedy, or {@code null}
     * when the target is not the probe container.
     */
    public String rejectionReason(String container) {
        if (!targetsProbe(container)) {
            return null;
        }
        return "target \"" + probeContainer + "\" is the MySQL cluster probe container (" + RULE_KEY
                + " / env " + ENV_KEY + "): kill/freeze/restart on it stops the probe as well, so"
                + " mysql_cluster_probe_up and every mysql_cluster_* series freeze exactly while the"
                + " failover drill runs (self-inflicted observation loss, not a cluster symptom)."
                + " Move the probe to another member first (for example " + ENV_KEY
                + "=jl-mysql-<n>, then restart chaos-controller) and only then fault " + probeContainer
                + "; start/resume on it stay allowed, because they are what brings it back.";
    }
}
