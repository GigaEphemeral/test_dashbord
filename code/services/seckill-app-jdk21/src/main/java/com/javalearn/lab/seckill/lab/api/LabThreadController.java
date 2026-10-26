package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
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
 * Internal lab API of the fault-thread lab (design §9.1, Task M5-1).
 *
 * <p>Token-guarded by {@code LabTokenFilter} like every other {@code /lab/**}
 * path; no extra auth is added here. Responses are {@link ThreadActionView}s that
 * are safe to render in the console (Task #34): mode, hold, state, started-at,
 * remaining TTL and thread ids only. This is the backend half of scenario 11;
 * the live-stack acceptance and the D6 dashboard belong to later tasks.</p>
 */
@RestController
@RequestMapping("/lab/thread")
public class LabThreadController {

    private final ThreadFaultService service;

    public LabThreadController(ThreadFaultService service) {
        this.service = Objects.requireNonNull(service, "service");
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
}
