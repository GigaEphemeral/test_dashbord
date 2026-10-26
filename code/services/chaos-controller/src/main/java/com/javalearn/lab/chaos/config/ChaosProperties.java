package com.javalearn.lab.chaos.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chaos action configuration. Every TTL, timeout, URL and credential is
 * externalized here (see application.yml / compose env) so the controller has
 * no hardcoded timing or endpoint.
 */
@ConfigurationProperties(prefix = "chaos")
public class ChaosProperties {

    private static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");
    private static final Pattern ENV_VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_MEMBERS = 16;

    /** TTL applied when kill/freeze does not send one. */
    private int defaultTtlSeconds = 120;

    /** Upper bound accepted for a caller-supplied TTL. */
    private int maxTtlSeconds = 600;

    /** Deadline for post-start/post-resume readiness probing. */
    private int actionTimeoutSeconds = 30;

    /** Watchdog sweep interval (second safety net for expired actions). */
    private int watchdogIntervalSeconds = 5;

    /** Docker stop grace period handed to the daemon. */
    private int stopGraceSeconds = 10;

    /** Revert attempts before a FAILED action stays failed until stop-all. */
    private int maxRevertAttempts = 3;

    /** Core threads of the chaos scheduler (TTL reverts + watchdog + readiness probes). */
    private int schedulerThreads = 4;

    /** Fault cleanup when the interactive guide reaches a terminal state. */
    private ResetMode resetMode = ResetMode.MANUAL;

    private final Datastore datastore = new Datastore();

    private final Annotation annotation = new Annotation();

    private final Guide guide = new Guide();

    private final Traffic traffic = new Traffic();

    private final MysqlCluster mysqlCluster = new MysqlCluster();

