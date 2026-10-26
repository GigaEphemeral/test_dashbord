package com.javalearn.lab.seckill.lab.thread;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup guard for {@code app.lab.thread.snapshot.*} (design §9.3): a blank
 * runtime path or a zero retention/timeout budget must fail startup instead of
 * producing a capture that silently writes nowhere or prunes everything.
 */
class ThreadSnapshotPropertiesValidationTest {

    @Test
    void defaultsValidate() {
        assertThatCode(TestLabThreadProperties.defaults()::validate).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveCommandTimeoutIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setCommandTimeoutMs(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command-timeout-ms");
    }

    @Test
    void maxCountBelowOneIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setMaxCount(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-count");
    }

    @Test
    void negativeMaxAgeIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setMaxAgeSeconds(-1L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-age-seconds");
    }

    @Test
    void blankRootDirIsRejectedWhileEnabled() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setRootDir(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root-dir");
    }

    @Test
    void blankJcmdPathIsRejectedWhileEnabled() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setJcmdPath("");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jcmd-path");
    }

    @Test
    void aDisabledSnapshotNeedsNoPaths() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().setEnabled(false);
        properties.getSnapshot().setRootDir("");
        properties.getSnapshot().setJcmdPath(" ");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void anInvalidGrafanaUrlFailsStartup() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().getAnnotation().setGrafanaUrl("not a url");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grafana-url");
    }

    @Test
    void nonPositiveAnnotationTimeoutIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getSnapshot().getAnnotation().setTimeoutSeconds(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("annotation.timeout-seconds");
    }
}
