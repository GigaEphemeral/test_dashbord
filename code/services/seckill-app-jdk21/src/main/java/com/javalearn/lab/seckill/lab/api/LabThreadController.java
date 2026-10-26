package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.lab.thread.LifecycleDemoService;
import com.javalearn.lab.seckill.lab.thread.ThreadFaultService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Internal lab API of the fault-thread lab (design §9.1, Task M5-1) and the
 * single-thread lifecycle demo (design §9.2, Task #33).
 *
 * <p>Token-guarded by {@code LabTokenFilter} like every other {@code /lab/**}
 * path; no extra auth is added here. Responses are {@link ThreadActionView}s and
 * {@link LifecycleDemoView}s that are safe to render in the console (Task #34).
 * The lifecycle demo lives under {@code /lab/thread/lifecycle/**} so it cannot
 * be confused with a fault of a mode named {@code lifecycle}.</p>
 */
@RestController
@RequestMapping("/lab/thread")
public class LabThreadController {

    private final ThreadFaultService service;
    private final LifecycleDemoService lifecycle;

    public LabThreadController(ThreadFaultService service, LifecycleDemoService lifecycle) {
        this.service = Objects.requireNonNull(service, "service");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @GetMapping("/state")
    public Result<List<ThreadActionView>> state() {
        return Result.success(service.list());
    }

    @PostMapping("/fault")
    public Result<ThreadActionView> fault(@RequestBody(required = false) FaultStartRequest request) {
        if (request == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        return Result.success(service.start(request.mode(), request.hold(),
                request.ttlSeconds(), request.virtual()));
    }

    @PostMapping("/fault/{mode}/stop")
    public Result<ThreadActionView> stop(@PathVariable("mode") String mode) {
        return Result.success(service.stop(mode));
    }

    @PostMapping("/fault/{mode}/pause")
    public Result<ThreadActionView> pause(@PathVariable("mode") String mode) {
        return Result.success(service.pause(mode));
    }

    @PostMapping("/fault/{mode}/resume")
    public Result<ThreadActionView> resume(@PathVariable("mode") String mode) {
        return Result.success(service.resume(mode));
    }

    @PostMapping("/stop-all")
    public Result<List<ThreadActionView>> stopAll() {
        return Result.success(service.stopAll());
    }

    // ------------------------------------------------- lifecycle demo (§9.2)

    /** Real state of the single demo thread (0 NEW … 5 TERMINATED) plus freeze/step. */
    @GetMapping("/lifecycle")
    public Result<LifecycleDemoView> lifecycleState() {
        return Result.success(lifecycle.view());
    }

    @PostMapping("/lifecycle/start")
    public Result<LifecycleDemoView> lifecycleStart() {
        return Result.success(lifecycle.start());
    }

    @PostMapping("/lifecycle/step")
    public Result<LifecycleDemoView> lifecycleStep() {
        return Result.success(lifecycle.step());
    }

    /** Pauses advancement and requests a snapshot immediately; never freezes the JVM. */
    @PostMapping("/lifecycle/freeze")
    public Result<LifecycleDemoView> lifecycleFreeze() {
        return Result.success(lifecycle.freeze());
    }

    @PostMapping("/lifecycle/resume")
    public Result<LifecycleDemoView> lifecycleResume() {
        return Result.success(lifecycle.resume());
    }

    /** Tears the demo thread down and returns to NEW; legal from any started state. */
    @PostMapping("/lifecycle/reset")
    public Result<LifecycleDemoView> lifecycleReset() {
        return Result.success(lifecycle.reset());
    }
}
