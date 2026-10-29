package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.lab.cache.CacheExperimentDataService;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Internal lab API of the cache experiment (design appendix A). Token-guarded by
 * {@code LabTokenFilter} like every other {@code /lab/**} path; {@code seed} is
 * the only write here and it only touches the reserved experiment id range.
 */
@RestController
@RequestMapping("/lab/cache")
public class LabCacheController {

    private final CacheDefenseModeService modeService;
    private final CacheExperimentDataService experimentData;

    public LabCacheController(CacheDefenseModeService modeService,
                              CacheExperimentDataService experimentData) {
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.experimentData = Objects.requireNonNull(experimentData, "experimentData");
    }

    @GetMapping("/mode")
    public Result<CacheModeResponse> mode() {
        return Result.success(new CacheModeResponse(modeService.current().key()));
    }

    @PostMapping("/mode")
    public Result<CacheModeResponse> updateMode(
            @RequestBody(required = false) CacheModeUpdateRequest request) {
        if (request == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        modeService.update(request.mode());
        return Result.success(new CacheModeResponse(modeService.current().key()));
    }

    @PostMapping("/seed")
    public Result<CacheSeedResponse> seed(
            @RequestBody(required = false) CacheSeedRequest request) {
        return Result.success(experimentData.seed(request == null ? null : request.count()));
    }

    @GetMapping("/state")
    public Result<CacheStateResponse> state() {
        return Result.success(experimentData.state());
    }
}
