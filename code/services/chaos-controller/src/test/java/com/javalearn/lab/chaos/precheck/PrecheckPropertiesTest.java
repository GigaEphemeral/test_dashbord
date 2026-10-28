package com.javalearn.lab.chaos.precheck;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuration contract: the documented defaults, the comma-separated app
 * container list, and the validation that names the property + env variable.
 */
class PrecheckPropertiesTest {

    @Test
    void defaultsMatchThePublishedConfiguration() {
        PrecheckProperties properties = new PrecheckProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTrafficContainer()).isEqualTo("jl-traffic");
        assertThat(properties.appContainerNames())
                .containsExactly("jl-app-1", "jl-app-2", "jl-app-3");
        assertThat(properties.getPrometheusTimeoutSeconds()).isEqualTo(5);
        assertThat(properties.getRequiredAppTargets()).isEqualTo(3);
        assertThat(properties.isRequireRedisExporter()).isTrue();
        assertThat(properties.isRequireMysqlProbe()).isTrue();
        assertThat(properties.getExtraQueries()).isEmpty();
        assertThat(properties.extraQueryList()).isEmpty();
        assertThat(properties.getMemoryReserveMb()).isEqualTo(1024);
        assertThat(properties.getExpectedAppImageId()).isEmpty();
        assertThat(properties.getMaxListedItems()).isEqualTo(10);
        assertThat(properties.getCheckTimeoutSeconds()).isEqualTo(30);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void theAppContainerListIsTrimmedAndDeDuplicated() {
        PrecheckProperties properties = new PrecheckProperties();
        properties.setAppContainers(" jl-app-1 , jl-app-2 ,, jl-app-1 ");

        assertThat(properties.appContainerNames()).containsExactly("jl-app-1", "jl-app-2");
    }

    @Test
    void validationNamesTheOffendingPropertyAndEnvironmentVariable() {
        PrecheckProperties blank = new PrecheckProperties();
        blank.setTrafficContainer(" ");
        assertThatThrownBy(blank::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.precheck.traffic-container")
                .hasMessageContaining("LAB_PRECHECK_TRAFFIC_CONTAINER");

        PrecheckProperties empty = new PrecheckProperties();
        empty.setAppContainers(" , ");
        assertThatThrownBy(empty::validate)
                .hasMessageContaining("no container name");

        PrecheckProperties negative = new PrecheckProperties();
        negative.setMemoryReserveMb(-1);
        assertThatThrownBy(negative::validate)
                .hasMessageContaining("lab.precheck.memory-reserve-mb");

        PrecheckProperties tooSmall = new PrecheckProperties();
        tooSmall.setRequiredAppTargets(0);
        assertThatThrownBy(tooSmall::validate)
                .hasMessageContaining("LAB_PRECHECK_REQUIRED_APP_TARGETS");

        PrecheckProperties noTimeout = new PrecheckProperties();
        noTimeout.setCheckTimeoutSeconds(0);
        assertThatThrownBy(noTimeout::validate)
                .hasMessageContaining("LAB_PRECHECK_CHECK_TIMEOUT_SECONDS");
    }

    @Test
    void extraQueriesAreParsedAndValidated() {
        PrecheckProperties properties = new PrecheckProperties();
        properties.setExtraQueries(" redis-ha = sum(up{job=\"redis-exporter-ha\"}) >= 1 ; "
                + "mysql-ha=sum(mysql_cluster_probe_up) == 1 ");
        assertThat(properties.extraQueryList()).containsExactly(
                new PrecheckProperties.RequiredQuery("redis-ha",
                        "sum(up{job=\"redis-exporter-ha\"}) >= 1"),
                new PrecheckProperties.RequiredQuery("mysql-ha", "sum(mysql_cluster_probe_up) == 1"));
        assertThatCode(properties::validate).doesNotThrowAnyException();

        PrecheckProperties noEquals = new PrecheckProperties();
        noEquals.setExtraQueries("redis-ha");
        assertThatThrownBy(noEquals::validate)
                .hasMessageContaining("LAB_PRECHECK_EXTRA_QUERIES")
                .hasMessageContaining("name=promql");

        PrecheckProperties badName = new PrecheckProperties();
        badName.setExtraQueries("Redis HA=sum(up)");
        assertThatThrownBy(badName::validate).hasMessageContaining("must match [a-z0-9_.-]");

        PrecheckProperties duplicate = new PrecheckProperties();
        duplicate.setExtraQueries("redis=sum(up);redis=sum(up)");
        assertThatThrownBy(duplicate::validate).hasMessageContaining("repeats name");
    }

    @Test
    void theContainerListIsImmutable() {
        PrecheckProperties properties = new PrecheckProperties();
        List<String> names = properties.appContainerNames();
        assertThatThrownBy(() -> names.add("jl-extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
