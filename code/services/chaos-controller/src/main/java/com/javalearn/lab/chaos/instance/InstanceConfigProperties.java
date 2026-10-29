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
    private List<String> names = new ArrayList<>(List.of("app-1", "app-2", "app-3"));

    /** Container name prefix; {@code jl-app-1} for {@code app-1}. */
    private String containerPrefix = "jl-";

    /** Per-instance container memory limit used by the budget check. */
    private int memoryLimitMb = 768;

    /** Stack size per business-pool thread in the budget. */
    private int threadStackMb = 1;

    /** Native/other overhead reserved inside the container limit. */
    private int nativeReserveMb = 128;

    /** Metaspace budget when the request leaves it unset. */
    private int defaultMetaspaceMb = 128;

    /** Direct-memory budget when the request leaves it unset. */
    private int defaultDirectMemoryMb = 64;

    /** Upper bound of {@code extraJvmArgs} entries. */
    private int maxExtraArgs = 16;

    /** Upper bound of the concatenated {@code extraJvmArgs} length. */
    private int maxExtraArgsLength = 1024;

    /** Verified image tags accepted for {@code imageTag}. */
    private List<String> imageTags = new ArrayList<>(List.of("jdk21", "jdk8"));

    /** Allow-listed root for GC-log and heap-dump paths (POSIX container path). */
    private String gcLogRoot = "/app/logs";

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
        if (gcLogRoot == null || !gcLogRoot.matches(GC_LOG_ROOT)
                || gcLogRoot.contains("..") || gcLogRoot.endsWith("/") && gcLogRoot.length() > 1) {
            throw new IllegalStateException("lab.instance.gc-log-root must be a POSIX path without "
                    + "'..' or a trailing slash (was '" + gcLogRoot + "'); env LAB_INSTANCE_GC_LOG_ROOT");
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

    public String getGcLogRoot() {
        return gcLogRoot;
    }

    public void setGcLogRoot(String gcLogRoot) {
        this.gcLogRoot = gcLogRoot;
    }
}