    /**
     * Fails the context fast on a nonsensical probe configuration instead of
     * surfacing it as a recurring probe failure at runtime.
     */
    @PostConstruct
    public void validate() {
        mysqlCluster.validate();
    }

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(int defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public int getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(int maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public int getActionTimeoutSeconds() {
        return actionTimeoutSeconds;
    }

    public void setActionTimeoutSeconds(int actionTimeoutSeconds) {
        this.actionTimeoutSeconds = actionTimeoutSeconds;
    }

    public int getWatchdogIntervalSeconds() {
        return watchdogIntervalSeconds;
    }

    public void setWatchdogIntervalSeconds(int watchdogIntervalSeconds) {
        this.watchdogIntervalSeconds = watchdogIntervalSeconds;
    }

    public int getStopGraceSeconds() {
        return stopGraceSeconds;
    }

    public void setStopGraceSeconds(int stopGraceSeconds) {
        this.stopGraceSeconds = stopGraceSeconds;
    }

    public int getMaxRevertAttempts() {
        return maxRevertAttempts;
    }

    public void setMaxRevertAttempts(int maxRevertAttempts) {
        this.maxRevertAttempts = maxRevertAttempts;
    }

    public int getSchedulerThreads() {
        return schedulerThreads;
    }

    public void setSchedulerThreads(int schedulerThreads) {
        this.schedulerThreads = schedulerThreads;
    }

    public ResetMode getResetMode() {
        return resetMode;
    }

    public void setResetMode(ResetMode resetMode) {
        this.resetMode = resetMode;
    }

    public Datastore getDatastore() {
        return datastore;
    }

    public Annotation getAnnotation() {
        return annotation;
    }

    public Guide getGuide() {
        return guide;
    }

    public Traffic getTraffic() {
        return traffic;
    }

    public MysqlCluster getMysqlCluster() {
        return mysqlCluster;
    }

    /**
     * MySQL InnoDB Cluster membership probe (design §8.3, panel D11).
     *
     * <p>Enabled by default: the controller cannot tell at configuration time
     * whether the db-ha tier is running (and compose wiring is outside this
     * module), so an opt-in default would silently leave the D11 metrics absent
     * on the live stack. A missing container or unreachable daemon is not an
     * error: the probe reports {@code mysql_cluster_probe_up = 0}, logs one WARN
     * on the transition and keeps retrying at the configured interval. Set
     * {@code CHAOS_MYSQL_CLUSTER_ENABLED=false} to drop the probe (and its
     * meters) entirely on the dev stack.</p>
     *
     * <p>No credential lives here: the default command expands {@code MYSQL_PWD}
     * inside the node from the container's own environment, named by
     * {@link #passwordEnvVar}.</p>
     */
    public static class MysqlCluster {

        /** False disables the probe, its scheduler and every mysql_cluster_* meter. */
        private boolean enabled = true;

        /** Poll period; also the upper bound for one exec (timeout &lt;= interval). */
        private int intervalSeconds = 10;

        /** Lab container the probe execs into; must stay inside the db-ha tier. */
        private String container = "jl-mysql-1";

        /** MySQL account used by the probe; its password is read in-container. */
        private String adminUser = "root";

        /** Name of the container env variable holding the admin password. */
        private String passwordEnvVar = "MYSQL_ROOT_PASSWORD";

        /** Read-only membership query; its output is parsed, never executed as DML/DDL. */
        private String query = "SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, MEMBER_ROLE "
                + "FROM performance_schema.replication_group_members "
                + "ORDER BY MEMBER_ROLE DESC, MEMBER_HOST";

        /**
         * argv run inside the container. The SQL travels on stdin and the two
         * environment values are not secrets; the password is expanded in-container.
         */
        private List<String> command = new ArrayList<>(List.of(
                "/bin/bash",
                "-c",
                "MYSQL_PWD=\"${!MYSQL_CLUSTER_PASSWORD_ENV}\" exec mysql --batch --raw --skip-column-names "
                        + "-h127.0.0.1 -u\"$MYSQL_CLUSTER_ADMIN_USER\""));

        /** Java-side bound for one exec, smaller than the interval by validation. */
        private int execTimeoutSeconds = 6;

        /** Healthy group size; a different observed count logs one WARN per change. */
        private int expectedMemberCount = 3;

        /** Name of the probe's dedicated single scheduler thread. */
        private String threadName = "mysql-cluster-probe";

        /** Grace period for an in-flight exec on context shutdown (0 = immediate). */
        private int shutdownTimeoutSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public String getContainer() {
            return container;
        }

        public void setContainer(String container) {
            this.container = container;
        }

        public String getAdminUser() {
            return adminUser;
        }

        public void setAdminUser(String adminUser) {
            this.adminUser = adminUser;
        }

        public String getPasswordEnvVar() {
            return passwordEnvVar;
        }

        public void setPasswordEnvVar(String passwordEnvVar) {
            this.passwordEnvVar = passwordEnvVar;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(List<String> command) {
            this.command = command;
        }

        public int getExecTimeoutSeconds() {
            return execTimeoutSeconds;
        }

        public void setExecTimeoutSeconds(int execTimeoutSeconds) {
            this.execTimeoutSeconds = execTimeoutSeconds;
        }

        public int getExpectedMemberCount() {
            return expectedMemberCount;
        }

        public void setExpectedMemberCount(int expectedMemberCount) {
            this.expectedMemberCount = expectedMemberCount;
        }

        public String getThreadName() {
            return threadName;
        }

        public void setThreadName(String threadName) {
            this.threadName = threadName;
        }

        public int getShutdownTimeoutSeconds() {
            return shutdownTimeoutSeconds;
        }

        public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
            this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        }

        /**
         * Typed validation, run at startup (and by the monitor's constructor).
         * Messages name the property path so a bad env value is easy to fix.
         */
        public void validate() {
            if (intervalSeconds < 1 || intervalSeconds > 3600) {
                throw new IllegalStateException("chaos.mysql-cluster.interval-seconds must be between 1 and 3600 (was "
                        + intervalSeconds + "); env CHAOS_MYSQL_CLUSTER_INTERVAL_SECONDS");
            }
            if (execTimeoutSeconds < 1 || execTimeoutSeconds > 300) {
                throw new IllegalStateException("chaos.mysql-cluster.exec-timeout-seconds must be between 1 and 300 (was "
                        + execTimeoutSeconds + "); env CHAOS_MYSQL_CLUSTER_EXEC_TIMEOUT_SECONDS");
            }
            if (execTimeoutSeconds > intervalSeconds) {
                throw new IllegalStateException("chaos.mysql-cluster.exec-timeout-seconds (" + execTimeoutSeconds
                        + ") must not exceed interval-seconds (" + intervalSeconds + "): a probe must not outlive its tick");
            }
            if (expectedMemberCount < 1 || expectedMemberCount > MAX_MEMBERS) {
                throw new IllegalStateException("chaos.mysql-cluster.expected-member-count must be between 1 and "
                        + MAX_MEMBERS + " (was " + expectedMemberCount + ")");
            }
            if (container == null || !CONTAINER_NAME.matcher(container).matches()) {
                throw new IllegalStateException("chaos.mysql-cluster.container must be a container name, was '"
                        + container + "'");
            }
            if (adminUser == null || adminUser.isBlank()) {
                throw new IllegalStateException("chaos.mysql-cluster.admin-user must not be blank");
            }
            if (passwordEnvVar == null || !ENV_VARIABLE_NAME.matcher(passwordEnvVar).matches()) {
                throw new IllegalStateException("chaos.mysql-cluster.password-env-var must be an environment variable "
                        + "name, was '" + passwordEnvVar + "'");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalStateException("chaos.mysql-cluster.query must not be blank");
            }
            if (command == null || command.isEmpty() || command.stream().anyMatch(token -> token == null || token.isBlank())) {
                throw new IllegalStateException("chaos.mysql-cluster.command must be a non-empty argv without blank tokens");
            }
            if (threadName == null || threadName.isBlank()) {
                throw new IllegalStateException("chaos.mysql-cluster.thread-name must not be blank");
            }
            if (shutdownTimeoutSeconds < 0 || shutdownTimeoutSeconds > 60) {
                throw new IllegalStateException("chaos.mysql-cluster.shutdown-timeout-seconds must be between 0 and 60 (was "
                        + shutdownTimeoutSeconds + ")");
            }
        }
    }

    /** Interactive guide (Task 22b): scenario directory + target allow-list. */
    public static class Guide {
        /** Directory holding shared scenario JSON; compose mounts it read-only. */
        private String scenarioDir = "/app/scenarios";

        /** Container names a scenario may target; datastores need the allow-list too. */
        private String targetPattern = "^jl-app-[0-9]+$";

        public String getScenarioDir() {
            return scenarioDir;
        }

        public void setScenarioDir(String scenarioDir) {
            this.scenarioDir = scenarioDir;
        }

        public String getTargetPattern() {
            return targetPattern;
        }

        public void setTargetPattern(String targetPattern) {
            this.targetPattern = targetPattern;
        }
    }

    /**
     * Datastore fault targets (M3-1c). Closed by default: an empty pattern means
     * every datastore-looking container (mysql/redis/rabbit/mongo/postgres) is
     * refused, exactly like the old absolute prohibition. Only an explicitly
     * configured pattern can admit one, and the lab uses it for the Redis
     * Sentinel topology (see doc/process/step-24c-sentinel-targets.md). It is a
     * teaching-lab safety valve, not a production affordance: the standalone
     * {@code jl-redis}, {@code jl-mysql}, {@code jl-rabbitmq}, the exporters and
     * every non-lab container must stay outside any pattern written to {@code .env}.
     */
    public static class Datastore {

        /** Whole-string regex of datastore containers a fault may target; empty = refuse all. */
        private String allowedTargetPattern = "";

        public String getAllowedTargetPattern() {
            return allowedTargetPattern;
        }

        public void setAllowedTargetPattern(String allowedTargetPattern) {
            this.allowedTargetPattern = allowedTargetPattern;
        }
    }

    /**
     * Background traffic (Task 23): the console starts/stops one fixed
     * compose-managed k6 container. It is deliberately not a chaos action - a
     * traffic container has no TTL revert, and a paused guide must not leave
     * load running or revert it early.
     */
    public static class Traffic {

        /** Container name created by compose under the {@code traffic} profile. */
        private String containerName = "jl-traffic";

        /** Docker stop grace for k6; SIGTERM makes k6 shut down gracefully. */
        private int stopGraceSeconds = 5;

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public int getStopGraceSeconds() {
            return stopGraceSeconds;
        }

        public void setStopGraceSeconds(int stopGraceSeconds) {
            this.stopGraceSeconds = stopGraceSeconds;
        }
    }

    public static class Annotation {

        /** Best-effort Grafana annotations; never fail the chaos action. */
        private boolean enabled = true;

        /** Grafana base URL; injected from compose/env (no URL in code). */
        private String grafanaUrl;

        private String user;

        private String password;

        private int timeoutSeconds = 3;

        /** Extra tags appended to the mandatory chaos/java-learn/<target>/<action> set. */
        private List<String> tags = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getGrafanaUrl() {
            return grafanaUrl;
        }

        public void setGrafanaUrl(String grafanaUrl) {
            this.grafanaUrl = grafanaUrl;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }
}
