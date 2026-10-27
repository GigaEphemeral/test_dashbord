package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotBundleView;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotFileView;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the design §9.3 snapshot bundle with the JDK 8 tools:
 * {@code <root>/<UTC yyyyMMdd-HHmmss-SSS>_<instance>/} containing
 * {@code thread-dump.txt} ({@code jstack -l <pid>}), {@code heap-info.txt}
 * ({@code jcmd <pid> GC.heap_info}), {@code class-histogram.txt}
 * ({@code jmap -histo <pid>}), {@code metadata.json} and a copy of the analysis
 * template.
 *
 * <p>The bundle layout, retention rule and metadata keys mirror the JDK 21
 * {@code ThreadSnapshotService} so D7/D8/D6 can treat both runtimes uniformly;
 * only the producer differs. Key differences on this runtime:</p>
 * <ul>
 *   <li>JDK 8 has no {@code ProcessHandle}; the pid comes from the
 *       {@link RuntimeMXBean} name ({@code "<pid>@<host>"}).</li>
 *   <li>The dump and histogram come from {@code jstack}/{@code jmap}. The heap
 *       summary cannot come from {@code jmap -heap} because that option was
 *       removed in the 8u line (8u504 exits with the usage text), so it uses
 *       {@code jcmd GC.heap_info} — the exact diagnostic the JDK 21 bundle uses.</li>
 *   <li>No lifecycle demo, so the only trigger is {@code manual}.</li>
 * </ul>
 *
 * <h2>Failure semantics</h2>
 * {@link #capture()} <strong>never throws</strong>. A failed tool, a missing
 * template, an unwritable directory or an unparseable metadata file is logged at
 * WARN, counted in {@link SnapshotMetrics} and returned as
 * {@code SnapshotView{captured=false, error=...}}; the endpoint still answers 200.
 * A partial folder is removed, so a directory always means a complete bundle.
 *
 * <h2>Retention</h2>
 * Bounded by the externalized {@code max-count} and {@code max-age-seconds};
 * only directories whose name starts with the bundle timestamp and an underscore
 * are considered, everything else in the root is left alone. Captures are
 * serialized by {@code captureLock}: two simultaneous tool runs and a concurrent
 * retention pass would otherwise race on folder creation.
 */
public final class ThreadSnapshotService {

    static final String FILE_THREAD_DUMP = SnapshotCommands.FILE_THREAD_DUMP;
    static final String FILE_HEAP_INFO = SnapshotCommands.FILE_HEAP_INFO;
    static final String FILE_CLASS_HISTOGRAM = SnapshotCommands.FILE_CLASS_HISTOGRAM;
    static final String FILE_METADATA = "metadata.json";
    static final String FILE_ANALYSIS_TEMPLATE = "analysis-template.md";

    static final String TRIGGER_MANUAL = SnapshotMetrics.TRIGGER_MANUAL;
    static final String TOOLSET = "jstack+jmap+jcmd";

    /** Fixed-width UTC timestamp; lexicographic order is chronological order. */
    static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final Pattern FOLDER_NAME = Pattern.compile("^(\\d{8}-\\d{6}-\\d{3})_(.+)$");
    /** Keeps folder names safe on every filesystem and parseable by the retainer. */
    private static final Pattern UNSAFE_INSTANCE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    /** Longer tool failures (stack traces) are truncated in the API error field. */
    private static final int ERROR_TEXT_LIMIT = 300;

    private static final Logger log = LoggerFactory.getLogger(ThreadSnapshotService.class);

    private final SnapshotProperties config;
    private final String instanceId;
    private final long pid;
    private final Clock clock;
    private final JvmCommandExecutor executor;
    private final ObjectMapper objectMapper;
    private final SnapshotMetrics metrics;
    private final Path root;
    private final Object captureLock = new Object();

    public ThreadSnapshotService(SnapshotProperties config,
                                 String instanceId,
                                 long pid,
                                 Clock clock,
                                 JvmCommandExecutor executor,
                                 ObjectMapper objectMapper,
                                 SnapshotMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.instanceId = sanitizeInstance(instanceId);
        this.pid = pid;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.root = Paths.get(config.getRootDir());
    }

    /**
     * The pid of the running JVM on Java 8: {@code ProcessHandle} is Java 9+, so
     * the {@link RuntimeMXBean} name {@code "<pid>@<host>"} is the portable source.
     */
    public static long currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        String pidText = at > 0 ? name.substring(0, at) : name;
        try {
            return Long.parseLong(pidText.trim());
        } catch (NumberFormatException notAPid) {
            throw new IllegalStateException("Cannot determine JVM pid from RuntimeMXBean name: " + name,
                    notAPid);
        }
    }

    /**
     * Captures one bundle and returns its metadata. Never throws.
     */
    public SnapshotView capture() {
        if (!config.isEnabled()) {
            metrics.onSkippedDisabled();
            log.info("Snapshot capture disabled (app.lab.snapshot.enabled=false); "
                    + "manual snapshot skipped");
            return new SnapshotView(false, TRIGGER_MANUAL, "", Collections.<String>emptyList(), 0L,
                    clock.millis(), "snapshot capture disabled: app.lab.snapshot.enabled=false");
        }

        metrics.onAttempt();
        long startNanos = System.nanoTime();
        Path directory = null;
        List<String> written = new ArrayList<String>();
        try {
            synchronized (captureLock) {
                directory = createDirectory();
                List<SnapshotCommand> commands = SnapshotCommands.forCurrentJvm(config, pid);
                for (SnapshotCommand command : commands) {
                    String output = executor.execute(command.getArgv(),
                            Duration.ofMillis(config.getCommandTimeoutMs()));
                    writeText(directory.resolve(command.getFile()), output);
                    written.add(command.getFile());
                }
                writeMetadata(directory, written);
                written.add(FILE_METADATA);
                copyAnalysisTemplate(directory);
                written.add(FILE_ANALYSIS_TEMPLATE);
                metrics.onRetentionDeleted(prune(directory));
            }
            Duration elapsed = elapsed(startNanos);
            metrics.onSuccess();
            metrics.onDuration(elapsed);
            SnapshotView view = new SnapshotView(true, TRIGGER_MANUAL,
                    directory.toAbsolutePath().toString(),
                    Collections.unmodifiableList(new ArrayList<String>(written)),
                    elapsed.toMillis(), clock.millis(), null);
            log.info("Snapshot written: trigger={} toolset={} pid={} dir={}",
                    TRIGGER_MANUAL, TOOLSET, pid, directory.getFileName());
            return view;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Duration elapsed = elapsed(startNanos);
            metrics.onFailure();
            metrics.onDuration(elapsed);
            deleteQuietly(directory);
            String error = shortError(failure);
            log.warn("Snapshot capture failed ({} continues unaffected): {}", TRIGGER_MANUAL, error,
                    failure);
            return new SnapshotView(false, TRIGGER_MANUAL,
                    directory == null ? "" : directory.toAbsolutePath().toString(),
                    Collections.<String>emptyList(), elapsed.toMillis(), clock.millis(), error);
        }
    }

    /**
     * Lists the bundles currently on disk, newest first. Read-only and
     * best-effort: an unreadable root or metadata file degrades to an empty/partial
     * listing instead of an error, because this endpoint must stay console-safe.
     */
    public List<SnapshotBundleView> listBundles() {
        List<SnapshotBundleView> bundles = new ArrayList<SnapshotBundleView>();
        for (Path directory : snapshotDirectories()) {
            Instant timestamp = folderTimestamp(directory);
            if (timestamp == null) {
                continue;
            }
            List<SnapshotFileView> files = fileSizes(directory);
            long total = 0L;
            for (SnapshotFileView file : files) {
                total += file.getBytes();
            }
            bundles.add(new SnapshotBundleView(
                    directory.getFileName().toString(),
                    directory.toAbsolutePath().toString(),
                    timestamp.toString(),
                    readTrigger(directory.resolve(FILE_METADATA)),
                    total,
                    files));
        }
        Collections.sort(bundles, new Comparator<SnapshotBundleView>() {
            @Override
            public int compare(SnapshotBundleView left, SnapshotBundleView right) {
                return right.getName().compareTo(left.getName());
            }
        });
        return bundles;
    }

    // ------------------------------------------------------------------ bundle

    private Path createDirectory() throws IOException {
        Files.createDirectories(root);
        String base = FOLDER_TIME.format(clock.instant()) + "_" + instanceId;
        Path candidate = root.resolve(base);
        for (int suffix = 2; Files.exists(candidate); suffix++) {
            candidate = root.resolve(base + "-" + suffix);
        }
        return Files.createDirectory(candidate);
    }

    private void writeMetadata(Path directory, List<String> written) throws IOException {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("instance", instanceId);
        metadata.put("trigger", TRIGGER_MANUAL);
        metadata.put("toolset", TOOLSET);
        // On 8u the MXBean vmVersion is the internal "25.504-b01"; the public
        // runtime identity the docs/D7 compare is java.version (1.8.0_504).
        // Keep both so one field says "1.8.0_xxx" and the other stays raw.
        metadata.put("jvmVersion", System.getProperty("java.version"));
        metadata.put("vmVersion", runtime.getVmVersion());
        metadata.put("jvmVendor", runtime.getVmVendor());
        metadata.put("jvmName", runtime.getVmName());
        metadata.put("uptimeMs", runtime.getUptime());
        metadata.put("pid", pid);
        // jmap -heap is gone on 8u, so name the actual collectors in metadata:
        // the heap artefact shows geometry, this makes the collector explicit
        // (e.g. PS Scavenge / PS MarkSweep under -Xmx512m).
        metadata.put("gcCollectors", gcCollectors());
        metadata.put("capturedAt", Instant.ofEpochMilli(clock.millis()).toString());
        metadata.put("capturedAtEpochMs", clock.millis());
        metadata.put("jmapMode", config.getJmapMode());
        // The manifest describes the complete bundle, not just the tool outputs:
        // metadata.json and the analysis template are written right after this
        // call, and a reader must be able to enumerate all five files.
        List<String> bundleFiles = new ArrayList<String>(written);
        bundleFiles.add(FILE_METADATA);
        bundleFiles.add(FILE_ANALYSIS_TEMPLATE);
        metadata.put("files", bundleFiles);

        Map<String, String> commands = new LinkedHashMap<String, String>();
        for (SnapshotCommand command : SnapshotCommands.forCurrentJvm(config, pid)) {
            commands.put(command.getFile(), command.getCommandLine());
        }
        metadata.put("commands", commands);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve(FILE_METADATA).toFile(), metadata);
    }

    private void copyAnalysisTemplate(Path directory) throws IOException {
        Path template = Paths.get(config.getTemplatePath());
        if (!Files.isRegularFile(template)) {
            throw new IOException("analysis template missing: " + template.toAbsolutePath());
        }
        Files.copy(template, directory.resolve(FILE_ANALYSIS_TEMPLATE));
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String gcCollectors() {
        StringBuilder names = new StringBuilder();
        for (java.lang.management.GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(collector.getName());
        }
        return names.toString();
    }

    // --------------------------------------------------------------- retention

    private int prune(Path current) {
        Instant now = clock.instant();
        int deleted = 0;
        if (config.getMaxAgeSeconds() > 0) {
            for (Path directory : snapshotDirectories()) {
                if (directory.equals(current)) {
                    continue;
                }
                Instant timestamp = folderTimestamp(directory);
                if (timestamp != null
                        && Duration.between(timestamp, now).getSeconds() > config.getMaxAgeSeconds()
                        && deleteRecursively(directory)) {
                    deleted++;
                }
            }
        }
        List<Path> remaining = snapshotDirectories();
        Collections.sort(remaining, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return right.getFileName().toString().compareTo(left.getFileName().toString());
            }
        });
        int kept = 0;
        for (Path directory : remaining) {
            if (directory.equals(current)) {
                kept++;
            } else if (kept < config.getMaxCount()) {
                kept++;
            } else if (deleteRecursively(directory)) {
                deleted++;
            }
        }
        return deleted;
    }

    private List<Path> snapshotDirectories() {
        List<Path> directories = new ArrayList<Path>();
        if (!Files.isDirectory(root)) {
            return directories;
        }
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(path -> {
                if (Files.isDirectory(path)
                        && FOLDER_NAME.matcher(path.getFileName().toString()).matches()) {
                    directories.add(path);
                }
            });
        } catch (IOException unreadable) {
            log.warn("Snapshot listing could not read {}: {}", root, unreadable.toString());
        }
        return directories;
    }

    private static Instant folderTimestamp(Path directory) {
        Matcher matcher = FOLDER_NAME.matcher(directory.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(1), FOLDER_TIME)
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException unparseable) {
            return null;
        }
    }

    private String readTrigger(Path metadataFile) {
        if (!Files.isRegularFile(metadataFile)) {
            return "unknown";
        }
        try {
            JsonNode metadata = objectMapper.readTree(metadataFile.toFile());
            JsonNode trigger = metadata == null ? null : metadata.get("trigger");
            return trigger == null || trigger.isNull() ? "unknown" : trigger.asText();
        } catch (IOException unreadable) {
            log.warn("Snapshot metadata unreadable ({}): {}", metadataFile, unreadable.toString());
            return "unknown";
        }
    }

    private static List<SnapshotFileView> fileSizes(Path directory) {
        List<SnapshotFileView> files = new ArrayList<SnapshotFileView>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    try {
                        files.add(new SnapshotFileView(path.getFileName().toString(),
                                Files.size(path)));
                    } catch (IOException unreadable) {
                        files.add(new SnapshotFileView(path.getFileName().toString(), 0L));
                    }
                }
            });
        } catch (IOException unreadable) {
            log.warn("Snapshot bundle unreadable ({}): {}", directory, unreadable.toString());
        }
        Collections.sort(files, new Comparator<SnapshotFileView>() {
            @Override
            public int compare(SnapshotFileView left, SnapshotFileView right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return files;
    }

    private static boolean deleteRecursively(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            List<Path> paths = new ArrayList<Path>();
            entries.forEach(paths::add);
            Collections.sort(paths, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return right.toString().compareTo(left.toString());
                }
            });
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException failure) {
            log.warn("Snapshot retention could not delete {}: {}", directory, failure.toString());
            return false;
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory != null) {
            deleteRecursively(directory);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static String shortError(Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().replace('\n', ' ');
        String text = failure.getClass().getSimpleName() + ": " + message;
        return text.length() <= ERROR_TEXT_LIMIT ? text : text.substring(0, ERROR_TEXT_LIMIT) + "...";
    }

    private static String sanitizeInstance(String instanceId) {
        String raw = instanceId == null || instanceId.trim().isEmpty() ? "unknown" : instanceId.trim();
        return UNSAFE_INSTANCE_CHARS.matcher(raw).replaceAll("-");
    }
}
