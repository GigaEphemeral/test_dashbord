package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared Java 8 test doubles for the snapshot tests: a properties factory with
 * every validated field set, a mutable clock, a fake command executor and a
 * template file writer. No Docker, no real jstack/jmap.
 */
final class SnapshotTestSupport {

    static final long PID = 4_242L;
    static final String TEMPLATE_TEXT = "# analysis template\n\n1. thread dump\n2. heap\n";

    private SnapshotTestSupport() {
    }

    static SnapshotProperties properties(Path root, Path template) {
        SnapshotProperties properties = new SnapshotProperties();
        properties.setEnabled(true);
        properties.setRootDir(root.toString());
        properties.setMaxCount(10L);
        properties.setMaxAgeSeconds(0L);
        properties.setCommandTimeoutMs(5_000L);
        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO);
        properties.setJstackPath("jstack");
        properties.setJmapPath("jmap");
        properties.setJcmdPath("jcmd");
        properties.setTemplatePath(template.toString());
        return properties;
    }

    static Path writeTemplate(Path directory) throws IOException {
        Path template = directory.resolve("snapshot-analysis-template.md");
        Files.write(template, TEMPLATE_TEXT.getBytes(StandardCharsets.UTF_8));
        return template;
    }

    /** Fixed-start clock; retention tests advance it to age seeded folders. */
    static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(long epochMillis) {
            this(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Records every argv; optionally fails the Nth invocation (1-based). */
    static final class FakeExecutor implements JvmCommandExecutor {

        final List<List<String>> invocations = new ArrayList<List<String>>();
        int failOnInvocation;
        boolean failAll;

        @Override
        public String execute(List<String> command, Duration timeout)
                throws IOException, InterruptedException {
            invocations.add(Collections.unmodifiableList(new ArrayList<String>(command)));
            if (failAll || invocations.size() == failOnInvocation) {
                throw new IOException("snapshot tool exited with 1: unable to attach");
            }
            return "canned " + String.join(" ", command) + "\n";
        }
    }
}
