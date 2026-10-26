package com.javalearn.lab.seckill.lab.toggle;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, per-instance penetration defense mode.
 *
 * <p>Same semantics as {@link LabToggleService}: the value is seeded from
 * configuration at startup, reads are lock-free and one instance can be switched
 * independently so the lab compares a protected instance against an unprotected
 * one. The chaos-controller will broadcast it later.</p>
 */
public final class CacheDefenseModeService {

    private final AtomicReference<CacheDefenseMode> current;

    public CacheDefenseModeService(LabCacheProperties properties) {
        Objects.requireNonNull(properties, "properties");
        CacheDefenseMode seeded = CacheDefenseMode.fromKey(properties.getPenetration().getMode());
        if (seeded == null) {
            throw new IllegalStateException(
                    "Unsupported app.lab.cache.penetration.mode: " + properties.getPenetration().getMode());
        }
        this.current = new AtomicReference<>(seeded);
    }

    public CacheDefenseMode current() {
        return current.get();
    }

    /**
     * Applies one mode update.
     *
     * @throws BizException {@code 40001} for an unknown or missing mode
     */
    public void update(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Mode is required");
        }
        CacheDefenseMode resolved = CacheDefenseMode.fromKey(mode);
        if (resolved == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Unknown mode: " + mode);
        }
        current.set(resolved);
    }
}
