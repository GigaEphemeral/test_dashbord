package com.javalearn.lab.chaos.toxiproxy;

import com.javalearn.lab.chaos.action.NetworkFaultCleaner;
import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies Toxiproxy network faults and reports them to the lab console.
 *
 * <p>This service owns validation and the safety boundary: a request can only
 * name a proxy from {@code chaos.toxiproxy.proxies}, every numeric parameter is
 * bounded by configuration, and a disabled layer answers 503/50301 before any
 * socket is opened. The {@link ToxiproxyGateway} owns HTTP details.</p>
 *
 * <p>Faults have no TTL and no watchdog. Unlike container actions, a toxic is
 * invisible to the Docker daemon, so an automatic revert cannot be inferred
 * from container state; the demo model is "apply, observe, reset". Stop-all
 * still restores the network through {@link #resetAll()}, which is also exposed
 * as {@code POST /chaos/toxiproxy/reset-all} for a network-only recovery.</p>
 *
 * <p>Mutating calls increment the existing {@code chaos.actions} meter with
 * {@code action=toxiproxy-*} (see the ACTION_* constants).</p>
 */
@Service
public class ToxiproxyService implements NetworkFaultCleaner {

    private static final Logger log = LoggerFactory.getLogger(ToxiproxyService.class);
    private static final Pattern TOXIC_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    /** Meter action tags; exported as chaos_actions_total{action="..."}. */
    public static final String ACTION_ADD = "toxiproxy-add";
    public static final String ACTION_REMOVE = "toxiproxy-remove";
    public static final String ACTION_RESET = "toxiproxy-reset";
    public static final String ACTION_DISABLE = "toxiproxy-disable";
    public static final String ACTION_ENABLE = "toxiproxy-enable";
    public static final String ACTION_RESET_ALL = "toxiproxy-reset-all";

    private static final String RESULT_ACCEPTED = "accepted";
    private static final String RESULT_REJECTED = "rejected";
    private static final String RESULT_FAILED = "failed";
    private static final String UNKNOWN_TARGET = "unknown";

    private final ToxiproxyGateway gateway;
    private final ChaosProperties.Toxiproxy config;
    private final MeterRegistry meterRegistry;
    private final List<String> allowedProxies;
    private final Set<String> allowedLookup;

    public ToxiproxyService(ToxiproxyGateway gateway,
                            ChaosProperties properties,
                            MeterRegistry meterRegistry) {
        this.gateway = gateway;
        this.config = properties.getToxiproxy();
        this.meterRegistry = meterRegistry;
        this.allowedProxies = List.copyOf(config.getProxies() == null ? List.of() : config.getProxies());
        this.allowedLookup = new LinkedHashSet<>(allowedProxies);
    }

    /** Create one toxic on a configured proxy. */
    public ToxicView addToxic(String proxy, ToxicRequest request) {
        String target = requireTarget(proxy, ACTION_ADD);
        ToxicSpec spec;
        try {
            spec = buildSpec(request);
        } catch (RuntimeException e) {
            count(ACTION_ADD, target, RESULT_REJECTED);
            throw e;
        }
        try {
            ToxicView view = gateway.addToxic(target, spec);
            count(ACTION_ADD, target, RESULT_ACCEPTED);
            log.info("Toxiproxy {} '{}' added to proxy {}", spec.type().wireName(), spec.name(), target);
            return view;
        } catch (RuntimeException e) {
            count(ACTION_ADD, target, resultOf(e));
            throw e;
        }
    }

    /** Remove one toxic by name. */
    public void removeToxic(String proxy, String toxicName) {
        String target = requireTarget(proxy, ACTION_REMOVE);
        String name = requireToxicName(toxicName);
        try {
            gateway.removeToxic(target, name);
            count(ACTION_REMOVE, target, RESULT_ACCEPTED);
            log.info("Toxiproxy toxic '{}' removed from proxy {}", name, target);
        } catch (RuntimeException e) {
            count(ACTION_REMOVE, target, resultOf(e));
            throw e;
        }
    }

    /**
     * Full restore of one proxy: remove every toxic and enable it. One call is
     * enough to recover from either the toxic model or the disable model.
     */
    public ProxyResetView reset(String proxy) {
        String target = requireTarget(proxy, ACTION_RESET);
        try {
            int removed = gateway.resetProxy(target);
            gateway.setProxyEnabled(target, true);
            count(ACTION_RESET, target, RESULT_ACCEPTED);
            log.info("Toxiproxy proxy {} reset: {} toxic(s) removed, proxy enabled", target, removed);
            return new ProxyResetView(target, removed, true);
        } catch (RuntimeException e) {
            count(ACTION_RESET, target, resultOf(e));
            throw e;
        }
    }

    /** 断网/恢复: disable or enable one proxy without touching its toxics. */
    public ProxyEnabledView setEnabled(String proxy, boolean enabled) {
        String action = enabled ? ACTION_ENABLE : ACTION_DISABLE;
        String target = requireTarget(proxy, action);
        try {
            gateway.setProxyEnabled(target, enabled);
            count(action, target, RESULT_ACCEPTED);
            log.info("Toxiproxy proxy {} {}", target, enabled ? "enabled" : "disabled");
            return new ProxyEnabledView(target, enabled);
        } catch (RuntimeException e) {
            count(action, target, resultOf(e));
            throw e;
        }
    }

    /**
     * Console snapshot: one row per configured proxy plus its active toxics.
     * A proxy missing from Toxiproxy is reported as {@code present=false};
     * an unreachable control plane fails the whole call with 503/50301.
     */
    public ToxiproxyStateView state() {
        requireEnabled();
        List<ProxyStateView> proxies = new ArrayList<>();
        for (String proxy : allowedProxies) {
            try {
                ProxyView view = gateway.getProxy(proxy);
                proxies.add(new ProxyStateView(proxy, true, view.enabled(), view.toxics()));
            } catch (ToxiproxyNotFoundException e) {
                proxies.add(ProxyStateView.missing(proxy));
            }
        }
        return new ToxiproxyStateView(config.isEnabled(), allowedProxies, proxies);
    }

    /**
     * Restore every configured proxy (remove toxics + enable). Never throws:
     * this is also the stop-all hook, where container reverts must not be lost
     * because the network cleanup failed. An unreachable control plane stops
     * further attempts so stop-all is not delayed once per proxy.
     */
    @Override
    public NetworkResetReport resetAll() {
        if (!config.isEnabled()) {
            return NetworkResetReport.disabled();
        }
        List<String> errors = new ArrayList<>();
        int reset = 0;
        boolean unreachable = false;
        for (String proxy : allowedProxies) {
            if (unreachable) {
                errors.add(proxy + ": not attempted (Toxiproxy unreachable)");
                count(ACTION_RESET_ALL, proxy, RESULT_FAILED);
                continue;
            }
            try {
                gateway.resetProxy(proxy);
                gateway.setProxyEnabled(proxy, true);
                reset++;
                count(ACTION_RESET_ALL, proxy, RESULT_ACCEPTED);
            } catch (ToxiproxyUnavailableException e) {
                unreachable = true;
                errors.add(proxy + ": " + e.getMessage());
                count(ACTION_RESET_ALL, proxy, RESULT_FAILED);
                log.warn("Toxiproxy reset-all: control plane unreachable at proxy {}: {}", proxy, e.getMessage());
            } catch (RuntimeException e) {
                errors.add(proxy + ": " + e.getMessage());
                count(ACTION_RESET_ALL, proxy, RESULT_FAILED);
                log.warn("Toxiproxy reset-all: proxy {} failed: {}", proxy, e.toString());
            }
        }
        String status;
        if (reset == allowedProxies.size()) {
            status = NetworkResetReport.STATUS_RESET;
        } else if (reset == 0) {
            status = NetworkResetReport.STATUS_FAILED;
        } else {
            status = NetworkResetReport.STATUS_PARTIAL;
        }
        if (NetworkResetReport.STATUS_RESET.equals(status)) {
            log.info("Toxiproxy reset-all: {} proxy(s) restored", reset);
        } else {
            log.warn("Toxiproxy reset-all: status={} restored={}/{} errors={}", status, reset,
                    allowedProxies.size(), errors);
        }
        return new NetworkResetReport(status, reset, allowedProxies.size(), errors);
    }

    private String requireTarget(String proxy, String action) {
        if (!config.isEnabled()) {
            count(action, UNKNOWN_TARGET, RESULT_REJECTED);
            throw new ToxiproxyUnavailableException(
                    "Toxiproxy support is disabled (chaos.toxiproxy.enabled=false); "
                            + "enable it and the chaos-proxy compose profile first");
        }
        String target = proxy == null ? "" : proxy.trim();
        if (!allowedLookup.contains(target)) {
            count(action, UNKNOWN_TARGET, RESULT_REJECTED);
            throw new ToxiproxyNotFoundException(
                    "Unknown toxiproxy proxy '" + target + "' (allowed: " + allowedProxies + ")");
        }
        return target;
    }

    private void requireEnabled() {
        if (!config.isEnabled()) {
            throw new ToxiproxyUnavailableException(
                    "Toxiproxy support is disabled (chaos.toxiproxy.enabled=false); "
                            + "enable it and the chaos-proxy compose profile first");
        }
    }

    private ToxicSpec buildSpec(ToxicRequest request) {
        ToxicType type = ToxicType.parse(request == null ? null : request.type());
        Map<String, Integer> params = request == null ? Map.of() : request.paramsOrEmpty();
        String name = toxicName(request, type);
        return switch (type) {
            case LATENCY -> {
                rejectUnknown(params, Set.of("latencyMs", "jitterMs"));
                int latency = intParam(params, "latencyMs", config.getDefaultLatencyMs());
                int jitter = intParam(params, "jitterMs", config.getDefaultJitterMs());
                requireRange("latencyMs", latency, 0, config.getMaxLatencyMs());
                requireRange("jitterMs", jitter, 0, config.getMaxJitterMs());
                yield new ToxicSpec.Latency(name, latency, jitter);
            }
            case BANDWIDTH -> {
                rejectUnknown(params, Set.of("rateKbps"));
                int rate = intParam(params, "rateKbps", config.getDefaultBandwidthKbps());
                requireRange("rateKbps", rate, 1, config.getMaxBandwidthKbps());
                yield new ToxicSpec.Bandwidth(name, rate);
            }
            case TIMEOUT -> {
                rejectUnknown(params, Set.of("timeoutMs"));
                int timeout = intParam(params, "timeoutMs", config.getDefaultTimeoutMs());
                requireRange("timeoutMs", timeout, 1, config.getMaxTimeoutMs());
                yield new ToxicSpec.Timeout(name, timeout);
            }
        };
    }

    private static String toxicName(ToxicRequest request, ToxicType type) {
        String raw = request == null ? null : request.name();
        if (raw == null || raw.isBlank()) {
            return type.wireName();
        }
        String name = raw.trim();
        if (!TOXIC_NAME.matcher(name).matches()) {
            throw new InvalidToxiproxyRequestException(
                    "toxic name must match " + TOXIC_NAME.pattern() + " (was '" + raw + "')");
        }
        return name;
    }

    private static String requireToxicName(String toxicName) {
        String name = toxicName == null ? "" : toxicName.trim();
        if (!TOXIC_NAME.matcher(name).matches()) {
            throw new InvalidToxiproxyRequestException(
                    "toxic name must match " + TOXIC_NAME.pattern() + " (was '" + toxicName + "')");
        }
        return name;
    }

    private static int intParam(Map<String, Integer> params, String key, int fallback) {
        if (!params.containsKey(key)) {
            return fallback;
        }
        Integer value = params.get(key);
        if (value == null) {
            throw new InvalidToxiproxyRequestException("toxic parameter '" + key + "' must not be null");
        }
        return value;
    }

    private static void requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidToxiproxyRequestException(
                    "toxic parameter '" + key + "' must be between " + min + " and " + max
                            + " (was " + value + "; see chaos.toxiproxy.* bounds)");
        }
    }

    private static void rejectUnknown(Map<String, Integer> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new InvalidToxiproxyRequestException(
                        "Unsupported toxic parameter '" + key + "' (allowed: " + allowed + ")");
            }
        }
    }

    private static String resultOf(RuntimeException e) {
        if (e instanceof InvalidToxiproxyRequestException
                || e instanceof ToxiproxyNotFoundException
                || e instanceof ToxiproxyConflictException) {
            return RESULT_REJECTED;
        }
        return RESULT_FAILED;
    }

    private void count(String action, String target, String result) {
        meterRegistry.counter("chaos.actions",
                        "action", action, "target", target, "result", result)
                .increment();
    }
}
