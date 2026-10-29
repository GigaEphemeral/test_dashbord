package com.javalearn.lab.chaos.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalog is the only key namespace the API accepts. These tests load the
 * shipped resource (so a typo in {@code catalog.yml} fails the build) and prove
 * the startup validators: unique key, key charset, non-blank fields and the
 * single {@code ${range}} placeholder.
 */
class MetricCatalogTest {

    @Test
    void shippedCatalogHasUniqueValidKeysAndNonBlankPromql() {
        MetricCatalog catalog = MetricCatalog.load(new DefaultResourceLoader(),
                "classpath:metrics/catalog.yml", 40);

        assertThat(catalog.version()).isEqualTo(1);
        assertThat(catalog.definitions()).hasSize(30);
        assertThat(catalog.keys()).contains(
                "http.rps", "http.success.ratio", "http.failure.ratio", "http.status.rps",
                "http.latency.p95", "http.latency.p99",
                "jvm.heap.used", "jvm.heap.max", "jvm.gc.pause.max", "jvm.cpu.usage",
                "jvm.threads.live", "hikari.active", "hikari.pending",
                "executor.active", "executor.queue", "executor.rejected",
                "tomcat.threads.busy", "tomcat.threads.current", "tomcat.threads.max",
                "tomcat.connections.current", "tomcat.connections.max",
                "cache.l1.hit.ratio", "cache.l1.entries", "cache.l2.keys", "cache.l2.memory",
                "business.orders.created", "business.stock.sold_out", "business.stock.oversold",
                "mysql.cluster.probe.up", "targets.up");
        assertThat(catalog.definitions()).allSatisfy(definition -> {
            assertThat(definition.key()).matches("[a-z0-9_.-]+");
            assertThat(definition.label()).isNotBlank();
            assertThat(definition.unit()).isNotBlank();
            assertThat(definition.promql()).isNotBlank();
        });
        assertThat(catalog.get("http.latency.p95").promql())
                .contains("histogram_quantile(0.95")
                .contains("${range}");
        assertThat(catalog.get("cache.l2.keys").groupByInstance()).isFalse();
        assertThat(catalog.get("targets.up").groupByInstance()).isTrue();
    }

    @Test
    void tomcatKeysUseTheRealExportNamesAndHttpNioPool() {
        MetricCatalog catalog = MetricCatalog.load(new DefaultResourceLoader(),
                "classpath:metrics/catalog.yml", 40);

        assertThat(catalog.get("tomcat.threads.busy").promql())
                .contains("tomcat_threads_busy_threads")
                .contains("name=\"http-nio-8080\"");
        assertThat(catalog.get("tomcat.threads.current").promql())
                .contains("tomcat_threads_current_threads");
        assertThat(catalog.get("tomcat.threads.max").promql())
                .contains("tomcat_threads_config_max_threads");
        assertThat(catalog.get("tomcat.connections.current").promql())
                .contains("tomcat_connections_current_connections");
        assertThat(catalog.get("tomcat.connections.max").promql())
                .contains("tomcat_connections_config_max_connections");
        for (String key : List.of("tomcat.threads.busy", "tomcat.threads.current",
                "tomcat.threads.max", "tomcat.connections.current",
                "tomcat.connections.max")) {
            assertThat(catalog.get(key).groupByInstance()).as(key).isTrue();
            assertThat(catalog.get(key).promql()).as(key)
                    .contains("job=~\"seckill-app|seckill-app-jdk8\"");
        }
    }

    @Test
    void duplicateKeysAreRejectedAtStartup() {
        List<MetricDefinition> definitions = List.of(definition("http.rps"), definition("http.rps"));

        assertThatThrownBy(() -> new MetricCatalog(1, definitions, 40))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate key 'http.rps'");
    }

    @Test
    void illegalKeysAreRejected() {
        for (String key : List.of("HTTP RPS", "Http.Rps", "http/rps", "http:rps")) {
            assertThatThrownBy(() -> new MetricCatalog(1, List.of(definition(key)), 40))
                    .as("key %s", key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match [a-z0-9_.-]+");
        }
    }

    @Test
    void blankPromqlAndUnknownPlaceholdersAreRejected() {
        assertThatThrownBy(() -> new MetricCatalog(1,
                List.of(new MetricDefinition("http.rps", "RPS", "req/s", " ", true)), 40))
                .hasMessageContaining("blank promql");

        assertThatThrownBy(() -> new MetricCatalog(1,
                List.of(new MetricDefinition("http.rps", "RPS", "req/s",
                        "rate(x[${window}])", true)), 40))
                .hasMessageContaining("unsupported placeholder ${window}");
    }

    @Test
    void theCatalogMayNotExceedMaxKeys() {
        assertThatThrownBy(() -> new MetricCatalog(1,
                List.of(definition("a"), definition("b")), 1))
                .hasMessageContaining("lab.metrics.max-keys")
                .hasMessageContaining("LAB_METRICS_MAX_KEYS");
    }

    @Test
    void aMissingCatalogResourceFailsFast() {
        assertThatThrownBy(() -> MetricCatalog.load(new DefaultResourceLoader(),
                "classpath:metrics/missing.yml", 40))
                .hasMessageContaining("lab.metrics.catalog-location")
                .hasMessageContaining("LAB_METRICS_CATALOG_LOCATION");
    }

    private static MetricDefinition definition(String key) {
        return new MetricDefinition(key, "label", "unit",
                "sum by (instance) (rate(metric[${range}]))", true);
    }
}
