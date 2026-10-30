package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Stage2 B4-01 schema/whitelist validator and JVM argv generator.
 *
 * <p>Nothing here builds a shell string: every generated flag is one element of
 * the returned argv list, and {@code extraJvmArgs} tokens are copied one per
 * element. The validator rejects dangerous diagnostic/agent options, embedded
 * whitespace/shell characters, {@code ..} and paths; GC-log and heap-dump
 * paths must resolve inside {@code lab.instance.gc-log-root}.</p>
 *
 * <p>Memory budget (design §4.6/§10.7):
 * {@code xmx + metaspace + direct + maxPoolSize * threadStack + nativeReserve}
 * must fit in {@code lab.instance.memory-limit-mb}. Unset fields use the
 * configured estimates (defaults: metaspace
 * {@code lab.instance.default-metaspace-mb} 96, direct
 * {@code lab.instance.default-direct-memory-mb} 64, thread stack 1 MB,
 * native reserve {@code lab.instance.native-reserve-mb} 64) and the arithmetic
 * is included in the rejection message.</p>
 */
public class InstanceConfigValidator {

    private static final Set<String> GC_COLLECTORS = Set.of("G1", "Parallel", "Serial");
    private static final Set<String> REGION_SIZES = Set.of("1m", "2m", "4m", "8m", "16m", "32m");
    private static final Set<String> REJECTION_POLICIES = Set.of(
            "AbortPolicy", "CallerRunsPolicy", "DiscardPolicy", "DiscardOldestPolicy");

    private static final Set<String> BANNED_TOKENS = Set.of(
            "-agentlib", "-javaagent", "-xbootclasspath", "-xx:+usezgc",
            "-xx:+useshenandoahgc", "-xx:+useepsilongc", "-xx:onerror",
            "-xx:onoutofmemoryerror", "-xx:errorfile", "-xloggc:", "-xlog:",
            "-xx:heapdumppath", "-xx:+heapdumponoutofmemoryerror",
            "-xx:maxram", "-xx:initialram");

    /** JDK 21-only (or JDK 9+) options refused for a JDK 8 shell. */
    private static final Set<String> JDK8_FORBIDDEN_TOKENS = Set.of(
            "-xlog:", "-xx:activeprocessorcount", "-xx:+useg1concurrentstarttobackground");

    private static final String SAFE_PATH = "[A-Za-z0-9._/-]+";

    private final InstanceConfigProperties properties;

    public InstanceConfigValidator(InstanceConfigProperties properties) {
        this.properties = properties;
    }

    /** Result of a successful validation: normalized config plus generated argv. */
    public record ValidationResult(InstanceConfig config, List<String> jvmArgs,
                                   List<String> warnings, boolean limitedObservability,
                                   int requiredMemoryMb, int memoryLimitMb) {
    }

