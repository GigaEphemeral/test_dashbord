package com.javalearn.lab.seckill.jdk8.lab.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.lab.cache.preheat.CachePreheatService;
import com.javalearn.lab.seckill.jdk8.lab.cache.preheat.PreheatTrigger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Internal lab API of the Caffeine preheat mechanism (M4-3, design 8.4).
 * Token-guarded by {@code LabTokenFilter} like every other {@code /lab/**}
 * path. GET is read-only; POST runs one manual preheat synchronously on the
 * calling thread and returns the post-run snapshot (small counts, no secrets).
 */
@RestController
@RequestMapping("/lab/cache/preheat")
public class LabCachePreheatController {

    private final CachePreheatService preheatService;

    public LabCachePreheatController(CachePreheatService preheatService) {
        this.preheatService = Objects.requireNonNull(preheatService, "preheatService");
    }

    @GetMapping
    public Result<CachePreheatStateResponse> state() {
        return Result.success(preheatService.state());
    }

    @PostMapping
    public Result<CachePreheatStateResponse> run() {
        preheatService.run(PreheatTrigger.MANUAL);
        return Result.success(preheatService.state());
    }
}
