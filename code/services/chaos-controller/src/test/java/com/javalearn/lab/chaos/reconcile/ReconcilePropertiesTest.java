package com.javalearn.lab.chaos.reconcile;

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

/** B3-04 defaults, env keys and startup validation. */
class ReconcilePropertiesTest {

    @Test
    void defaultsComeFromApplicationYml() throws IOException {
        ReconcileProperties properties = bind(Map.of());

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMysqlMode()).isEqualTo("standalone");
        assertThat(properties.getBusinessUrl()).isEmpty();
        assertThat(properties.getStandaloneUrl()).contains(":3306/seckill");
        assertThat(properties.getRouterUrl()).contains(":6446/seckill");
        assertThat(properties.getSchema()).isEqualTo("seckill");
        assertThat(properties.getUser()).isEqualTo("seckill");
        assertThat(properties.getPassword()).isEqualTo("seckill");
        assertThat(properties.getQueryTimeoutSeconds()).isEqualTo(5);
        assertThat(properties.getMqDrainTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.getMqRequestTimeoutSeconds()).isEqualTo(2);
        assertThat(properties.getMqPollIntervalMillis()).isEqualTo(500);
        assertThat(properties.getMaxSampleRows()).isEqualTo(20);
        assertThat(properties.getMqManagementUrl()).isEqualTo("http://rabbitmq:15672");
        assertThat(properties.getMqVhost()).isEqualTo("/");
        assertThat(properties.getMqQueue()).isEqualTo("seckill.order.queue");
        assertThat(properties.getMqUser()).isEqualTo("lab");
        assertThat(properties.getMqPassword()).isEqualTo("lab");
        properties.validate();
    }

    @Test
    void everyReconcileEnvKeyIsDeclaredInTheShippedYml() throws IOException {
        String yml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(yml).contains(
                "LAB_RECONCILE_ENABLED", "LAB_RECONCILE_MYSQL_MODE",
                "LAB_RECONCILE_BUSINESS_URL", "LAB_RECONCILE_STANDALONE_URL",
                "LAB_RECONCILE_ROUTER_URL", "LAB_RECONCILE_SCHEMA",
                "LAB_RECONCILE_BUSINESS_USER", "LAB_RECONCILE_BUSINESS_PASSWORD",
                "LAB_RECONCILE_QUERY_TIMEOUT_SECONDS", "LAB_RECONCILE_MAX_SAMPLE_ROWS",
                "LAB_RECONCILE_MQ_DRAIN_TIMEOUT_SECONDS",
                "LAB_RECONCILE_MQ_REQUEST_TIMEOUT_SECONDS",
                "LAB_RECONCILE_MQ_POLL_INTERVAL_MILLIS",
                "LAB_RECONCILE_MQ_MANAGEMENT_URL", "LAB_RECONCILE_MQ_VHOST",
                "LAB_RECONCILE_MQ_QUEUE", "LAB_RECONCILE_MQ_USER",
                "LAB_RECONCILE_MQ_PASSWORD");
    }

    @Test
    void environmentStyleOverridesWin() throws IOException {
        ReconcileProperties properties = bind(Map.of(
                "lab.reconcile.mysql-mode", "router",
                "lab.reconcile.query-timeout-seconds", "9",
                "lab.reconcile.max-sample-rows", "5",
                "lab.reconcile.mq-drain-timeout-seconds", "30",
                "lab.reconcile.mq-request-timeout-seconds", "3",
                "lab.reconcile.user", "reconciler",
                "lab.reconcile.password", "secret-value"));

        assertThat(properties.isRouter()).isTrue();
        assertThat(properties.activeUrl()).contains(":6446/seckill");
        assertThat(properties.getQueryTimeoutSeconds()).isEqualTo(9);
        assertThat(properties.getMaxSampleRows()).isEqualTo(5);
        assertThat(properties.getMqDrainTimeoutSeconds()).isEqualTo(30);
        properties.validate();
    }

    @Test
    void explicitBusinessUrlOverridesTheModeUrl() throws IOException {
        ReconcileProperties properties = bind(Map.of(
                "lab.reconcile.business-url",
                "jdbc:mysql://pinned-mysql:3306/seckill?useSSL=false"));

        assertThat(properties.activeUrl()).startsWith("jdbc:mysql://pinned-mysql:3306/seckill");
        properties.validate();

        ReconcileProperties bad = valid();
        bad.setBusinessUrl("http://not-jdbc/seckill");
        assertThatThrownBy(bad::validate)
                .hasMessageContaining("lab.reconcile.business-url")
                .hasMessageContaining("LAB_RECONCILE_BUSINESS_URL");
    }

    @Test
    void badModeSchemaRangesAndUrlsAreRejected() {
        ReconcileProperties badMode = valid();
        badMode.setMysqlMode("cluster");
        assertThatThrownBy(badMode::validate)
                .hasMessageContaining("lab.reconcile.mysql-mode")
                .hasMessageContaining("LAB_RECONCILE_MYSQL_MODE");

        ReconcileProperties badSchema = valid();
        badSchema.setSchema("seckill; DROP");
        assertThatThrownBy(badSchema::validate)
                .hasMessageContaining("lab.reconcile.schema")
                .hasMessageContaining("LAB_RECONCILE_SCHEMA");

        ReconcileProperties badTimeout = valid();
        badTimeout.setQueryTimeoutSeconds(0);
        assertThatThrownBy(badTimeout::validate)
                .hasMessageContaining("lab.reconcile.query-timeout-seconds")
                .hasMessageContaining("LAB_RECONCILE_QUERY_TIMEOUT_SECONDS");

        ReconcileProperties badDrain = valid();
        badDrain.setMqDrainTimeoutSeconds(0);
        assertThatThrownBy(badDrain::validate)
                .hasMessageContaining("lab.reconcile.mq-drain-timeout-seconds");

        ReconcileProperties requestOverDrain = valid();
        requestOverDrain.setMqDrainTimeoutSeconds(2);
        requestOverDrain.setMqRequestTimeoutSeconds(3);
        assertThatThrownBy(requestOverDrain::validate)
                .hasMessageContaining("lab.reconcile.mq-request-timeout-seconds");

        ReconcileProperties badSamples = valid();
        badSamples.setMaxSampleRows(0);
        assertThatThrownBy(badSamples::validate)
                .hasMessageContaining("lab.reconcile.max-sample-rows");

        ReconcileProperties badMqUrl = valid();
        badMqUrl.setMqManagementUrl("rabbitmq:15672");
        assertThatThrownBy(badMqUrl::validate)
                .hasMessageContaining("lab.reconcile.mq-management-url")
                .hasMessageContaining("LAB_RECONCILE_MQ_MANAGEMENT_URL");
    }

    @Test
    void passwordsAreRedactedFromToString() {
        ReconcileProperties properties = valid();
        properties.setPassword("business-secret");
        properties.setMqPassword("rabbit-secret");

        assertThat(properties.toString())
                .doesNotContain("business-secret")
                .doesNotContain("rabbit-secret")
                .contains("password='***'")
                .contains("mqPassword='***'");
    }

    private static ReconcileProperties valid() {
        ReconcileProperties properties = new ReconcileProperties();
        properties.validate();
        return properties;
    }

    private static ReconcileProperties bind(Map<String, Object> overrides) throws IOException {
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
                .bind("lab.reconcile", Bindable.of(ReconcileProperties.class))
                .orElseThrow(() -> new IllegalStateException("lab.reconcile did not bind"));
    }
}