    public ValidationResult validate(String instance, InstanceConfig request) {
        if (!properties.isKnownInstance(instance)) {
            throw new com.javalearn.lab.chaos.error.InstanceNotFoundException(
                    "unknown instance '" + instance + "'; configured: " + properties.getNames());
        }
        if (request == null) {
            throw new InvalidInstanceConfigException("config body is required");
        }
        List<String> warnings = new ArrayList<>();

        int jdk = requireJdk(request.jdkVersion());
        String imageTag = optionalText(request.imageTag(), "imageTag");
        if (imageTag != null && !properties.getImageTags().contains(imageTag)) {
            throw invalid("imageTag '" + imageTag + "' is not allow-listed (allowed: "
                    + properties.getImageTags() + "; lab.instance.image-tags)");
        }
        boolean jdk8Slot = instance.equals(properties.getJdk8Instance());
        if (!jdk8Slot && (jdk == 8 || "jdk8".equals(imageTag))) {
            throw invalid("instance '" + instance + "' is not the JDK 8 slot ("
                    + properties.getJdk8Instance() + "); only that slot may use jdkVersion 8 "
                    + "or the jdk8 image");
        }
        if (jdk8Slot && jdk != 8) {
            throw invalid("instance '" + instance + "' is the configured JDK 8 slot; "
                    + "jdkVersion must be 8 (was " + jdk + ")");
        }
        if (jdk8Slot && imageTag != null && !"jdk8".equals(imageTag)) {
            throw invalid("instance '" + instance + "' is the configured JDK 8 slot; "
                    + "imageTag must be jdk8 (was " + imageTag + ")");
        }
        if (imageTag == null) {
            warnings.add("imageTag is not set: the running image is not pinned by this configuration");
        }

        Integer xms = range(request.xmsMb(), 32, 65536, "xmsMb");
        Integer xmx = range(request.xmxMb(), 32, 65536, "xmxMb");
        if (xms != null && xmx != null && xmx < xms) {
            throw invalid("xmxMb (" + xmx + ") must be >= xmsMb (" + xms + ")");
        }
        Integer metaspace = range(request.metaspaceMb(), 16, 4096, "metaspaceMb");
        Integer direct = range(request.directMemoryMb(), 8, 8192, "directMemoryMb");

        // ------------------------------------------------------------- GC
        String collector = normalizeEnum(request.gcCollector(), GC_COLLECTORS, "gcCollector");
        Integer pause = range(request.maxGcPauseMillis(), 10, 5000, "maxGcPauseMillis");
        Integer g1New = range(request.g1NewSizePercent(), 1, 99, "g1NewSizePercent");
        Integer g1MaxNew = range(request.g1MaxNewSizePercent(), 1, 99, "g1MaxNewSizePercent");
        String region = normalizeRegion(request.g1HeapRegionSize());
        Integer ihop = range(request.initiatingHeapOccupancyPercent(), 0, 100,
                "initiatingHeapOccupancyPercent");
        Integer parallelThreads = range(request.parallelGcThreads(), 1, 128, "parallelGcThreads");
        Integer newRatio = range(request.newRatio(), 1, 32, "newRatio");
        Integer survivorRatio = range(request.survivorRatio(), 1, 32, "survivorRatio");

        requireG1(collector, "maxGcPauseMillis", pause);
        requireG1(collector, "g1NewSizePercent", g1New);
        requireG1(collector, "g1MaxNewSizePercent", g1MaxNew);
        requireG1(collector, "g1HeapRegionSize", region);
        requireG1(collector, "initiatingHeapOccupancyPercent", ihop);
        if (!"Parallel".equals(collector) && parallelThreads != null) {
            throw invalid("parallelGcThreads is only valid when gcCollector=Parallel (was "
                    + collector + ")");
        }
        if (g1New != null && g1MaxNew != null && g1New > g1MaxNew) {
            throw invalid("g1NewSizePercent (" + g1New + ") must be <= g1MaxNewSizePercent ("
                    + g1MaxNew + ")");
        }
        if ("G1".equals(collector) && xmx != null && xmx < 64) {
            warnings.add("G1 with a heap below 64 MB is usually counter-productive");
        }

        // --------------------------------------------------- business pool
        Integer core = range(request.corePoolSize(), 1, 4096, "corePoolSize");
        Integer maxPool = range(request.maxPoolSize(), 1, 4096, "maxPoolSize");
        if (core != null && maxPool != null && maxPool < core) {
            throw invalid("maxPoolSize (" + maxPool + ") must be >= corePoolSize (" + core + ")");
        }
        Integer queueCapacity = range(request.queueCapacity(), 1, 1_000_000, "queueCapacity");
        Integer keepAlive = range(request.keepAliveSeconds(), 1, 86_400, "keepAliveSeconds");
        String rejection = normalizeEnum(request.rejectionPolicy(), REJECTION_POLICIES,
                "rejectionPolicy");

        // --------------------------------------------------------- Caffeine
        Boolean caffeineEnabled = request.caffeineEnabled();
        Long maximumSize = positive(request.maximumSize(), 1, 10_000_000, "maximumSize");
        Long expireWrite = range(request.expireAfterWriteSeconds(), 0, 31_536_000,
                "expireAfterWriteSeconds");
        Long expireAccess = range(request.expireAfterAccessSeconds(), 0, 31_536_000,
                "expireAfterAccessSeconds");
        Long refreshWrite = range(request.refreshAfterWriteSeconds(), 0, 31_536_000,
                "refreshAfterWriteSeconds");
        Boolean recordStats = request.recordStats();
        if (Boolean.FALSE.equals(caffeineEnabled)
                && (maximumSize != null || expireWrite != null || expireAccess != null
                || refreshWrite != null || recordStats != null)) {
            warnings.add("Caffeine is disabled; its size/expiry/stats fields are stored but unused");
        }

        // ------------------------------------------------- GC log / dump
        Boolean gcLogEnabled = request.gcLogEnabled();
        Integer logFileSize = range(request.gcLogFileSizeMb(), 1, 4096, "gcLogFileSizeMb");
        Integer logFileCount = range(request.gcLogFileCount(), 1, 1000, "gcLogFileCount");
        String gcLogPath = null;
        if (request.gcLogPath() != null) {
            gcLogPath = normalizePath(request.gcLogPath(), "gcLogPath");
        }
        if (Boolean.TRUE.equals(gcLogEnabled)) {
            if (gcLogPath == null) {
                gcLogPath = defaultPath("gc-" + instance + ".log");
            }
        } else if (gcLogPath != null) {
            warnings.add("gcLogEnabled is not true; gcLogPath is stored but no GC-log flag is generated");
        }
        Boolean heapDump = request.heapDumpOnOutOfMemory();
        String heapDumpPath = null;
        if (request.heapDumpPath() != null) {
            heapDumpPath = normalizePath(request.heapDumpPath(), "heapDumpPath");
        }
        if (Boolean.TRUE.equals(heapDump)) {
            if (heapDumpPath == null) {
                heapDumpPath = defaultPath("heap-" + instance + ".hprof");
            }
        } else if (heapDumpPath != null) {
            warnings.add("heapDumpOnOutOfMemory is not true; heapDumpPath is stored but no dump flag is generated");
        }

        // ----------------------------------------------------------- Tomcat
        Integer tomcatMax = range(request.tomcatMaxThreads(), 1, 10_000, "tomcatMaxThreads");
        Integer acceptCount = range(request.acceptCount(), 0, 10_000, "acceptCount");
        Integer minSpare = range(request.minSpareThreads(), 0, 4096, "minSpareThreads");
        if (tomcatMax != null && minSpare != null && minSpare > tomcatMax) {
            throw invalid("minSpareThreads (" + minSpare + ") must be <= tomcatMaxThreads ("
                    + tomcatMax + ")");
        }
        if (tomcatMax != null || acceptCount != null || minSpare != null) {
            warnings.add("Tomcat fields are display-only in B4-01; B4-02/04 decide how they are applied");
        }

        // ------------------------------------------------------ extra args
        List<String> extra = normalizeExtraArgs(request.extraJvmArgs(), jdk);

        // ---------------------------------------------------------- budget
        int memoryLimit = properties.getMemoryLimitMb();
        int effectiveXmx = xmx != null ? xmx : Math.max(1, memoryLimit * 25 / 100);
        if (xmx == null) {
            warnings.add("xmxMb is not set; the budget uses the JVM default 25% of "
                    + memoryLimit + " MB (" + effectiveXmx + " MB)");
        }
        int effectiveMetaspace = metaspace != null ? metaspace : properties.getDefaultMetaspaceMb();
        int effectiveDirect = direct != null ? direct : properties.getDefaultDirectMemoryMb();
        int effectivePool = maxPool != null ? maxPool : 0;
        int threadStacks = effectivePool * properties.getThreadStackMb();
        if (maxPool == null) {
            warnings.add("maxPoolSize is not set; business-pool thread stacks are not budgeted");
        }
        int required = effectiveXmx + effectiveMetaspace + effectiveDirect + threadStacks
                + properties.getNativeReserveMb();
        if (required > memoryLimit) {
            throw invalid("memory budget exceeded: xmx=" + effectiveXmx
                    + (xmx == null ? " (default 25% of limit)" : "")
                    + " + metaspace=" + effectiveMetaspace
                    + (metaspace == null ? " (default)" : "")
                    + " + direct=" + effectiveDirect
                    + (direct == null ? " (default)" : "")
                    + " + threads=" + effectivePool + "x" + properties.getThreadStackMb()
                    + "=" + threadStacks
                    + (maxPool == null ? " (maxPoolSize not set)" : "")
                    + " + native=" + properties.getNativeReserveMb()
                    + " = " + required + " MB > memLimit " + memoryLimit
                    + " MB (lab.instance.memory-limit-mb; env LAB_INSTANCE_MEMORY_LIMIT_MB)");
        }

        InstanceConfig normalized = new InstanceConfig(jdk, imageTag, xms, xmx, metaspace, direct,
                extra, collector, pause, g1New, g1MaxNew, region, ihop, parallelThreads,
                newRatio, survivorRatio, core, maxPool, queueCapacity, keepAlive, rejection,
                caffeineEnabled, maximumSize, expireWrite, expireAccess, refreshWrite, recordStats,
                gcLogEnabled, gcLogPath, logFileSize, logFileCount, heapDump, heapDumpPath,
                tomcatMax, acceptCount, minSpare);

        boolean limited = jdk == 8;
        if (limited) {
            warnings.add("JDK 8 shell is LIMITED-OBSERVABILITY: app-level hot readback is "
                    + "READBACK_UNAVAILABLE; use GC logs/JMX as the fallback");
        }

        List<String> args = new ArrayList<>();
        if (xms != null) {
            args.add("-Xms" + xms + "m");
        }
        if (xmx != null) {
            args.add("-Xmx" + xmx + "m");
        }
        if (metaspace != null) {
            args.add("-XX:MaxMetaspaceSize=" + metaspace + "m");
        }
        if (direct != null) {
            args.add("-XX:MaxDirectMemorySize=" + direct + "m");
        }
        if ("G1".equals(collector)) {
            args.add("-XX:+UseG1GC");
        } else if ("Parallel".equals(collector)) {
            args.add("-XX:+UseParallelGC");
        } else if ("Serial".equals(collector)) {
            args.add("-XX:+UseSerialGC");
        }
        // G1NewSizePercent/G1MaxNewSizePercent and
        // InitiatingHeapOccupancyPercent are experimental in JDK 8/21; the
        // unlock flag must precede them or the JVM refuses to start.
        if (g1New != null || g1MaxNew != null || ihop != null) {
            args.add("-XX:+UnlockExperimentalVMOptions");
        }
        if (pause != null) {
            args.add("-XX:MaxGCPauseMillis=" + pause);
        }
        if (g1New != null) {
            args.add("-XX:G1NewSizePercent=" + g1New);
        }
        if (g1MaxNew != null) {
            args.add("-XX:G1MaxNewSizePercent=" + g1MaxNew);
        }
        if (region != null) {
            args.add("-XX:G1HeapRegionSize=" + region);
        }
        if (ihop != null) {
            args.add("-XX:InitiatingHeapOccupancyPercent=" + ihop);
        }
        if (parallelThreads != null) {
            args.add("-XX:ParallelGCThreads=" + parallelThreads);
        }
        if (newRatio != null) {
            args.add("-XX:NewRatio=" + newRatio);
        }
        if (survivorRatio != null) {
            args.add("-XX:SurvivorRatio=" + survivorRatio);
        }
        if (Boolean.TRUE.equals(gcLogEnabled) && gcLogPath != null) {
            if (jdk == 8) {
                args.add("-Xloggc:" + gcLogPath);
                if (logFileCount != null || logFileSize != null) {
                    args.add("-XX:+UseGCLogFileRotation");
                    if (logFileCount != null) {
                        args.add("-XX:NumberOfGCLogFiles=" + logFileCount);
                    }
                    if (logFileSize != null) {
                        args.add("-XX:GCLogFileSize=" + logFileSize + "M");
                    }
                }
            } else {
                String options = "time,level,tags";
                StringBuilder rotation = new StringBuilder();
                if (logFileCount != null) {
                    rotation.append("filecount=").append(logFileCount);
                }
                if (logFileSize != null) {
                    if (!rotation.isEmpty()) {
                        rotation.append(',');
                    }
                    rotation.append("filesize=").append(logFileSize).append('m');
                }
                args.add("-Xlog:gc*:file=" + gcLogPath + ":" + options
                        + (rotation.isEmpty() ? "" : ":" + rotation));
            }
        }
        if (Boolean.TRUE.equals(heapDump)) {
            args.add("-XX:+HeapDumpOnOutOfMemoryError");
            args.add("-XX:HeapDumpPath=" + heapDumpPath);
        }
        args.addAll(extra);

        return new ValidationResult(normalized, List.copyOf(args), List.copyOf(warnings),
                limited, required, memoryLimit);
    }

