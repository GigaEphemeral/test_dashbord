package com.javalearn.lab.chaos.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppProxyPropertiesTest {

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> new ChaosProperties().getAppProxy().validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ChaosProperties.AppProxy().validate())
                .doesNotThrowAnyException();
    }

    @Test
    void instanceListMustNotBeEmpty() {
        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setInstances(new ArrayList<>());
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.app-proxy.instances")
                .hasMessageContaining("CHAOS_APP_PROXY_INSTANCES");

        config.setInstances(null);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void instanceNamesAreValidatedAndDuplicatesRejected() {
        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setInstances(List.of("app-1", "app/2"));
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app/2");

        config.setInstances(List.of("app-1", "app-1"));
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void transportAndTimeoutsAreBounded() {
        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();

        config.setScheme("ftp");
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAOS_APP_PROXY_SCHEME");

        config.setScheme("http");
        config.setPort(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.app-proxy.port");

        config.setPort(8080);
        config.setConnectTimeoutSeconds(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONNECT_TIMEOUT");

        config.setConnectTimeoutSeconds(2);
        config.setRequestTimeoutSeconds(61);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REQUEST_TIMEOUT");

        config.setRequestTimeoutSeconds(10);
        config.setMaxRequestBytes(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAX_REQUEST_BYTES");

        config.setMaxRequestBytes(8192);
        config.setMaxResponseBytes(0);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAX_RESPONSE_BYTES");

        config.setMaxResponseBytes(16_777_217);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.app-proxy.max-response-bytes");

        config.setMaxResponseBytes(2_097_152);
        assertThatCode(config::validate).doesNotThrowAnyException();
    }
}
