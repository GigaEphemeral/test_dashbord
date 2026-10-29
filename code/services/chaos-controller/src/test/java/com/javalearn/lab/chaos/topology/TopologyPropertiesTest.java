package com.javalearn.lab.chaos.topology;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B5-01a startup validation of the externalized topology knobs. */
class TopologyPropertiesTest {

    @Test
    void documentedDefaultsValidate() {
        TopologyProperties properties = new TopologyProperties();

        properties.validate();

        assertThat(properties.getRateWindow()).isEqualTo("1m");
        assertThat(properties.getCacheSeconds()).isEqualTo(2);
        assertThat(properties.getClusterNodes()).isEqualTo(3);
        assertThat(properties.getSidecars()).contains("jl-redis-1-exporter");
    }

    @Test
    void rejectsAnInvalidPromqlRateWindow() {
        TopologyProperties properties = new TopologyProperties();
        properties.setRateWindow("5x");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.topology.rate-window")
                .hasMessageContaining("LAB_TOPOLOGY_RATE_WINDOW");
    }

    @Test
    void rejectsANegativeCache() {
        TopologyProperties properties = new TopologyProperties();
        properties.setCacheSeconds(-1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.topology.cache-seconds");
    }

    @Test
    void rejectsAnInvalidPoolTag() {
        TopologyProperties properties = new TopologyProperties();
        properties.setPoolName("bad name");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.topology.pool-name");
    }

    @Test
    void rejectsDuplicateSidecars() {
        TopologyProperties properties = new TopologyProperties();
        properties.setSidecars(List.of("jl-nginx-exporter", "jl-nginx-exporter"));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.topology.sidecars repeats");
    }
}
