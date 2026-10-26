package com.javalearn.lab.chaos.guide;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Interactive guide API. Protected by the shared {@code TokenFilter} because it
 * lives under {@code /lab/**}; every executed step still goes through the chaos
 * whitelist and TTL rules.
 */
@RestController
@RequestMapping("/lab/guide")
public class GuideController {

    private final GuideService guideService;

    public GuideController(GuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping("/scenarios")
    public Result<List<GuideScenarioSummary>> scenarios() {
        return Result.success(guideService.scenarios());
    }

    @GetMapping("/state")
    public Result<GuideStateView> state() {
        return Result.success(guideService.state());
    }

    @PostMapping("/start")
    public Result<GuideStateView> start(@RequestBody(required = false) GuideStartRequest request) {
        return Result.success(guideService.start(request == null ? null : request.scenarioId()));
    }

    @PostMapping("/step")
    public Result<GuideStateView> step() {
        return Result.success(guideService.step());
    }

    /** Advances past the current event without executing it. */
    @PostMapping("/skip")
    public Result<GuideStateView> skip() {
        return Result.success(guideService.skip());
    }

    @PostMapping("/pause")
    public Result<GuideStateView> pause() {
        return Result.success(guideService.pause());
    }

    @PostMapping("/resume")
    public Result<GuideStateView> resume() {
        return Result.success(guideService.resume());
    }

    @PostMapping("/reset")
    public Result<GuideStateView> reset() {
        return Result.success(guideService.reset());
    }
}
