package com.javalearn.lab.chaos.appproxy;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
import com.javalearn.lab.chaos.error.UnknownAppInstanceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppProxyServiceTest {

    private static final byte[] FAULT_BODY = "{\"mode\":\"cpu-burn\",\"hold\":\"auto\",\"ttlSeconds\":5}"
            .getBytes(StandardCharsets.UTF_8);

    private final RecordingGateway gateway = new RecordingGateway();
    private final ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
    private final AppProxyService service = new AppProxyService(config, gateway);

    @Test
    void readPathsAreForwardedToTheSelectedInstance() {
        ProxyResponse response = service.forward("app-2", "GET", "/lab/thread/state", new byte[0]);
        assertThat(response.status()).isEqualTo(200);
        assertThat(gateway.instance).isEqualTo("app-2");
        assertThat(gateway.method).isEqualTo("GET");
        assertThat(gateway.path).isEqualTo("/lab/thread/state");
        assertThat(gateway.body).isNull();

        service.forward("app-3", "get", "/lab/thread/lifecycle", new byte[0]);
        assertThat(gateway.instance).isEqualTo("app-3");
        assertThat(gateway.method).isEqualTo("GET");
        assertThat(gateway.path).isEqualTo("/lab/thread/lifecycle");
    }

    @ParameterizedTest
    @CsvSource({
            "/lab/thread/stop-all",
            "/lab/thread/lifecycle/start",
            "/lab/thread/lifecycle/step",
            "/lab/thread/lifecycle/freeze",
            "/lab/thread/lifecycle/resume",
            "/lab/thread/lifecycle/reset",
            "/lab/thread/snapshot"
    })
    void bodylessPostPathsAreForwardedWithoutABody(String path) {
        service.forward("app-1", "POST", path, new byte[0]);
        assertThat(gateway.method).isEqualTo("POST");
        assertThat(gateway.path).isEqualTo(path);
        assertThat(gateway.body).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "deadlock,stop",
            "stuck,pause",
            "cpu-burn,resume",
            "heap-leak,stop",
            "oom,stop",
            "thread-leak,pause",
            "starvation,resume"
    })
    void everyDocumentedFaultModeAndActionIsForwarded(String mode, String action) {
        service.forward("app-1", "POST", "/lab/thread/fault/" + mode + "/" + action, new byte[0]);
        assertThat(gateway.path).isEqualTo("/lab/thread/fault/" + mode + "/" + action);
    }

    @Test
    void faultBodyIsForwardedVerbatim() {
        service.forward("app-1", "POST", "/lab/thread/fault", FAULT_BODY);
        assertThat(gateway.body).isEqualTo(FAULT_BODY);
    }

    @Test
    void bodiesOnOtherPostPathsAreDropped() {
        service.forward("app-1", "POST", "/lab/thread/stop-all", FAULT_BODY);
        assertThat(gateway.body).isNull();
    }

    @Test
    void unknownInstanceIsRejectedBeforeTheGateway() {
        assertThatThrownBy(() -> service.forward("app-9", "GET", "/lab/thread/state", new byte[0]))
                .isInstanceOf(UnknownAppInstanceException.class)
                .hasMessageContaining("app-9")
                .hasMessageContaining("app-1");
        assertThat(gateway.path).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/lab/thread/fault",
            "GET,/lab/thread/stop-all",
            "POST,/lab/thread/state",
            "POST,/lab/thread/lifecycle",
            "GET,/lab/toggles",
            "GET,/lab/thread/../toggles",
            "GET,/lab/thread/state/",
            "GET,//lab/thread/state",
            "GET,/lab/thread/state?tail=1",
            "POST,/lab/thread/fault/deadlock/explode",
            "POST,/lab/thread/fault/unknown/stop",
            "POST,/lab/thread/fault/deadlock/stop/extra",
            "POST,/lab/thread/fault/deadlock",
            "PUT,/lab/thread/state"
    })
    void offListMethodPathPairsAreRejected(String method, String path) {
        assertThatThrownBy(() -> service.forward("app-1", method, path, new byte[0]))
                .isInstanceOf(AppPathNotAllowedException.class)
                .hasMessageContaining("allow-list");
        assertThat(gateway.path).isNull();
    }

    @Test
    void emptyPathIsRejected() {
        assertThatThrownBy(() -> service.forward("app-1", "GET", "", new byte[0]))
                .isInstanceOf(AppPathNotAllowedException.class);
    }

    @Test
    void oversizedBodyIsRejectedBeforeTheGateway() {
        config.setMaxRequestBytes(8);
        assertThatThrownBy(() -> service.forward("app-1", "POST", "/lab/thread/fault", FAULT_BODY))
                .isInstanceOf(InvalidAppProxyRequestException.class)
                .hasMessageContaining("at most 8 bytes");
        assertThat(gateway.path).isNull();
        assertThat(service.maxRequestBytes()).isEqualTo(8);
    }

    private static final class RecordingGateway implements AppProxyGateway {

        private String instance;
        private String method;
        private String path;
        private byte[] body;

        @Override
        public ProxyResponse exchange(String instance, String method, String path, byte[] body) {
            this.instance = instance;
            this.method = method;
            this.path = path;
            this.body = body;
            return new ProxyResponse(200, "{\"code\":\"0\",\"message\":\"OK\",\"data\":null}");
        }
    }
}
