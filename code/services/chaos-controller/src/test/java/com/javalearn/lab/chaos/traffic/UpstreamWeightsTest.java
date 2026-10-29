package com.javalearn.lab.chaos.traffic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamWeightsTest {

    private static final String CONF = """
            server { listen 80; }
            upstream seckill_backend {
                zone seckill_backend 64k;
                server app-1:8080 max_fails=2 fail_timeout=5s;
                server app-2:8080 weight=3;
                server app-3:8080;
            }
            """;

    @Test
    void parsesHostsAndWeightsWithNginxDefaults() {
        UpstreamWeights.Result result = UpstreamWeights.parse(CONF, "seckill_backend");

        assertThat(result.available()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.weights()).isEqualTo(Map.of("app-1", 1, "app-2", 3, "app-3", 1));
    }

    @Test
    void missingUpstreamIsUnavailableNotNullAnEmptyMap() {
        UpstreamWeights.Result result = UpstreamWeights.parse(CONF, "other_backend");

        assertThat(result.available()).isFalse();
        assertThat(result.weights()).isEmpty();
        assertThat(result.reason()).contains("other_backend");
    }

    @Test
    void unreadableFileKeepsTheReason(@TempDir Path dir) {
        UpstreamWeights.Result result = UpstreamWeights.read(dir.resolve("missing.conf"), "seckill_backend");

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("不可读");
    }

    @Test
    void readsTheFileFromDisk(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("default.conf");
        Files.writeString(conf, CONF, StandardCharsets.UTF_8);

        UpstreamWeights.Result result = UpstreamWeights.read(conf, "seckill_backend");

        assertThat(result.available()).isTrue();
        assertThat(result.weights()).containsEntry("app-2", 3);
    }

    @Test
    void aConfigOverTheByteCapIsUnavailable(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("big.conf");
        Files.writeString(conf, CONF + " ".repeat(512), StandardCharsets.UTF_8);

        UpstreamWeights.Result result = UpstreamWeights.read(conf, "seckill_backend", 64);

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("上限");
    }
}
