package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.ThreadSnapshotView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the design §9.3 snapshot bundle to disk:
 * {@code doc/process/snapshots/<timestamp>_<instance>/} containing
 * {@code thread-dump.txt} ({@code jcmd Thread.print -l}), {@code heap-info.txt}
 * ({@code jcmd GC.heap_info}), {@code class-histogram.txt}
 * ({@code jcmd GC.class_histogram}), a {@code metadata.json} and a copy of the
 * analysis template.
 *
 * <h2>Failure semantics</h2>
 * {@link #capture(LifecycleSnapshot)} <strong>never throws</strong>. A failed
 * {@code jcmd}, an unwritable directory or a broken annotator is logged at WARN,
 * counted in {@link ThreadSnapshotMetrics} and returned as
 * {@code ThreadSnapshotView{captured=false, error=...}}; {@code freeze} keeps its
 * "pause advancement" contract and the on-demand endpoint still answers 200.
 * A partial folder is removed, so a directory always means a complete bundle.
 *
 * <h2>Retention</h2>
 * Bounded by the externalized {@code max-count} and {@code max-age-seconds};
 * only directories whose name starts with the bundle timestamp and an underscore
 * are ever considered, everything else in the root is left alone.
 *
 * <p>Captures are serialized by {@code captureLock}: two simultaneous
 * {@code jcmd} runs and a concurrent retention pass would otherwise race on
 * folder creation.</p>
 */
public final class ThreadSnapshotService {

    static final String TEMPLATE_RESOURCE = "lab/thread/snapshot-analysis-template.md";

    static final String FILE_THREAD_DUMP = "thread-dump.txt";
    static final String FILE_HEAP_INFO = "heap-info.txt";
    static final String FILE_CLASS_HISTOGRAM = "class-histogram.txt";
    static final String FILE_METADATA = "metadata.json";
    static final String FILE_ANALYSIS_TEMPLATE = "analysis-template.md";

    /** Fixed-width UTC timestamp; lexicographic order is chronological order. */
    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final Pattern FOLDER_NAME = Pattern.compile("^(\\d{8}-\\d{6}-\\d{3})_(.+)$");
    /** Keeps folder names safe on every filesystem and parseable by the retainer. */
    private static final Pattern UNSAFE_INSTANCE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    /** Longer jcmd failures (stack traces) are truncated in the API error field. */
    private static final int ERROR_TEXT_LIMIT = 300;

    private static final List<Command> COMMANDS = List.of(
            new Command(FILE_THREAD_DUMP, List.of("Thread.print", "-l")),
            new Command(FILE_HEAP_INFO, List.of("GC.heap_info")),
            new Command(FILE_CLASS_HISTOGRAM, List.of("GC.class_histogram")));

    private static final Logger log = LoggerFactory.getLogger(ThreadSnapshotService.class);

    private final LabThreadProperties.Snapshot config;
    private final String instanceId;
    private final Clock clock;
    private final JvmCommandExecutor executor;
    private final ObjectMapper objectMapper;
    private final ThreadSnapshotMetrics metrics;
    private final SnapshotAnnotator annotator;
    private final Path root;
    private final Object captureLock = new Object();

    public ThreadSnapshotService(LabThreadProperties properties,
                                 String instanceId,
                                 Clock clock,
                                 JvmCommandExecutor executor,
                                 ObjectMapper objectMapper,
                                 ThreadSnapshotMetrics metrics,
                                 SnapshotAnnotator annotator) {
        Objects.requireNonNull(properties, "properties");
        this.config = properties.getSnapshot();
        this.instanceId = sanitizeInstance(instanceId);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.annotator = Objects.requireNonNull(annotator, "annotator");
        this.root = Path.of(config.getRootDir());
    }

    /**
     * Captures one bundle and returns its metadata. Never throws.
     *
     * @param snapshot observation handed over by {@code freeze} or built on demand
     */
    public ThreadSnapshotView capture(LifecycleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String trigger = ThreadSnapshotMetrics.TRIGGER_FREEZE.equals(snapshot.reason())
                ? ThreadSnapshotMetrics.TRIGGER_FREEZE
                : ThreadSnapshotMetrics.TRIGGER_MANUAL;
        if (!config.isEnabled()) {
            metrics.onSkippedDisabled();
            log.info("Snapshot capture disabled (app.lab.thread.snapshot.enabled=false); "
                    + "{} snapshot for thread {} skipped", trigger, snapshot.threadName());
            return new ThreadSnapshotView(false, trigger, "", List.of(), 0L,
                    snapshot.capturedAtEpochMs(),
                    "snapshot capture disabled: app.lab.thread.snapshot.enabled=false");
        }

        metrics.onAttempt(trigger);
        long startNanos = System.nanoTime();
        Path directory = null;
        List<String> written = new ArrayList<>();
        try {
            synchronized (captureLock) {
                directory = createDirectory();
                for (Command command : COMMANDS) {
                    String output = executor.execute(command.args(),
                            Duration.ofMillis(config.getCommandTimeoutMs()));
                    writeText(directory.resolve(command.file()), output);
                    written.add(command.file());
                }
                writeMetadata(directory, snapshot, trigger, written);
                written.add(FILE_METADATA);
                copyAnalysisTemplate(directory);
                written.add(FILE_ANALYSIS_TEMPLATE);
                metrics.onRetentionDeleted(prune(directory));
            }
            Duration elapsed = elapsed(startNanos);
            metrics.onSuccess(trigger);
            metrics.onDuration(trigger, elapsed);
            ThreadSnapshotView view = new ThreadSnapshotView(true, trigger,
                    directory.toAbsolutePath().toString(), List.copyOf(written),
                    elapsed.toMillis(), snapshot.capturedAtEpochMs(), null);
            log.info("Snapshot written: trigger={} state={}({}) step={} dir={}",
                    trigger, snapshot.state(), snapshot.stateCode(), snapshot.step(),
                    directory.getFileName());
            annotate(view, snapshot);
            return view;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Duration elapsed = elapsed(startNanos);
            metrics.onFailure(trigger);
            metrics.onDuration(trigger, elapsed);
            deleteQuietly(directory);
            String error = shortError(failure);
            log.warn("Snapshot capture failed ({} continues unaffected): {}", trigger, error, failure);
            return new ThreadSnapshotView(false, trigger,
                    directory == null ? "" : directory.toAbsolutePath().toString(),
                    List.of(), elapsed.toMillis(), snapshot.capturedAtEpochMs(), error);
        }
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

    private void writeMetadata(Path directory, LifecycleSnapshot snapshot, String trigger,
                               List<String> written) throws IOException {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("instance", instanceId);
        metadata.put("trigger", trigger);
        metadata.put("reason", snapshot.reason());
        metadata.put("state", snapshot.state());
        metadata.put("stateCode", snapshot.stateCode());
        metadata.put("step", snapshot.step());
        metadata.put("threadId", snapshot.threadId());
        metadata.put("threadName", snapshot.threadName());
        metadata.put("capturedAt", Instant.ofEpochMilli(snapshot.capturedAtEpochMs()).toString());
        metadata.put("capturedAtEpochMs", snapshot.capturedAtEpochMs());
        metadata.put("jvmVersion", runtime.getVmVersion());
        metadata.put("jvmVendor", runtime.getVmVendor());
        metadata.put("uptimeMs", runtime.getUptime());
        metadata.put("pid", ProcessHandle.current().pid());
        metadata.put("files", List.copyOf(written));
        Map<String, String> commands = new LinkedHashMap<>();
        for (Command command : COMMANDS) {
            commands.put(command.file(), "jcmd " + ProcessHandle.current().pid() + " "
                    + String.join(" ", command.args()));
        }
        metadata.put("commands", commands);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve(FILE_METADATA).toFile(), metadata);
    }

    private static void copyAnalysisTemplate(Path directory) throws IOException {
        ClassLoader loader = ThreadSnapshotService.class.getClassLoader();
        try (InputStream template = loader.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (template == null) {
                throw new IOException("analysis template resource missing: " + TEMPLATE_RESOURCE);
            }
            Files.copy(template, directory.resolve(FILE_ANALYSIS_TEMPLATE));
        }
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void annotate(ThreadSnapshotView view, LifecycleSnapshot snapshot) {
        try {
            annotator.annotate(view, snapshot);
        } catch (RuntimeException failure) {
            // The bundle is on disk; a broken annotator must not mark it failed.
            log.warn("Snapshot annotation failed (ignored): {}", failure.toString());
        }
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
        remaining.sort(Comparator
                .comparing((Path path) -> path.getFileName().toString())
                .reversed());
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
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return new ArrayList<>(entries
                    .filter(Files::isDirectory)
                    .filter(path -> FOLDER_NAME.matcher(path.getFileName().toString()).matches())
                    .toList());
        } catch (IOException unreadable) {
            log.warn("Snapshot retention could not list {}: {}", root, unreadable.toString());
            return new ArrayList<>();
        }
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

    private static boolean deleteRecursively(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            List<Path> paths = entries.sorted(Comparator.reverseOrder()).toList();
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
        String raw = instanceId == null || instanceId.isBlank() ? "unknown" : instanceId.trim();
        return UNSAFE_INSTANCE_CHARS.matcher(raw).replaceAll("-");
    }

    /** One file of the bundle and the jcmd arguments that produce it. */
    private record Command(String file, List<String> args) {
    }
}
