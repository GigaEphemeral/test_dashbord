package com.javalearn.lab.chaos.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToxiproxyPropertiesTest {

    @Test
    void defaultsAreValidAndTargetTheTwoLabProxies() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();

        config.validate();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getUrl()).isEqualTo("http://toxiproxy:8474");
        assertThat(config.getProxies()).containsExactly("redis", "mysql");
        assertThat(config.getDefaultLatencyMs()).isEqualTo(500);
        assertThat(config.getDefaultTimeoutMs()).isEqualTo(1000);
    }

    @Test
    void enabledRequiresAUrl() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setUrl("  ");

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.toxiproxy.url")
                .hasMessageContaining("CHAOS_TOXIPROXY_URL");
    }

    @Test
    void urlMustBeAbsoluteHttp() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setUrl("toxiproxy:8474");

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute http(s) URL");
    }

    @Test
    void disabledLayerAllowsBlankUrlAndEmptyAllowList() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setEnabled(false);
        config.setUrl("");
        config.setProxies(List.of());

        config.validate();
    }

    @Test
    void enabledLayerRejectsAnEmptyAllowList() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setProxies(List.of());

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one proxy");
    }

    @Test
    void duplicateOrMalformedProxyNamesAreRejected() {
        ChaosProperties.Toxiproxy duplicate = new ChaosProperties().getToxiproxy();
        duplicate.setProxies(List.of("redis", "redis"));
        assertThatThrownBy(duplicate::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate proxy");

        ChaosProperties.Toxiproxy malformed = new ChaosProperties().getToxiproxy();
        malformed.setProxies(List.of("redis/../../etc"));
        assertThatThrownBy(malformed::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid proxy name");
    }

    @Test
    void timeoutsAreBounded() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setConnectTimeoutSeconds(0);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.toxiproxy.connect-timeout-seconds");
    }

    @Test
    void defaultLatencyMustStayWithinTheMaximum() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setMaxLatencyMs(100);
        config.setDefaultLatencyMs(500);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.toxiproxy.default-latency-ms");
    }

    @Test
    void timeoutToxicMustBePositiveBecauseZeroWouldStallForever() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setDefaultTimeoutMs(0);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.toxiproxy.default-timeout-ms");
    }

    @Test
    void bandwidthBoundsAreValidated() {
        ChaosProperties.Toxiproxy config = new ChaosProperties().getToxiproxy();
        config.setMaxBandwidthKbps(10);
        config.setDefaultBandwidthKbps(100);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.toxiproxy.default-bandwidth-kbps");
    }
}
