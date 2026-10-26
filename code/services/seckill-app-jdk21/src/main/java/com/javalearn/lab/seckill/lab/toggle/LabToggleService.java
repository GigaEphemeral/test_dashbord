package com.javalearn.lab.seckill.lab.toggle;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory, per-instance runtime toggles.
 *
 * <p>Reads are lock-free: each protection owns an {@link AtomicBoolean} and the
 * map itself is never mutated after construction. The chaos-controller will
 * broadcast updates to all instances later; before that, a toggle update only
 * affects the instance that received the {@code POST /lab/toggles} call, which
 * is exactly what lets the lab compare one degraded instance against two
 * healthy ones.</p>
 */
public final class LabToggleService {

    private final Map<LabToggle, AtomicBoolean> states;

    public LabToggleService(LabToggleProperties properties) {
        Objects.requireNonNull(properties, "properties");
        Map<LabToggle, AtomicBoolean> initial = new EnumMap<>(LabToggle.class);
        initial.put(LabToggle.LOCK, new AtomicBoolean(required(properties.getLock(), "lock")));
        initial.put(LabToggle.IDEMPOTENCY,
                new AtomicBoolean(required(properties.getIdempotency(), "idempotency")));
        initial.put(LabToggle.MQ, new AtomicBoolean(required(properties.getMq(), "mq")));
        initial.put(LabToggle.RATELIMIT,
                new AtomicBoolean(required(properties.getRatelimit(), "ratelimit")));
        initial.put(LabToggle.CIRCUITBREAKER,
                new AtomicBoolean(required(properties.getCircuitbreaker(), "circuitbreaker")));
        initial.put(LabToggle.CACHE, new AtomicBoolean(required(properties.getCache(), "cache")));
        this.states = Collections.unmodifiableMap(initial);
    }

    public boolean isEnabled(LabToggle toggle) {
        return state(toggle).get();
    }

    /**
     * A stable, ordered snapshot for {@code GET /lab/toggles} and for metrics
     * or logs that need to prove which state an experiment ran with.
     */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> snapshot = new LinkedHashMap<>();
        for (LabToggle toggle : LabToggle.values()) {
            snapshot.put(toggle.key(), state(toggle).get());
        }
        return snapshot;
    }

    /**
     * Applies one toggle update.
     *
     * @throws BizException {@code 40001} for an unknown key or a missing value
     */
    public void update(String key, Boolean value) {
        if (key == null || key.trim().isEmpty()) {
            throw invalid("Toggle key is required");
        }
        LabToggle toggle = LabToggle.fromKey(key);
        if (toggle == null) {
            throw invalid("Unknown toggle: " + key);
        }
        if (value == null) {
            throw invalid("Toggle value is required for '" + key + "'");
        }
        state(toggle).set(value);
    }

    private AtomicBoolean state(LabToggle toggle) {
        AtomicBoolean state = states.get(toggle);
        if (state == null) {
            throw new IllegalArgumentException("Unsupported toggle: " + toggle);
        }
        return state;
    }

    private static boolean required(Boolean value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required configuration: app.toggles." + key);
        }
        return value;
    }

    private static BizException invalid(String message) {
        return new BizException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}
