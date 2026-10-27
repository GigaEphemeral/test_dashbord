package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command construction of the JDK 8 bundle (design §9.3). JDK 8 tools put the
 * pid last, and the histogram mode decides whether a full GC runs first, so
 * these are asserted without starting a process.
 */
class SnapshotCommandsTest {

    @TempDir
    Path root;

    @TempDir
    Path templateDir;

    @Test
    void buildsJstackJmapAndJcmdCommandsWithThePidLastWhereRequired() {
        SnapshotProperties properties = SnapshotTestSupport.properties(root, templateDir);

        List<SnapshotCommand> commands = SnapshotCommands.forCurrentJvm(properties, 4_242L);

        assertThat(commands).hasSize(3);
        assertThat(commands.get(0).getFile()).isEqualTo("thread-dump.txt");
        assertThat(commands.get(0).getArgv()).containsExactly("jstack", "-l", "4242");
        assertThat(commands.get(1).getFile()).isEqualTo("heap-info.txt");
        // jmap -heap is gone on 8u; JDK 8's jcmd prints the same heap summary.
        assertThat(commands.get(1).getArgv())
                .containsExactly("jcmd", "4242", "GC.heap_info");
        assertThat(commands.get(2).getFile()).isEqualTo("class-histogram.txt");
        assertThat(commands.get(2).getArgv()).containsExactly("jmap", "-histo", "4242");
    }

    @Test
    void liveHistogramOptsIntoTheFullGcFlag() {
        SnapshotProperties properties = SnapshotTestSupport.properties(root, templateDir);
        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO_LIVE);

        List<SnapshotCommand> commands = SnapshotCommands.forCurrentJvm(properties, 7L);

        assertThat(commands.get(2).getArgv()).containsExactly("jmap", "-histo:live", "7");
        assertThat(properties.isLiveHistogram()).isTrue();
    }

    @Test
    void customToolPathsAreUsedVerbatim() {
        SnapshotProperties properties = SnapshotTestSupport.properties(root, templateDir);
        properties.setJstackPath("/opt/java/openjdk/bin/jstack");
        properties.setJmapPath("/opt/java/openjdk/bin/jmap");
        properties.setJcmdPath("/opt/java/openjdk/bin/jcmd");

        List<SnapshotCommand> commands = SnapshotCommands.forCurrentJvm(properties, 1L);

        assertThat(commands.get(0).getArgv())
                .containsExactly("/opt/java/openjdk/bin/jstack", "-l", "1");
        assertThat(commands.get(1).getArgv())
                .containsExactly("/opt/java/openjdk/bin/jcmd", "1", "GC.heap_info");
        assertThat(commands.get(2).getArgv())
                .containsExactly("/opt/java/openjdk/bin/jmap", "-histo", "1");
    }

    @Test
    void commandLineIsManifestFriendly() {
        SnapshotCommand command = new SnapshotCommand("class-histogram.txt",
                Arrays.asList("jmap", "-histo", "1"));

        assertThat(command.getCommandLine()).isEqualTo("jmap -histo 1");
    }
}
