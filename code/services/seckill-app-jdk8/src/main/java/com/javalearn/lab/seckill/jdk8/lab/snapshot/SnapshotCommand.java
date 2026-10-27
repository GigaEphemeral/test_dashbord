package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import java.util.Collections;
import java.util.List;

/**
 * One file of the bundle and the complete argv that produces it.
 *
 * <p>Java 8 has no records; this is a plain immutable value object. The argv is
 * exactly what {@link JvmCommandExecutor} runs and what {@code metadata.json}
 * records, so what a reader sees in the manifest is what was executed.</p>
 */
public final class SnapshotCommand {

    private final String file;
    private final List<String> argv;

    public SnapshotCommand(String file, List<String> argv) {
        this.file = file;
        this.argv = Collections.unmodifiableList(argv);
    }

    /** Bundle file name, e.g. {@code thread-dump.txt}. */
    public String getFile() {
        return file;
    }

    /** Complete process argv, e.g. {@code [jstack, -l, 1]}. */
    public List<String> getArgv() {
        return argv;
    }

    /** Manifest-friendly rendering, e.g. {@code jmap -histo 1}. */
    public String getCommandLine() {
        return String.join(" ", argv);
    }
}
