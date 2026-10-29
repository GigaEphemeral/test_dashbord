package com.javalearn.lab.chaos.instance;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage2 B4-01 per-instance configuration limits. Every number/allow-list is
 * externalized in the controller {@code application.yml} with a
 * {@code ${ENV:default}} placeholder so compose (B4-02 forward) can tune it
 * without a rebuild. The defaults below only describe the documented default
 * so direct construction in tests is safe.
 *
 * <p>The memory budget is intentionally conservative: the lab stack runs three
 * app instances plus the observability stack on one host, so a single
 * instance defaults to a 768 MB container limit. The validator reports the
 * full arithmetic when a configuration exceeds it.</p>
 */
@ConfigurationProperties(prefix = "lab.instance")
public class InstanceConfigProperties {

    private static final String INSTANCE_NAME = "[a-z0-9][a-z0-9-]{0,62}";
    private static final String IMAGE_TAG = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
    private static final String GC_LOG_ROOT = "/[A-Za-z0-9._/-]*";

    /** Instances this controller owns; anything else is a 404. */
    private List<String> names = new ArrayList<>(List.of("app-1", "app-2", "app-3", "seckill-app-jdk8"));

    /** Container name prefix; {@code jl-app-1} for {@code app-1}. */
    private String containerPrefix = "jl-";

    /** Per-instance container memory limit used by the budget check. */
    private int memoryLimitMb = 768;

    /** Stack size per business-pool thread in the budget. */
    private int threadStackMb = 1;

    /** Native/other overhead reserved inside the container limit. */
    private int nativeReserveMb = 64;

    /** Metaspace budget when the request leaves it unset. */
    private int defaultMetaspaceMb = 96;

    /** Direct-memory budget when the request leaves it unset. */
    private int defaultDirectMemoryMb = 64;

    /** Upper bound of {@code extraJvmArgs} entries. */
    private int maxExtraArgs = 16;

    /** Upper bound of the concatenated {@code extraJvmArgs} length. */
    private int maxExtraArgsLength = 1024;

    /** Verified image tags accepted for {@code imageTag}. */
    private List<String> imageTags = new ArrayList<>(List.of("jdk21", "jdk8"));

    /** Allow-listed root for GC-log and heap-dump paths (POSIX container path). */
    private String gcLogRoot = "/app/lab-snapshots";
    /** B4-03 GC-log readback bounds. */
    private int gcLogDefaultLines = 200;
    private int gcLogMaxLines = 2000;
    private int gcLogMaxBytes = 262144;
    private int gcLogTailTimeoutSeconds = 5;
    /**
     * B5-04b: {@code jcmd 1 VM.flags} readback bounds. Only JDK 21 instances are
     * read; a JDK 8 instance stays READBACK_UNAVAILABLE by design. The output
     * is truncated to the byte cap before token parsing.
     */
    private int jvmFlagsTimeoutSeconds = 10;
    private int jvmFlagsMaxBytes = 8192;
    /**
     * B5-04b: bounded container-log scan for OOM / heap-dump events. Docker's
     * log tail is capped at 1000 lines by the gateway, so the default stays low.
     */
    private int eventsLogTail = 200;
    private int eventsMaxEvents = 50;
    private int eventsLineMaxChars = 400;
    /** B4-04: the JDK 8 shell instance and its Prometheus job (LIMITED). */
    private String jdk8Instance = "seckill-app-jdk8";
    private String jdk8PrometheusJob = "seckill-app-jdk8";

    // ------------------------------------------------------- B4-02 rebuild

    /** Stable period after an apply/rollback (success and failure), design §10.7. */
    private int stableSeconds = 60;

    /** Wall-clock budget of one rebuild including every health gate. */
    private int applyTimeoutSeconds = 240;

    /** Budget for the Docker health gate (container health or the app probe fallback). */
    private int healthTimeoutSeconds = 150;

    /** Nginx entry probe after the reload (through the upstream name, not an IP). */
    private String nginxProbeUrl = "http://nginx/api/hello";

    /** App probe template; {@code %s} is the instance service name (e.g. app-1). */
    private String appProbeUrlTemplate = "http://%s:8080/api/hello";