    /** True when any startup (JVM/GC/log) field differs from the stored config. */
    public static boolean startupFieldsChanged(InstanceConfig requested, InstanceConfig stored) {
        if (stored == null) {
            return true;
        }
        return !Objects.equals(requested.jdkVersion(), stored.jdkVersion())
                || !Objects.equals(requested.imageTag(), stored.imageTag())
                || !Objects.equals(requested.xmsMb(), stored.xmsMb())
                || !Objects.equals(requested.xmxMb(), stored.xmxMb())
                || !Objects.equals(requested.metaspaceMb(), stored.metaspaceMb())
                || !Objects.equals(requested.directMemoryMb(), stored.directMemoryMb())
                || !Objects.equals(requested.extraJvmArgs(), stored.extraJvmArgs())
                || !Objects.equals(requested.gcCollector(), stored.gcCollector())
                || !Objects.equals(requested.maxGcPauseMillis(), stored.maxGcPauseMillis())
                || !Objects.equals(requested.g1NewSizePercent(), stored.g1NewSizePercent())
                || !Objects.equals(requested.g1MaxNewSizePercent(), stored.g1MaxNewSizePercent())
                || !Objects.equals(requested.g1HeapRegionSize(), stored.g1HeapRegionSize())
                || !Objects.equals(requested.initiatingHeapOccupancyPercent(),
                        stored.initiatingHeapOccupancyPercent())
                || !Objects.equals(requested.parallelGcThreads(), stored.parallelGcThreads())
                || !Objects.equals(requested.newRatio(), stored.newRatio())
                || !Objects.equals(requested.survivorRatio(), stored.survivorRatio())
                || !Objects.equals(requested.gcLogEnabled(), stored.gcLogEnabled())
                || !Objects.equals(requested.gcLogPath(), stored.gcLogPath())
                || !Objects.equals(requested.gcLogFileSizeMb(), stored.gcLogFileSizeMb())
                || !Objects.equals(requested.gcLogFileCount(), stored.gcLogFileCount())
                || !Objects.equals(requested.heapDumpOnOutOfMemory(),
                        stored.heapDumpOnOutOfMemory())
                || !Objects.equals(requested.heapDumpPath(), stored.heapDumpPath());
    }

