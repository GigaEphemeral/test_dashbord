package com.javalearn.lab.chaos.toxiproxy;

import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToxiproxyServiceTest {

    private ChaosProperties properties;
    private FakeGateway gateway;
    private SimpleMeterRegistry registry;
    private ToxiproxyService service;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.getToxiproxy().setProxies(List.of("redis", "mysql"));
        gateway = new FakeGateway();
        registry = new SimpleMeterRegistry();
        service = new ToxiproxyService(gateway, properties, registry);
    }

    @Test
    void latencyWithoutParamsUsesConfiguredDefaults() {
        ToxicView view = service.addToxic("redis", new ToxicRequest("latency", null, null));

        assertThat(gateway.lastSpec).isInstanceOf(ToxicSpec.Latency.class);
        ToxicSpec.Latency latency = (ToxicSpec.Latency) gateway.lastSpec;
        assertThat(latency.name()).isEqualTo("latency");
        assertThat(latency.latencyMs()).isEqualTo(500);
        assertThat(latency.jitterMs()).isEqualTo(50);
        assertThat(view.type()).isEqualTo("latency");
        assertThat(gateway.calls).containsExactly("add:redis");
        assertThat(metric(ToxiproxyService.ACTION_ADD, "redis", "accepted")).isEqualTo(1.0);
    }

    @Test
    void bandwidthAndTimeoutUseTheirTypedSpecs() {
        service.addToxic("redis", new ToxicRequest("bandwidth", "bw", Map.of("rateKbps", 64)));
        service.addToxic("mysql", new ToxicRequest("timeout", "cut", Map.of("timeoutMs", 250)));

        assertThat(gateway.calls).containsExactly("add:redis", "add:mysql");
        assertThat(gateway.lastSpec).isInstanceOf(ToxicSpec.Timeout.class);
        assertThat(((ToxicSpec.Timeout) gateway.lastSpec).timeoutMs()).isEqualTo(250);
        assertThat(((ToxicSpec.Timeout) gateway.lastSpec).name()).isEqualTo("cut");
        assertThat(metric(ToxiproxyService.ACTION_ADD, "mysql", "accepted")).isEqualTo(1.0);
    }

    @Test
    void unknownToxicTypeIsRejectedBeforeAnyGatewayCall() {
        assertThatThrownBy(() -> service.addToxic("redis", new ToxicRequest("explode", null, null)))
                .isInstanceOf(InvalidToxiproxyRequestException.class)
                .hasMessageContaining("latency|bandwidth|timeout");

        assertThat(gateway.calls).isEmpty();
        assertThat(metric(ToxiproxyService.ACTION_ADD, "redis", "rejected")).isEqualTo(1.0);
    }

    @Test
    void unknownParameterIsRejectedSoTyposCannotSilentlyApplyADifferentFault() {
        assertThatThrownBy(() -> service.addToxic("redis",
                new ToxicRequest("latency", null, Map.of("latency", 500))))
                .isInstanceOf(InvalidToxiproxyRequestException.class)
                .hasMessageContaining("Unsupported toxic parameter 'latency'");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void outOfBoundsValueIsRejectedWithTheConfiguredBound() {
        assertThatThrownBy(() -> service.addToxic("redis",
                new ToxicRequest("latency", null, Map.of("latencyMs", 99_999))))
                .isInstanceOf(InvalidToxiproxyRequestException.class)
                .hasMessageContaining("between 0 and 30000");

        assertThat(gateway.calls).isEmpty();
        assertThat(metric(ToxiproxyService.ACTION_ADD, "redis", "rejected")).isEqualTo(1.0);
    }

    @Test
    void proxyOutsideTheAllowListIsNotFoundAndCountedUnderUnknown() {
        assertThatThrownBy(() -> service.addToxic("postgres", new ToxicRequest("latency", null, null)))
                .isInstanceOf(ToxiproxyNotFoundException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("redis");

        assertThat(gateway.calls).isEmpty();
        assertThat(metric(ToxiproxyService.ACTION_ADD, "unknown", "rejected")).isEqualTo(1.0);
    }

    @Test
    void disabledLayerReturns503AndNeverCallsTheGateway() {
        properties.getToxiproxy().setEnabled(false);

        assertThatThrownBy(() -> service.addToxic("redis", new ToxicRequest("latency", null, null)))
                .isInstanceOf(ToxiproxyUnavailableException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.state())
                .isInstanceOf(ToxiproxyUnavailableException.class)
                .hasMessageContaining("disabled");

        assertThat(gateway.calls).isEmpty();
        assertThat(metric(ToxiproxyService.ACTION_ADD, "unknown", "rejected")).isEqualTo(1.0);
    }

    @Test
    void removeForwardsTheToxicName() {
        service.removeToxic("redis", "latency");

        assertThat(gateway.calls).containsExactly("remove:redis:latency");
        assertThat(metric(ToxiproxyService.ACTION_REMOVE, "redis", "accepted")).isEqualTo(1.0);
    }

    @Test
    void removeOfAMissingToxicIsRejected() {
        gateway.failures.put("remove", new ToxiproxyNotFoundException("toxic gone"));

        assertThatThrownBy(() -> service.removeToxic("redis", "latency"))
                .isInstanceOf(ToxiproxyNotFoundException.class);
        assertThat(metric(ToxiproxyService.ACTION_REMOVE, "redis", "rejected")).isEqualTo(1.0);
    }

    @Test
    void disableAndEnableForwardTheFlag() {
        ProxyEnabledView disabled = service.setEnabled("mysql", false);
        ProxyEnabledView enabled = service.setEnabled("mysql", true);

        assertThat(disabled.enabled()).isFalse();
        assertThat(enabled.enabled()).isTrue();
        assertThat(gateway.calls).containsExactly("disable:mysql", "enable:mysql");
        assertThat(metric(ToxiproxyService.ACTION_DISABLE, "mysql", "accepted")).isEqualTo(1.0);
        assertThat(metric(ToxiproxyService.ACTION_ENABLE, "mysql", "accepted")).isEqualTo(1.0);
    }

    @Test
    void resetRemovesToxicsAndReenablesTheProxy() {
        gateway.toxicsToRemove = 3;

        ProxyResetView view = service.reset("redis");

        assertThat(view.proxy()).isEqualTo("redis");
        assertThat(view.toxicsRemoved()).isEqualTo(3);
        assertThat(view.enabled()).isTrue();
        assertThat(gateway.calls).containsExactly("reset:redis", "enable:redis");
        assertThat(metric(ToxiproxyService.ACTION_RESET, "redis", "accepted")).isEqualTo(1.0);
    }

    @Test
    void stateReportsAConfiguredProxyMissingFromToxiproxy() {
        gateway.toxics.put("redis", List.of(new ToxicView("latency", "latency",
                Map.of("latency", 500, "jitter", 50), 1.0)));
        gateway.missingProxies.add("mysql");

        ToxiproxyStateView state = service.state();

        assertThat(state.enabled()).isTrue();
        assertThat(state.allowedProxies()).containsExactly("redis", "mysql");
        assertThat(state.proxies()).hasSize(2);
        assertThat(state.proxies().get(0).present()).isTrue();
        assertThat(state.proxies().get(0).enabled()).isTrue();
        assertThat(state.proxies().get(0).toxics()).hasSize(1);
        assertThat(state.proxies().get(1).present()).isFalse();
    }

    @Test
    void stateFailsWhenTheControlPlaneIsUnreachable() {
        gateway.failures.put("get", new ToxiproxyUnavailableException("connection refused"));

        assertThatThrownBy(() -> service.state())
                .isInstanceOf(ToxiproxyUnavailableException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void resetAllRestoresEveryConfiguredProxyAndCountsEachOne() {
        gateway.toxicsToRemove = 2;

        NetworkResetReport report = service.resetAll();

        assertThat(report.status()).isEqualTo(NetworkResetReport.STATUS_RESET);
        assertThat(report.proxiesReset()).isEqualTo(2);
        assertThat(report.proxiesTotal()).isEqualTo(2);
        assertThat(report.errors()).isEmpty();
        assertThat(gateway.calls).containsExactly(
                "reset:redis", "enable:redis", "reset:mysql", "enable:mysql");
        assertThat(metric(ToxiproxyService.ACTION_RESET_ALL, "redis", "accepted")).isEqualTo(1.0);
        assertThat(metric(ToxiproxyService.ACTION_RESET_ALL, "mysql", "accepted")).isEqualTo(1.0);
    }

    @Test
    void resetAllReportsPartialFailureButKeepsGoing() {
        gateway.failures.put("reset:mysql", new ToxiproxyNotFoundException("proxy not configured"));

        NetworkResetReport report = service.resetAll();

        assertThat(report.status()).isEqualTo(NetworkResetReport.STATUS_PARTIAL);
        assertThat(report.proxiesReset()).isEqualTo(1);
        assertThat(report.errors()).hasSize(1);
        assertThat(report.errors().get(0)).contains("mysql");
        assertThat(metric(ToxiproxyService.ACTION_RESET_ALL, "redis", "accepted")).isEqualTo(1.0);
        assertThat(metric(ToxiproxyService.ACTION_RESET_ALL, "mysql", "failed")).isEqualTo(1.0);
    }

    @Test
    void resetAllStopsTryingOnceTheControlPlaneIsUnreachable() {
        gateway.failures.put("reset:redis", new ToxiproxyUnavailableException("connection refused"));

        NetworkResetReport report = service.resetAll();

        assertThat(report.status()).isEqualTo(NetworkResetReport.STATUS_FAILED);
        assertThat(report.proxiesReset()).isZero();
        assertThat(report.errors()).hasSize(2);
        assertThat(report.errors().get(1)).contains("not attempted");
        assertThat(gateway.calls).containsExactly("reset:redis");
    }

    @Test
    void resetAllIsANoOpWhenTheLayerIsDisabled() {
        properties.getToxiproxy().setEnabled(false);

        NetworkResetReport report = service.resetAll();

        assertThat(report.status()).isEqualTo(NetworkResetReport.STATUS_DISABLED);
        assertThat(report.proxiesReset()).isZero();
        assertThat(gateway.calls).isEmpty();
    }

    private double metric(String action, String target, String result) {
        Counter counter = registry.find("chaos.actions")
                .tags("action", action, "target", target, "result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    static final class FakeGateway implements ToxiproxyGateway {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, RuntimeException> failures = new HashMap<>();
        final Set<String> missingProxies = new HashSet<>();
        final Map<String, List<ToxicView>> toxics = new LinkedHashMap<>();
        ToxicSpec lastSpec;
        int toxicsToRemove = 1;

        @Override
        public ToxicView addToxic(String proxy, ToxicSpec toxic) {
            calls.add("add:" + proxy);
            lastSpec = toxic;
            maybeFail("add");
            return new ToxicView(toxic.name(), toxic.type().wireName(),
                    new LinkedHashMap<>(toxic.attributes()), 1.0);
        }

        @Override
        public void removeToxic(String proxy, String toxicName) {
            calls.add("remove:" + proxy + ":" + toxicName);
            maybeFail("remove");
        }

        @Override
        public int resetProxy(String proxy) {
            calls.add("reset:" + proxy);
            maybeFail("reset:" + proxy);
            maybeFail("reset");
            return toxicsToRemove;
        }

        @Override
        public void setProxyEnabled(String proxy, boolean enabled) {
            calls.add((enabled ? "enable:" : "disable:") + proxy);
            maybeFail(enabled ? "enable:" + proxy : "disable:" + proxy);
            maybeFail(enabled ? "enable" : "disable");
        }

        @Override
        public List<ToxicView> listToxics(String proxy) {
            calls.add("list:" + proxy);
            maybeFail("list");
            return toxics.getOrDefault(proxy, List.of());
        }

        @Override
        public ProxyView getProxy(String proxy) {
            calls.add("get:" + proxy);
            if (missingProxies.contains(proxy)) {
                throw new ToxiproxyNotFoundException("proxy '" + proxy + "' not found");
            }
            maybeFail("get:" + proxy);
            maybeFail("get");
            return new ProxyView(proxy, true, toxics.getOrDefault(proxy, List.of()));
        }

        private void maybeFail(String key) {
            RuntimeException failure = failures.get(key);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