    /** Pool readback template; {@code %s} is the instance service name. */
    private String poolProbeUrlTemplate = "http://%s:8080/lab/pool";

    /** Per-probe HTTP timeout. */
    private int probeTimeoutSeconds = 5;

    /** How long the Prometheus {@code up} gate waits for a first 1 sample. */
    private int prometheusUpWaitSeconds = 30;

    /** Image repository; the ref is always {@code <repository>:<imageTag>}. */
    private String imageRepository = "java-learn-lab/seckill-app";

    /** Container whose {@code nginx -s reload} re-resolves the upstreams. */
    private String nginxContainer = "jl-nginx";

    private static final String IMAGE_REPOSITORY = "[A-Za-z0-9][A-Za-z0-9._/-]{0,200}";

    private static final String CONTAINER_NAME = "[A-Za-z0-9][A-Za-z0-9_.-]{0,62}";

    @PostConstruct
    public void validate() {
        if (names == null || names.isEmpty()) {
            throw new IllegalStateException("lab.instance.names must not be empty; env LAB_INSTANCE_NAMES");
        }
        for (String name : names) {
            if (name == null || !name.matches(INSTANCE_NAME)) {
                throw new IllegalStateException("lab.instance.names entry '" + name
                        + "' must match " + INSTANCE_NAME + "; env LAB_INSTANCE_NAMES");
            }
        }
        if (containerPrefix == null || containerPrefix.isBlank()) {
            throw new IllegalStateException(
                    "lab.instance.container-prefix must not be blank; env LAB_INSTANCE_CONTAINER_PREFIX");
        }
        range(memoryLimitMb, 128, 65536, "lab.instance.memory-limit-mb",
                "LAB_INSTANCE_MEMORY_LIMIT_MB");
        range(threadStackMb, 1, 16, "lab.instance.thread-stack-mb", "LAB_INSTANCE_THREAD_STACK_MB");
        range(nativeReserveMb, 0, 8192, "lab.instance.native-reserve-mb",
                "LAB_INSTANCE_NATIVE_RESERVE_MB");
        range(defaultMetaspaceMb, 16, 4096, "lab.instance.default-metaspace-mb",
                "LAB_INSTANCE_DEFAULT_METASPACE_MB");
        range(defaultDirectMemoryMb, 8, 8192, "lab.instance.default-direct-memory-mb",
                "LAB_INSTANCE_DEFAULT_DIRECT_MEMORY_MB");
        range(maxExtraArgs, 0, 64, "lab.instance.max-extra-args", "LAB_INSTANCE_MAX_EXTRA_ARGS");
        range(maxExtraArgsLength, 0, 8192, "lab.instance.max-extra-args-length",
                "LAB_INSTANCE_MAX_EXTRA_ARGS_LENGTH");
        if (imageTags == null || imageTags.isEmpty()) {
            throw new IllegalStateException(
                    "lab.instance.image-tags must not be empty; env LAB_INSTANCE_IMAGE_TAGS");
        }
        for (String tag : imageTags) {
            if (tag == null || !tag.matches(IMAGE_TAG)) {
                throw new IllegalStateException("lab.instance.image-tags entry '" + tag
                        + "' must match " + IMAGE_TAG + "; env LAB_INSTANCE_IMAGE_TAGS");
            }
        }
        if (gcLogDefaultLines < 1 || gcLogDefaultLines > gcLogMaxLines) {
            throw new IllegalStateException("lab.instance.gc-log-default-lines must be between 1 and "
                    + "gc-log-max-lines (env LAB_INSTANCE_GC_LOG_DEFAULT_LINES)");
        }
        if (gcLogMaxLines < 1 || gcLogMaxLines > 100000) {
            throw new IllegalStateException("lab.instance.gc-log-max-lines must be between 1 and 100000"
                    + " (env LAB_INSTANCE_GC_LOG_MAX_LINES)");
        }
        if (gcLogMaxBytes < 1024) {
            throw new IllegalStateException("lab.instance.gc-log-max-bytes must be at least 1024"
                    + " (env LAB_INSTANCE_GC_LOG_MAX_BYTES)");
        }
        if (gcLogTailTimeoutSeconds < 1 || gcLogTailTimeoutSeconds > 120) {
            throw new IllegalStateException("lab.instance.gc-log-tail-timeout-seconds must be"
                    + " between 1 and 120 (env LAB_INSTANCE_GC_LOG_TAIL_TIMEOUT_SECONDS)");
        }
        if (jvmFlagsTimeoutSeconds < 1 || jvmFlagsTimeoutSeconds > 120) {
            throw new IllegalStateException("lab.instance.jvm-flags-timeout-seconds must be"
                    + " between 1 and 120 (env LAB_INSTANCE_JVM_FLAGS_TIMEOUT_SECONDS)");
        }
        if (jvmFlagsMaxBytes < 1024 || jvmFlagsMaxBytes > 1048576) {
            throw new IllegalStateException("lab.instance.jvm-flags-max-bytes must be between"
                    + " 1024 and 1048576 (env LAB_INSTANCE_JVM_FLAGS_MAX_BYTES)");
        }
        if (eventsLogTail < 1 || eventsLogTail > 1000) {
            throw new IllegalStateException("lab.instance.events-log-tail must be between 1 and"
                    + " 1000 (env LAB_INSTANCE_EVENTS_LOG_TAIL)");
        }
        if (eventsMaxEvents < 1 || eventsMaxEvents > 200) {
            throw new IllegalStateException("lab.instance.events-max-events must be between 1 and"
                    + " 200 (env LAB_INSTANCE_EVENTS_MAX_EVENTS)");
        }
        if (eventsLineMaxChars < 80 || eventsLineMaxChars > 4000) {
            throw new IllegalStateException("lab.instance.events-line-max-chars must be between"
                    + " 80 and 4000 (env LAB_INSTANCE_EVENTS_LINE_MAX_CHARS)");
        }
        if (gcLogRoot == null || !gcLogRoot.matches(GC_LOG_ROOT)
                || gcLogRoot.contains("..") || gcLogRoot.endsWith("/") && gcLogRoot.length() > 1) {
            throw new IllegalStateException("lab.instance.gc-log-root must be a POSIX path without "
                    + "'..' or a trailing slash (was '" + gcLogRoot + "'); env LAB_INSTANCE_GC_LOG_ROOT");
        }
        range(stableSeconds, 0, 3600, "lab.instance.stable-seconds",
                "LAB_INSTANCE_STABLE_SECONDS");
        range(applyTimeoutSeconds, 5, 900, "lab.instance.apply-timeout-seconds",
                "LAB_INSTANCE_APPLY_TIMEOUT_SECONDS");
        range(healthTimeoutSeconds, 5, 900, "lab.instance.health-timeout-seconds",
                "LAB_INSTANCE_HEALTH_TIMEOUT_SECONDS");
        range(probeTimeoutSeconds, 1, 120, "lab.instance.probe-timeout-seconds",
                "LAB_INSTANCE_PROBE_TIMEOUT_SECONDS");
        range(prometheusUpWaitSeconds, 0, 600, "lab.instance.prometheus-up-wait-seconds",
                "LAB_INSTANCE_PROMETHEUS_UP_WAIT_SECONDS");
        requireUrl(nginxProbeUrl, "lab.instance.nginx-probe-url", "LAB_INSTANCE_NGINX_PROBE_URL");
        requireTemplate(appProbeUrlTemplate, "lab.instance.app-probe-url-template",
                "LAB_INSTANCE_APP_PROBE_URL_TEMPLATE");
        requireTemplate(poolProbeUrlTemplate, "lab.instance.pool-probe-url-template",
                "LAB_INSTANCE_POOL_PROBE_URL_TEMPLATE");
        if (imageRepository == null || !imageRepository.matches(IMAGE_REPOSITORY)
                || imageRepository.contains("..") || imageRepository.contains(":")) {
            throw new IllegalStateException("lab.instance.image-repository must be a bare repository "
                    + "name without a tag, '..' or a colon (was '" + imageRepository
                    + "'); env LAB_INSTANCE_IMAGE_REPOSITORY");
        }
        if (nginxContainer == null || !nginxContainer.matches(CONTAINER_NAME)) {
            throw new IllegalStateException("lab.instance.nginx-container must be a container name "
                    + "(was '" + nginxContainer + "'); env LAB_INSTANCE_NGINX_CONTAINER");
        }
    }