    private int requireJdk(Integer jdkVersion) {
        if (jdkVersion == null) {
            throw invalid("jdkVersion is required (8 or 21)");
        }
        if (jdkVersion != 8 && jdkVersion != 21) {
            throw invalid("jdkVersion must be 8 or 21 (was " + jdkVersion + ")");
        }
        return jdkVersion;
    }

    private static void requireG1(String collector, String field, Object value) {
        if (value != null && !"G1".equals(collector)) {
            throw invalid(field + " is only valid when gcCollector=G1 (was " + collector + ")");
        }
    }

    private static String normalizeEnum(String raw, Set<String> allowed, String field) {
        String value = optionalText(raw, field);
        if (value == null) {
            return null;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw invalid(field + " must be one of " + allowed + " (was '" + raw + "')");
    }

    private static String normalizeRegion(String raw) {
        String value = optionalText(raw, "g1HeapRegionSize");
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!REGION_SIZES.contains(normalized)) {
            throw invalid("g1HeapRegionSize must be one of " + REGION_SIZES
                    + " (was '" + raw + "')");
        }
        return normalized;
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 255) {
            throw invalid(field + " is too long (" + trimmed.length() + " > 255)");
        }
        return trimmed;
    }

    private static Integer range(Integer value, int min, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw invalid(field + " must be between " + min + " and " + max + " (was " + value + ")");
        }
        return value;
    }

    private static Long range(Long value, long min, long max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw invalid(field + " must be between " + min + " and " + max + " (was " + value + ")");
        }
        return value;
    }

    private static Long positive(Long value, long min, long max, String field) {
        return range(value, min, max, field);
    }

    private String normalizePath(String raw, String field) {
        String value = raw.trim();
        if (value.isEmpty()) {
            throw invalid(field + " must not be blank");
        }
        if (!value.matches(SAFE_PATH) || value.contains("..") || value.endsWith("/")) {
            throw invalid(field + " must match " + SAFE_PATH + ", must not contain '..' and must "
                    + "name a file (was '" + raw + "')");
        }
        String root = properties.getGcLogRoot();
        String resolved;
        if ("/".equals(root)) {
            resolved = value.startsWith("/") ? value : "/" + value;
            if ("/".equals(resolved)) {
                throw invalid(field + " must name a file, not the allow-listed root " + root);
            }
        } else {
            resolved = value.startsWith("/") ? value : root + "/" + value;
            if (resolved.equals(root) || !resolved.startsWith(root + "/")) {
                throw invalid(field + " '" + raw + "' escapes the allow-listed root " + root
                        + " (lab.instance.gc-log-root; env LAB_INSTANCE_GC_LOG_ROOT)");
            }
        }
        return resolved;
    }

    private String defaultPath(String fileName) {
        String root = properties.getGcLogRoot();
        return "/".equals(root) ? "/" + fileName : root + "/" + fileName;
    }

    private List<String> normalizeExtraArgs(List<String> raw, int jdk) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > properties.getMaxExtraArgs()) {
            throw invalid("extraJvmArgs has " + raw.size() + " entries; the cap is "
                    + properties.getMaxExtraArgs() + " (lab.instance.max-extra-args)");
        }
        List<String> result = new ArrayList<>(raw.size());
        int total = 0;
        for (String entry : raw) {
            if (entry == null) {
                throw invalid("extraJvmArgs must not contain null entries");
            }
            String token = entry.trim();
            if (token.isEmpty()) {
                throw invalid("extraJvmArgs must not contain blank entries");
            }
            if (token.length() > 256) {
                throw invalid("extraJvmArgs entry is too long: " + token.length() + " > 256");
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (jdk == 8) {
                for (String banned : JDK8_FORBIDDEN_TOKENS) {
                    if (lower.contains(banned)) {
                        throw invalid("extraJvmArgs entry '" + token
                                + "' is JDK 21-only and cannot run on the JDK 8 shell");
                    }
                }
            }
            for (String banned : BANNED_TOKENS) {
                if (lower.contains(banned)) {
                    throw invalid("extraJvmArgs entry '" + token
                            + "' is not allowed (contains " + banned + ")");
                }
            }
            if (!(token.startsWith("-X") || token.startsWith("-XX:") || token.startsWith("-D"))) {
                throw invalid("extraJvmArgs entry '" + token
                        + "' must start with -X, -XX: or -D");
            }
            if (!token.matches("[A-Za-z0-9_.:,=+\\-]+")
                    || token.contains("..") || token.contains("/") || token.contains("\\")) {
                throw invalid("extraJvmArgs entry '" + token
                        + "' contains whitespace, a shell character or a path");
            }
            total += token.length();
            result.add(token);
        }
        if (total > properties.getMaxExtraArgsLength()) {
            throw invalid("extraJvmArgs total length " + total + " exceeds the cap "
                    + properties.getMaxExtraArgsLength() + " (lab.instance.max-extra-args-length)");
        }
        return List.copyOf(result);
    }

    private static InvalidInstanceConfigException invalid(String message) {
        return new InvalidInstanceConfigException(message);
    }
}
