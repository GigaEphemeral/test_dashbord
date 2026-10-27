package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds the three JDK 8 diagnostic commands of one bundle (design §9.3).
 *
 * <p>Kept separate from the service so the argv contract (tool first, pid last,
 * {@code -histo} vs {@code -histo:live}) is unit-testable without a process. The
 * tools are the JDK 8 ones by design:</p>
 *
 * <ul>
 *   <li>{@code jstack -l <pid>} — full thread dump with lock information
 *       (the JDK 8 counterpart of {@code jcmd Thread.print -l});</li>
 *   <li>{@code jcmd <pid> GC.heap_info} — heap geometry and usage. JDK 8's
 *       {@code jmap -heap} was removed in the 8u line (the binary exits 1 with
 *       the usage text, verified on 8u504), so the JDK 8 heap artefact uses the
 *       one JDK 8 command that still prints it; it is byte-for-byte the same
 *       diagnostic the JDK 21 bundle uses, which makes the two heap-info files
 *       directly comparable;</li>
 *   <li>{@code jmap -histo[:live] <pid>} — class histogram
 *       (counterpart of {@code jcmd GC.class_histogram}).</li>
 * </ul>
 */
public final class SnapshotCommands {

    public static final String FILE_THREAD_DUMP = "thread-dump.txt";
    public static final String FILE_HEAP_INFO = "heap-info.txt";
    public static final String FILE_CLASS_HISTOGRAM = "class-histogram.txt";

    private SnapshotCommands() {
    }

    /** The three commands of one bundle, in bundle order. */
    public static List<SnapshotCommand> forCurrentJvm(SnapshotProperties config, long pid) {
        String pidText = Long.toString(pid);
        List<SnapshotCommand> commands = new ArrayList<SnapshotCommand>(3);
        commands.add(new SnapshotCommand(FILE_THREAD_DUMP,
                Collections.unmodifiableList(Arrays.asList(
                        config.getJstackPath(), "-l", pidText))));
        commands.add(new SnapshotCommand(FILE_HEAP_INFO,
                Collections.unmodifiableList(Arrays.asList(
                        config.getJcmdPath(), pidText, "GC.heap_info"))));
        commands.add(new SnapshotCommand(FILE_CLASS_HISTOGRAM,
                Collections.unmodifiableList(Arrays.asList(
                        config.getJmapPath(),
                        config.isLiveHistogram() ? "-histo:live" : "-histo",
                        pidText))));
        return Collections.unmodifiableList(commands);
    }
}