    /** The image ref is always built here - a request never supplies a repository. */
    public String imageRef(String imageTag) {
        return imageRepository + ":" + imageTag;
    }

    private static void requireUrl(String value, String property, String env) {
        if (value == null || !value.matches("https?://[^\\s/]+(/[^\\s]*)?")) {
            throw new IllegalStateException(property + " must be an http(s) URL without spaces (was '"
                    + value + "'); env " + env);
        }
    }

    private static void requireTemplate(String value, String property, String env) {
        if (value == null || value.indexOf("%s") < 0
                || !value.replace("%s", "app-1").matches("https?://[^\\s/]+(/[^\\s]*)?")) {
            throw new IllegalStateException(property + " must be an http(s) URL containing one %s "
                    + "placeholder (was '" + value + "'); env " + env);
        }
    }

    public boolean isKnownInstance(String instance) {
        return instance != null && names.contains(instance);
    }

    public String containerName(String instance) {
        return containerPrefix + instance;
    }

    private static void range(int value, int min, int max, String property, String env) {
        if (value < min || value > max) {
            throw new IllegalStateException(property + " must be between " + min + " and " + max
                    + " (was " + value + "); env " + env);
        }
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public String getContainerPrefix() {
        return containerPrefix;
    }

    public void setContainerPrefix(String containerPrefix) {
        this.containerPrefix = containerPrefix;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(int memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public int getThreadStackMb() {
        return threadStackMb;
    }

    public void setThreadStackMb(int threadStackMb) {
        this.threadStackMb = threadStackMb;
    }

    public int getNativeReserveMb() {
        return nativeReserveMb;
    }

    public void setNativeReserveMb(int nativeReserveMb) {
        this.nativeReserveMb = nativeReserveMb;
    }

    public int getDefaultMetaspaceMb() {
        return defaultMetaspaceMb;
    }

    public void setDefaultMetaspaceMb(int defaultMetaspaceMb) {
        this.defaultMetaspaceMb = defaultMetaspaceMb;
    }

    public int getDefaultDirectMemoryMb() {
        return defaultDirectMemoryMb;
    }

    public void setDefaultDirectMemoryMb(int defaultDirectMemoryMb) {
        this.defaultDirectMemoryMb = defaultDirectMemoryMb;
    }

    public int getMaxExtraArgs() {
        return maxExtraArgs;
    }

    public void setMaxExtraArgs(int maxExtraArgs) {
        this.maxExtraArgs = maxExtraArgs;
    }

    public int getMaxExtraArgsLength() {
        return maxExtraArgsLength;
    }

    public void setMaxExtraArgsLength(int maxExtraArgsLength) {
        this.maxExtraArgsLength = maxExtraArgsLength;
    }

    public List<String> getImageTags() {
        return imageTags;
    }

    public void setImageTags(List<String> imageTags) {
        this.imageTags = imageTags;
    }

    public String getJdk8Instance() {
        return jdk8Instance;
    }

    public void setJdk8Instance(String jdk8Instance) {
        this.jdk8Instance = jdk8Instance;
    }

    public String getJdk8PrometheusJob() {
        return jdk8PrometheusJob;
    }

    public void setJdk8PrometheusJob(String jdk8PrometheusJob) {
        this.jdk8PrometheusJob = jdk8PrometheusJob;
    }

    public int getGcLogDefaultLines() {
        return gcLogDefaultLines;
    }

    public void setGcLogDefaultLines(int gcLogDefaultLines) {
        this.gcLogDefaultLines = gcLogDefaultLines;
    }

    public int getGcLogMaxLines() {
        return gcLogMaxLines;
    }

    public void setGcLogMaxLines(int gcLogMaxLines) {
        this.gcLogMaxLines = gcLogMaxLines;
    }

    public int getGcLogMaxBytes() {
        return gcLogMaxBytes;
    }

    public void setGcLogMaxBytes(int gcLogMaxBytes) {
        this.gcLogMaxBytes = gcLogMaxBytes;
    }

    public int getGcLogTailTimeoutSeconds() {
        return gcLogTailTimeoutSeconds;
    }

    public void setGcLogTailTimeoutSeconds(int gcLogTailTimeoutSeconds) {
        this.gcLogTailTimeoutSeconds = gcLogTailTimeoutSeconds;
    }

    public int getJvmFlagsTimeoutSeconds() {
        return jvmFlagsTimeoutSeconds;
    }

    public void setJvmFlagsTimeoutSeconds(int jvmFlagsTimeoutSeconds) {
        this.jvmFlagsTimeoutSeconds = jvmFlagsTimeoutSeconds;
    }

    public int getJvmFlagsMaxBytes() {
        return jvmFlagsMaxBytes;
    }

    public void setJvmFlagsMaxBytes(int jvmFlagsMaxBytes) {
        this.jvmFlagsMaxBytes = jvmFlagsMaxBytes;
    }

    public int getEventsLogTail() {
        return eventsLogTail;
    }

    public void setEventsLogTail(int eventsLogTail) {
        this.eventsLogTail = eventsLogTail;
    }

    public int getEventsMaxEvents() {
        return eventsMaxEvents;
    }

    public void setEventsMaxEvents(int eventsMaxEvents) {
        this.eventsMaxEvents = eventsMaxEvents;
    }

    public int getEventsLineMaxChars() {
        return eventsLineMaxChars;
    }

    public void setEventsLineMaxChars(int eventsLineMaxChars) {
        this.eventsLineMaxChars = eventsLineMaxChars;
    }

    public String getGcLogRoot() {
        return gcLogRoot;
    }

    public void setGcLogRoot(String gcLogRoot) {
        this.gcLogRoot = gcLogRoot;
    }

    public int getStableSeconds() {
        return stableSeconds;
    }

    public void setStableSeconds(int stableSeconds) {
        this.stableSeconds = stableSeconds;
    }

    public int getApplyTimeoutSeconds() {
        return applyTimeoutSeconds;
    }

    public void setApplyTimeoutSeconds(int applyTimeoutSeconds) {
        this.applyTimeoutSeconds = applyTimeoutSeconds;
    }

    public int getHealthTimeoutSeconds() {
        return healthTimeoutSeconds;
    }

    public void setHealthTimeoutSeconds(int healthTimeoutSeconds) {
        this.healthTimeoutSeconds = healthTimeoutSeconds;
    }

    public String getNginxProbeUrl() {
        return nginxProbeUrl;
    }

    public void setNginxProbeUrl(String nginxProbeUrl) {
        this.nginxProbeUrl = nginxProbeUrl;
    }

    public String getAppProbeUrlTemplate() {
        return appProbeUrlTemplate;
    }

    public void setAppProbeUrlTemplate(String appProbeUrlTemplate) {
        this.appProbeUrlTemplate = appProbeUrlTemplate;
    }

    public String getPoolProbeUrlTemplate() {
        return poolProbeUrlTemplate;
    }

    public void setPoolProbeUrlTemplate(String poolProbeUrlTemplate) {
        this.poolProbeUrlTemplate = poolProbeUrlTemplate;
    }

    public int getProbeTimeoutSeconds() {
        return probeTimeoutSeconds;
    }

    public void setProbeTimeoutSeconds(int probeTimeoutSeconds) {
        this.probeTimeoutSeconds = probeTimeoutSeconds;
    }

    public int getPrometheusUpWaitSeconds() {
        return prometheusUpWaitSeconds;
    }

    public void setPrometheusUpWaitSeconds(int prometheusUpWaitSeconds) {
        this.prometheusUpWaitSeconds = prometheusUpWaitSeconds;
    }

    public String getImageRepository() {
        return imageRepository;
    }

    public void setImageRepository(String imageRepository) {
        this.imageRepository = imageRepository;
    }

    public String getNginxContainer() {
        return nginxContainer;
    }

    public void setNginxContainer(String nginxContainer) {
        this.nginxContainer = nginxContainer;
    }
}
