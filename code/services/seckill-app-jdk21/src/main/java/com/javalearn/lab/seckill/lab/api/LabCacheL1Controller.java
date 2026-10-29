package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B3-01 independent Caffeine L1 switch for THIS JVM.
 *
 * <p>The switch is deliberately separate from the {@code cache} lab toggle:
 * turning L1 off keeps L2/Redis in play and only bypasses the local Caffeine
 * layer. Disabling clears every local entry and returns how many were removed;
 * enabling starts from an empty L1. Repeating the current value is a no-op and
 * clears nothing. The app proxy already allow-lists both verbs, so the console
 * reaches this controller through the normal per-instance hop.</p>
 */
@RestController
@RequestMapping("/lab/cache/l1")
public class LabCacheL1Controller {

    private final TwoLevelCacheManager cacheManager;
    private final String instance;

    public LabCacheL1Controller(CacheManager cacheManager,
                                @Value("${observability.instance:unknown}") String instance) {
        if (!(cacheManager instanceof TwoLevelCacheManager twoLevel)) {
            throw new IllegalStateException(
                    "LabCacheL1Controller requires the TwoLevelCacheManager bean");
        }
        this.cacheManager = twoLevel;
        this.instance = instance == null || instance.isBlank() ? "unknown" : instance;
    }

    @GetMapping
    public Result<L1SwitchView> state() {
        return Result.success(view());
    }

    @PostMapping
    public Result<L1SwitchUpdateResult> update(
            @RequestBody(required = false) L1SwitchUpdateRequest request) {
        if (request == null) {
            throw invalid("Request body is required");
        }
        if (!(request.enabled() instanceof Boolean enabled)) {
            throw invalid("Field 'enabled' is required and must be a boolean");
        }
        long cleared = cacheManager.setL1Enabled(enabled);
        L1SwitchView view = view();
        return Result.success(new L1SwitchUpdateResult(view.enabled(), view.cacheCount(),
                view.caches(), view.instance(), cleared));
    }

    private L1SwitchView view() {
        List<CacheEntry> caches = cacheManager.cacheStatuses().stream()
                .map(status -> new CacheEntry(status.name(), status.size()))
                .toList();
        return new L1SwitchView(cacheManager.isL1Enabled(), caches.size(), caches, instance);
    }

    private static BizException invalid(String message) {
        return new BizException(CommonErrorCode.INVALID_ARGUMENT, message);
    }

    /** Body of {@code POST /lab/cache/l1}; {@code Object} keeps non-booleans detectable. */
    public record L1SwitchUpdateRequest(Object enabled) {
    }

    /** One managed cache and its current L1 entry count. */
    public record CacheEntry(String name, long size) {
    }

    /** {@code GET /lab/cache/l1} payload. */
    public record L1SwitchView(boolean enabled,
                               int cacheCount,
                               List<CacheEntry> caches,
                               String instance) {
    }

    /** {@code POST /lab/cache/l1} payload: the same view plus what the transition cleared. */
    public record L1SwitchUpdateResult(boolean enabled,
                                       int cacheCount,
                                       List<CacheEntry> caches,
                                       String instance,
                                       long clearedEntries) {
    }
}
