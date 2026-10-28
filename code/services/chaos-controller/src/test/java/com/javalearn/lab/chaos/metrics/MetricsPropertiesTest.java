package com.javalearn.lab.chaos.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the real {@code application.yml} so the defaults asserted here are the
 * ones the controller ships, then proves env-style overrides and typed startup
 * validation for the B1-03 keys.
 */
class MetricsPropertiesTest {

    @Test
    void defaultsComeFromApplicationYml() throws IOException {
        MetricsProperties properties = bind(Map.of());

        assertThat(properties.getPrometheusUrl()).isEqualTo("http://prometheus:9090");
        assertThat(properties.getConnectTimeoutSeconds()).isEqualTo(2);
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.getScrapeGraceSeconds()).isEqualTo(15);
        assertThat(properties.getDrainGraceSeconds()).isEqualTo(30);
        assertThat(properties.getMaxPoints()).isEqualTo(300);
        assertThat(properties.getMinStepSeconds()).isEqualTo(15);
        assertThat(properties.getMaxSeries()).isEqualTo(120);
        assertThat(properties.getMaxKeys()).isEqualTo(40);
        assertThat(properties.getGrafanaUrl()).isEqualTo("http://grafana:3000");
        assertThat(properties.getTempoUrl()).isEqualTo("http://tempo:3200");
        assertThat(properties.getGrafanaDashboardUid()).isEmpty();
        assertThat(properties.getCatalogLocation()).isEqualTo("classpath:metrics/catalog.yml");
    }

    @Test
    void everyMetricsEnvKeyIsDeclaredInTheShippedYml() throws IOException {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(yml).contains(
                "LAB_METRICS_PROMETHEUS_URL", "LAB_METRICS_CONNECT_TIMEOUT_SECONDS",
                "LAB_METRICS_REQUEST_TIMEOUT_SECONDS", "LAB_METRICS_SCRAPE_GRACE_SECONDS",
                "LAB_METRICS_DRAIN_GRACE_SECONDS", "LAB_METRICS_MAX_POINTS",
                "LAB_METRICS_MIN_STEP_SECONDS", "LAB_METRICS_MAX_SERIES",
                "LAB_METRICS_MAX_KEYS", "LAB_METRICS_GRAFANA_URL", "LAB_METRICS_TEMPO_URL",
                "LAB_METRICS_GRAFANA_DASHBOARD_UID", "LAB_METRICS_CATALOG_LOCATION");
    }

    @Test
    void environmentStyleOverrideWinsBeforeBinding() throws IOException {
        MetricsProperties properties = bind(Map.of(
                "lab.metrics.prometheus-url", "http://prom.lab:9999",
                "lab.metrics.scrape-grace-seconds", "1",
                "lab.metrics.drain-grace-seconds", "2",
                "lab.metrics.max-points", "42",
                "lab.metrics.max-keys", "7",
                "lab.metrics.grafana-dashboard-uid", "lab-overview"));

        assertThat(properties.getPrometheusUrl()).isEqualTo("http://prom.lab:9999");
        assertThat(properties.getScrapeGraceSeconds()).isEqualTo(1);
        assertThat(properties.getDrainGraceSeconds()).isEqualTo(2);
        assertThat(properties.getMaxPoints()).isEqualTo(42);
        assertThat(properties.getMaxKeys()).isEqualTo(7);
        assertThat(properties.getGrafanaDashboardUid()).isEqualTo("lab-overview");
        properties.validate();
    }

    @Test
    void urlAndRangeBoundsAreEnforced() {
        MetricsProperties badUrl = valid();
        badUrl.setPrometheusUrl("prometheus:9090");
        assertThatThrownBy(badUrl::validate)
                .hasMessageContaining("lab.metrics.prometheus-url")
                .hasMessageContaining("LAB_METRICS_PROMETHEUS_URL");

        MetricsProperties badGrafana = valid();
        badGrafana.setGrafanaUrl("grafana:3000");
        assertThatThrownBy(badGrafana::validate)
                .hasMessageContaining("lab.metrics.grafana-url");

        MetricsProperties zeroPoints = valid();
        zeroPoints.setMaxPoints(0);
        assertThatThrownBy(zeroPoints::validate)
                .hasMessageContaining("lab.metrics.max-points")
                .hasMessageContaining("LAB_METRICS_MAX_POINTS");

        MetricsProperties zeroKeys = valid();
        zeroKeys.setMaxKeys(0);
        assertThatThrownBy(zeroKeys::validate)
                .hasMessageContaining("lab.metrics.max-keys");

        MetricsProperties negativeGrace = valid();
        negativeGrace.setScrapeGraceSeconds(-1);
        assertThatThrownBy(negativeGrace::validate)
                .hasMessageContaining("lab.metrics.scrape-grace-seconds");
    }

    @Test
    void dashboardUidMustBeBlankOrAUid() {
        MetricsProperties properties = valid();
        properties.setGrafanaDashboardUid("has space");
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("lab.metrics.grafana-dashboard-uid")
                .hasMessageContaining("LAB_METRICS_GRAFANA_DASHBOARD_UID");

        MetricsProperties blank = valid();
        blank.setGrafanaDashboardUid("  ");
        blank.validate();
    }

    private static MetricsProperties valid() {
        MetricsProperties properties = new MetricsProperties();
        properties.validate();
        return properties;
    }

    private static MetricsProperties bind(Map<String, Object> overrides) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : loaded) {
            environment.getPropertySources().addLast(source);
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("envOverride", overrides));
        }
        return Binder.get(environment)
                .bind("lab.metrics", Bindable.of(MetricsProperties.class))
                .orElseThrow(() -> new IllegalStateException("lab.metrics did not bind"));
    }
}
