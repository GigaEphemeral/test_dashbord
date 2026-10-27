package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.lab.config.CacheEffectiveConfigReader;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Internal runtime lab API. Every call is protected by {@code LabTokenFilter};
 * {@code GET /lab/config} and {@code GET /lab/toggles} never expose secrets.
 */
@RestController
@RequestMapping("/lab")
public class LabController {

    private final LabToggleService toggleService;
    private final LabInfoProvider infoProvider;
    private final CacheEffectiveConfigReader cacheConfigReader;

    public LabController(LabToggleService toggleService,
                         LabInfoProvider infoProvider,
                         CacheEffectiveConfigReader cacheConfigReader) {
        this.toggleService = Objects.requireNonNull(toggleService, "toggleService");
        this.infoProvider = Objects.requireNonNull(infoProvider, "infoProvider");
        this.cacheConfigReader = Objects.requireNonNull(cacheConfigReader, "cacheConfigReader");
    }

    @GetMapping("/toggles")
    public Result<Map<String, Boolean>> toggles() {
        return Result.success(toggleService.snapshot());
    }

    @PostMapping("/toggles")
    public Result<Map<String, Boolean>> update(@RequestBody(required = false) ToggleUpdateRequest request) {
        if (request == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        toggleService.update(request.key(), request.value());
        return Result.success(toggleService.snapshot());
    }

    /**
     * Identity, toggles and the effective cache configuration (B0-04): every
     * {@code app.lab.cache.*} key with the value THIS instance resolved, the env
     * variable that can override it, the declared default and the property
     * source that won. Secrets are never part of the view.
     */
    @GetMapping("/config")
    public Result<LabConfigResponse> config() {
        return Result.success(infoProvider.config(toggleService, cacheConfigReader.read()));
    }
}
