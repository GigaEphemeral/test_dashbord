package com.javalearn.lab.seckill.jdk8.lab.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup guard for {@code app.lab.snapshot.*} (design §9.3): a blank runtime
 * path, a zero timeout, an unbounded retention budget or an unknown jmap mode
 * must fail startup instead of producing a capture that silently writes nowhere
 * or GCs the heap unexpectedly.
 */
class SnapshotPropertiesValidationTest {

    @Test
    void defaultsValidate() {
        SnapshotProperties properties = validProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveCommandTimeoutIsRejected() {
        SnapshotProperties properties = validProperties();
        properties.setCommandTimeoutMs(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command-timeout-ms");
    }

    @Test
    void maxCountBelowOneIsRejected() {
        SnapshotProperties properties = validProperties();
        properties.setMaxCount(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-count");
    }

    @Test
    void negativeMaxAgeIsRejected() {
        SnapshotProperties properties = validProperties();
        properties.setMaxAgeSeconds(-1L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-age-seconds");
    }

    @Test
    void blankRootDirIsRejectedWhileEnabled() {
        SnapshotProperties properties = validProperties();
        properties.setRootDir(" ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root-dir");
    }

    @Test
    void blankToolPathsAreRejectedWhileEnabled() {
        SnapshotProperties blankJstack = validProperties();
        blankJstack.setJstackPath("");
        assertThatThrownBy(blankJstack::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jstack-path");

        SnapshotProperties blankJmap = validProperties();
        blankJmap.setJmapPath(null);
        assertThatThrownBy(blankJmap::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jmap-path");

        SnapshotProperties blankJcmd = validProperties();
        blankJcmd.setJcmdPath(" ");
        assertThatThrownBy(blankJcmd::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jcmd-path");
    }

    @Test
    void blankTemplatePathIsRejectedWhileEnabled() {
        SnapshotProperties properties = validProperties();
        properties.setTemplatePath("  ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template-path");
    }

    @Test
    void unknownJmapModeIsRejected() {
        SnapshotProperties properties = validProperties();
        properties.setJmapMode("histo:all");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jmap-mode");
    }

    @Test
    void aDisabledSnapshotNeedsNoPaths() {
        SnapshotProperties properties = validProperties();
        properties.setEnabled(false);
        properties.setRootDir("");
        properties.setJstackPath(null);
        properties.setJmapPath(" ");
        properties.setJcmdPath("");
        properties.setTemplatePath("");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void liveModeIsExposedToTheCommandBuilder() {
        SnapshotProperties properties = validProperties();
        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO_LIVE);

        assertThat(properties.isLiveHistogram()).isTrue();

        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO);
        assertThat(properties.isLiveHistogram()).isFalse();
    }

    private static SnapshotProperties validProperties() {
        SnapshotProperties properties = new SnapshotProperties();
        properties.setEnabled(true);
        properties.setRootDir("/app/lab-snapshots");
        properties.setMaxCount(20L);
        properties.setMaxAgeSeconds(604_800L);
        properties.setCommandTimeoutMs(5_000L);
        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO);
        properties.setJstackPath("jstack");
        properties.setJmapPath("jmap");
        properties.setJcmdPath("jcmd");
        properties.setTemplatePath(
                Paths.get("doc", "process", "snapshot-analysis-template.md").toString());
        return properties;
    }
}
