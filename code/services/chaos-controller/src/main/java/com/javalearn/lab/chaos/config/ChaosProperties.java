package com.javalearn.lab.chaos.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final Pattern PROXY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
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

    private final Toxiproxy toxiproxy = new Toxiproxy();

    private final AppProxy appProxy = new AppProxy();

    /**
     * Fails the context fast on a nonsensical probe configuration instead of
     * surfacing it as a recurring probe failure at runtime.
     */
    @PostConstruct
    public void validate() {
        toxiproxy.validate();
        appProxy.validate();
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

    public Toxiproxy getToxiproxy() {
        return toxiproxy;
    }

    public AppProxy getAppProxy() {
        return appProxy;
    }

    /**
     * Narrow forwarding of the app-tier thread lab ({@code /lab/thread/**}) to
     * one application instance. Nginx deliberately never proxies the app's
     * {@code /lab/**} paths, so this controller-side hop is the console's only
     * way in; it is therefore limited to an instance allow-list, a path
     * allow-list, GET+POST and the shared token.
     */
    public static class AppProxy {

        /** App instance host names this proxy may reach; anything else is a 404. */
        private List<String> instances = new ArrayList<>(List.of("app-1", "app-2", "app-3"));

        /** Transport to the app instance; http on jl-net, https only for completeness. */
        private String scheme = "http";

        /** App HTTP port. */
        private int port = 8080;

        /** TCP connect timeout for one forwarded call. */
        private int connectTimeoutSeconds = 2;

        /** Whole-request timeout for one forwarded call. */
        private int requestTimeoutSeconds = 10;

        /** Upper bound for the forwarded request body (the fault JSON is tiny). */
        private int maxRequestBytes = 8192;

        public List<String> getInstances() {
            return instances;
        }

        public void setInstances(List<String> instances) {
            this.instances = instances;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public int getMaxRequestBytes() {
            return maxRequestBytes;
        }

        public void setMaxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
        }

        /** Typed startup validation; messages name the property and its env variable. */
        public void validate() {
            if (instances == null || instances.isEmpty()) {
                throw new IllegalStateException("chaos.app-proxy.instances must list at least one app instance; "
                        + "env CHAOS_APP_PROXY_INSTANCES");
            }
            Set<String> seen = new HashSet<>();
            for (String instance : instances) {
                if (instance == null || !CONTAINER_NAME.matcher(instance).matches()) {
                    throw new IllegalStateException("chaos.app-proxy.instances contains an invalid instance name '"
                            + instance + "' (expected " + CONTAINER_NAME.pattern() + "); env CHAOS_APP_PROXY_INSTANCES");
                }
                if (!seen.add(instance)) {
                    throw new IllegalStateException("chaos.app-proxy.instances contains duplicate instance '"
                            + instance + "'");
                }
            }
            String normalizedScheme = scheme == null ? "" : scheme.trim().toLowerCase();
            if (!("http".equals(normalizedScheme) || "https".equals(normalizedScheme))) {
                throw new IllegalStateException("chaos.app-proxy.scheme must be http or https (was '"
                        + scheme + "'); env CHAOS_APP_PROXY_SCHEME");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("chaos.app-proxy.port must be between 1 and 65535 (was "
                        + port + "); env CHAOS_APP_PROXY_PORT");
            }
            if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
                throw new IllegalStateException("chaos.app-proxy.connect-timeout-seconds must be between 1 and 60 (was "
                        + connectTimeoutSeconds + "); env CHAOS_APP_PROXY_CONNECT_TIMEOUT_SECONDS");
            }
            if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60) {
                throw new IllegalStateException("chaos.app-proxy.request-timeout-seconds must be between 1 and 60 (was "
                        + requestTimeoutSeconds + "); env CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS");
            }
            if (maxRequestBytes < 1 || maxRequestBytes > 1_048_576) {
                throw new IllegalStateException("chaos.app-proxy.max-request-bytes must be between 1 and 1048576 (was "
                        + maxRequestBytes + "); env CHAOS_APP_PROXY_MAX_REQUEST_BYTES");
            }
        }
    }

    /**
     * Toxiproxy network-fault layer (design §11.3). The controller is only a
     * client: the proxy topology lives in the {@code chaos-proxy} compose
     * overlay and applications are restarted to use it (design §3.3), so this
     * block holds no topology, only the control URL, timeouts, an allow-list of
     * proxy names and the bounds a toxic request may use.
     *
     * <p>Enabled by default so the capability is present when the overlay is
     * up; without Toxiproxy the endpoints answer 503/50301 and stop-all reports
     * a failed network cleanup instead of hanging or NPE-ing. Set
     * {@code CHAOS_TOXIPROXY_ENABLED=false} on stacks that never run the
     * {@code chaos-proxy} profile.</p>
     */
    public static class Toxiproxy {

        /** False disables every /chaos/toxiproxy endpoint and the stop-all hook. */
        private boolean enabled = true;

        /** Toxiproxy control API base URL; never contains credentials. */
        private String url = "http://toxiproxy:8474";

        /** TCP connect timeout for control calls. */
        private int connectTimeoutSeconds = 2;

        /** Whole-request timeout for control calls (toxics are tiny JSON). */
        private int requestTimeoutSeconds = 5;

        /** Proxy names this controller may touch; anything else is a 404. */
        private List<String> proxies = new ArrayList<>(List.of("redis", "mysql"));

        /** Latency used when the request omits latencyMs. */
        private int defaultLatencyMs = 500;

        /** Upper bound accepted for latencyMs. */
        private int maxLatencyMs = 30000;

        /** Jitter used when the request omits jitterMs. */
        private int defaultJitterMs = 50;

        /** Upper bound accepted for jitterMs. */
        private int maxJitterMs = 5000;

        /** Bandwidth used when the request omits rateKbps (KB/s). */
        private int defaultBandwidthKbps = 100;

        /** Upper bound accepted for rateKbps. */
        private int maxBandwidthKbps = 10240;

        /** Network-cut timeout used when the request omits timeoutMs. */
        private int defaultTimeoutMs = 1000;

        /** Upper bound accepted for timeoutMs. */
        private int maxTimeoutMs = 60000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public List<String> getProxies() {
            return proxies;
        }

        public void setProxies(List<String> proxies) {
            this.proxies = proxies;
        }

        public int getDefaultLatencyMs() {
            return defaultLatencyMs;
        }

        public void setDefaultLatencyMs(int defaultLatencyMs) {
            this.defaultLatencyMs = defaultLatencyMs;
        }

        public int getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(int maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }

        public int getDefaultJitterMs() {
            return defaultJitterMs;
        }

        public void setDefaultJitterMs(int defaultJitterMs) {
            this.defaultJitterMs = defaultJitterMs;
        }

        public int getMaxJitterMs() {
            return maxJitterMs;
        }

        public void setMaxJitterMs(int maxJitterMs) {
            this.maxJitterMs = maxJitterMs;
        }

        public int getDefaultBandwidthKbps() {
            return defaultBandwidthKbps;
        }

        public void setDefaultBandwidthKbps(int defaultBandwidthKbps) {
            this.defaultBandwidthKbps = defaultBandwidthKbps;
        }

        public int getMaxBandwidthKbps() {
            return maxBandwidthKbps;
        }

        public void setMaxBandwidthKbps(int maxBandwidthKbps) {
            this.maxBandwidthKbps = maxBandwidthKbps;
        }

        public int getDefaultTimeoutMs() {
            return defaultTimeoutMs;
        }

        public void setDefaultTimeoutMs(int defaultTimeoutMs) {
            this.defaultTimeoutMs = defaultTimeoutMs;
        }

        public int getMaxTimeoutMs() {
            return maxTimeoutMs;
        }

        public void setMaxTimeoutMs(int maxTimeoutMs) {
            this.maxTimeoutMs = maxTimeoutMs;
        }

        /**
         * Typed startup validation. An invalid value fails the context with the
         * property path and env variable, exactly like the mysql-cluster probe.
         */
        public void validate() {
            if (maxLatencyMs < 1 || maxLatencyMs > 600_000) {
                throw new IllegalStateException("chaos.toxiproxy.max-latency-ms must be between 1 and 600000 (was "
                        + maxLatencyMs + "); env CHAOS_TOXIPROXY_MAX_LATENCY_MS");
            }
            if (defaultLatencyMs < 0 || defaultLatencyMs > maxLatencyMs) {
                throw new IllegalStateException("chaos.toxiproxy.default-latency-ms must be between 0 and max-latency-ms ("
                        + maxLatencyMs + "), was " + defaultLatencyMs + "; env CHAOS_TOXIPROXY_DEFAULT_LATENCY_MS");
            }
            if (maxJitterMs < 0 || maxJitterMs > 600_000) {
                throw new IllegalStateException("chaos.toxiproxy.max-jitter-ms must be between 0 and 600000 (was "
                        + maxJitterMs + "); env CHAOS_TOXIPROXY_MAX_JITTER_MS");
            }
            if (defaultJitterMs < 0 || defaultJitterMs > maxJitterMs) {
                throw new IllegalStateException("chaos.toxiproxy.default-jitter-ms must be between 0 and max-jitter-ms ("
                        + maxJitterMs + "), was " + defaultJitterMs + "; env CHAOS_TOXIPROXY_DEFAULT_JITTER_MS");
            }
            if (maxBandwidthKbps < 1 || maxBandwidthKbps > 1_000_000) {
                throw new IllegalStateException("chaos.toxiproxy.max-bandwidth-kbps must be between 1 and 1000000 (was "
                        + maxBandwidthKbps + "); env CHAOS_TOXIPROXY_MAX_BANDWIDTH_KBPS");
            }
            if (defaultBandwidthKbps < 1 || defaultBandwidthKbps > maxBandwidthKbps) {
                throw new IllegalStateException("chaos.toxiproxy.default-bandwidth-kbps must be between 1 and "
                        + "max-bandwidth-kbps (" + maxBandwidthKbps + "), was " + defaultBandwidthKbps
                        + "; env CHAOS_TOXIPROXY_DEFAULT_BANDWIDTH_KBPS");
            }
            if (maxTimeoutMs < 1 || maxTimeoutMs > 600_000) {
                throw new IllegalStateException("chaos.toxiproxy.max-timeout-ms must be between 1 and 600000 (was "
                        + maxTimeoutMs + "); env CHAOS_TOXIPROXY_MAX_TIMEOUT_MS");
            }
            if (defaultTimeoutMs < 1 || defaultTimeoutMs > maxTimeoutMs) {
                throw new IllegalStateException("chaos.toxiproxy.default-timeout-ms must be between 1 and max-timeout-ms ("
                        + maxTimeoutMs + "), was " + defaultTimeoutMs + "; env CHAOS_TOXIPROXY_DEFAULT_TIMEOUT_MS");
            }
            if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
                throw new IllegalStateException("chaos.toxiproxy.connect-timeout-seconds must be between 1 and 60 (was "
                        + connectTimeoutSeconds + "); env CHAOS_TOXIPROXY_CONNECT_TIMEOUT_SECONDS");
            }
            if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60) {
                throw new IllegalStateException("chaos.toxiproxy.request-timeout-seconds must be between 1 and 60 (was "
                        + requestTimeoutSeconds + "); env CHAOS_TOXIPROXY_REQUEST_TIMEOUT_SECONDS");
            }
            if (proxies == null) {
                throw new IllegalStateException("chaos.toxiproxy.proxies must not be null");
            }
            Set<String> seen = new HashSet<>();
            for (String proxy : proxies) {
                if (proxy == null || !PROXY_NAME.matcher(proxy).matches()) {
                    throw new IllegalStateException("chaos.toxiproxy.proxies contains an invalid proxy name '"
                            + proxy + "' (expected " + PROXY_NAME.pattern() + ")");
                }
                if (!seen.add(proxy)) {
                    throw new IllegalStateException("chaos.toxiproxy.proxies contains duplicate proxy '" + proxy + "'");
                }
            }
            if (enabled && proxies.isEmpty()) {
                throw new IllegalStateException("chaos.toxiproxy.proxies must list at least one proxy while "
                        + "chaos.toxiproxy.enabled=true (set CHAOS_TOXIPROXY_ENABLED=false to disable the layer)");
            }
            String configuredUrl = url == null ? "" : url.trim();
            if (enabled && configuredUrl.isEmpty()) {
                throw new IllegalStateException(
                        "chaos.toxiproxy.url is required while enabled; env CHAOS_TOXIPROXY_URL");
            }
            if (!configuredUrl.isEmpty()) {
                validateUrl(configuredUrl);
            }
        }

        private static void validateUrl(String configuredUrl) {
            URI uri;
            try {
                uri = URI.create(configuredUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("chaos.toxiproxy.url is not a valid URI: " + configuredUrl, e);
            }
            if (uri.getScheme() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalStateException("chaos.toxiproxy.url must be an absolute http(s) URL with a host: "
                        + configuredUrl);
            }
        }
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
                        + "-h127.0.0.1 -u\"$MYSQL_CLUSTER_ADMIN_USER\" -e \"$MYSQL_CLUSTER_QUERY\""));

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
