package com.javalearn.lab.seckill.jdk8.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.catalog.cache.TwoLevelCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
        if (!(cacheManager instanceof TwoLevelCacheManager)) {
            throw new IllegalStateException(
                    "LabCacheL1Controller requires the TwoLevelCacheManager bean");
        }
        this.cacheManager = (TwoLevelCacheManager) cacheManager;
        this.instance = instance == null || instance.trim().isEmpty() ? "unknown" : instance;
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
        if (!(request.getEnabled() instanceof Boolean)) {
            throw invalid("Field 'enabled' is required and must be a boolean");
        }
        Boolean enabled = (Boolean) request.getEnabled();
        long cleared = cacheManager.setL1Enabled(enabled.booleanValue());
        L1SwitchView view = view();
        return Result.success(new L1SwitchUpdateResult(view.isEnabled(), view.getCacheCount(),
                view.getCaches(), view.getInstance(), cleared));
    }

    private L1SwitchView view() {
        List<CacheEntry> caches = new ArrayList<>();
        for (TwoLevelCacheManager.CacheStatus status : cacheManager.cacheStatuses()) {
            caches.add(new CacheEntry(status.name(), status.size()));
        }
        return new L1SwitchView(cacheManager.isL1Enabled(), caches.size(), caches, instance);
    }

    private static BizException invalid(String message) {
        return new BizException(CommonErrorCode.INVALID_ARGUMENT, message);
    }

    /** Body of {@code POST /lab/cache/l1}; {@code Object} keeps non-booleans detectable. */
    public static class L1SwitchUpdateRequest {

        private Object enabled;

        public L1SwitchUpdateRequest() {
        }

        public L1SwitchUpdateRequest(Object enabled) {
            this.enabled = enabled;
        }

        public Object getEnabled() {
            return enabled;
        }

        public void setEnabled(Object enabled) {
            this.enabled = enabled;
        }
    }

    /** One managed cache and its current L1 entry count. */
    public static final class CacheEntry {

        private final String name;
        private final long size;

        public CacheEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }
    }

    /** {@code GET /lab/cache/l1} payload. */
    public static final class L1SwitchView {

        private final boolean enabled;
        private final int cacheCount;
        private final List<CacheEntry> caches;
        private final String instance;

        public L1SwitchView(boolean enabled, int cacheCount,
                            List<CacheEntry> caches, String instance) {
            this.enabled = enabled;
            this.cacheCount = cacheCount;
            this.caches = caches;
            this.instance = instance;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getCacheCount() {
            return cacheCount;
        }

        public List<CacheEntry> getCaches() {
            return caches;
        }

        public String getInstance() {
            return instance;
        }
    }

    /** {@code POST /lab/cache/l1} payload: the same view plus what the transition cleared. */
    public static final class L1SwitchUpdateResult {

        private final boolean enabled;
        private final int cacheCount;
        private final List<CacheEntry> caches;
        private final String instance;
        private final long clearedEntries;

        public L1SwitchUpdateResult(boolean enabled, int cacheCount,
                                    List<CacheEntry> caches, String instance,
                                    long clearedEntries) {
            this.enabled = enabled;
            this.cacheCount = cacheCount;
            this.caches = caches;
            this.instance = instance;
            this.clearedEntries = clearedEntries;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getCacheCount() {
            return cacheCount;
        }

        public List<CacheEntry> getCaches() {
            return caches;
        }

        public String getInstance() {
            return instance;
        }

        public long getClearedEntries() {
            return clearedEntries;
        }
    }
}
