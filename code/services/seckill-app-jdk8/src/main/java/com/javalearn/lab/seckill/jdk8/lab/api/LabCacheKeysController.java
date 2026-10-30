package com.javalearn.lab.seckill.jdk8.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.lab.cache.CacheExperimentDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * SCAN-only key browser, single-key detail and experiment reset (Stage2 B3-02).
 *
 * <p>Split from {@link LabCacheController} so the dataset/mode API stays small.
 * Both are {@code /lab/**} and therefore behind {@link com.javalearn.lab.seckill.lab.auth.LabTokenFilter}.
 * Prefix validation, limit caps, truncation and the destructive reset bounds
 * live in {@link CacheExperimentDataService}; this controller only carries the
 * wire shape.</p>
 */
@RestController
@RequestMapping("/lab/cache")
public class LabCacheKeysController {

    private final CacheExperimentDataService experimentData;

    public LabCacheKeysController(CacheExperimentDataService experimentData) {
        this.experimentData = Objects.requireNonNull(experimentData, "experimentData");
    }

    @GetMapping("/keys")
    public Result<CacheKeysPageResponse> keys(
            @RequestParam(name = "prefix", required = false) String prefix,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return Result.success(experimentData.scanKeys(prefix, cursor, limit));
    }

    @PostMapping("/key")
    public Result<CacheKeyDetailResponse> key(
            @RequestBody(required = false) KeyRequest request) {
        if (request == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        return Result.success(experimentData.keyDetail(request.getKey(), request.getProductId()));
    }

    @PostMapping("/reset")
    public Result<CacheExperimentResetResponse> reset(
            @RequestBody(required = false) ResetRequest request) {
        boolean includeBusiness = request != null
                && Boolean.TRUE.equals(request.getIncludeBusiness());
        return Result.success(experimentData.reset(includeBusiness));
    }

    /** Exactly one of {@code key} / {@code productId}; validated by the service. */
    public static class KeyRequest {

        private String key;
        private Long productId;

        public KeyRequest() {
        }

        public KeyRequest(String key, Long productId) {
            this.key = key;
            this.productId = productId;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }
    }

    /** Omitted body means {@code includeBusiness=false} (cache keys only). */
    public static class ResetRequest {

        private Boolean includeBusiness;

        public ResetRequest() {
        }

        public ResetRequest(Boolean includeBusiness) {
            this.includeBusiness = includeBusiness;
        }

        public Boolean getIncludeBusiness() {
            return includeBusiness;
        }

        public void setIncludeBusiness(Boolean includeBusiness) {
            this.includeBusiness = includeBusiness;
        }
    }
}
