package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Chaos action API. Token-protected by {@code TokenFilter}; every target is
 * whitelisted by the gateway before any Docker call.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final ChaosActionService chaosActionService;

    public ChaosController(ChaosActionService chaosActionService) {
        this.chaosActionService = chaosActionService;
    }

    @PostMapping("/instances/{name}/actions")
    public ResponseEntity<Result<ChaosActionView>> apply(@PathVariable String name,
                                                         @RequestBody(required = false) ChaosActionRequest request) {
        ChaosActionView view = chaosActionService.execute(name, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(view));
    }

    @GetMapping("/actions")
    public Result<List<ChaosActionView>> actions() {
        return Result.success(chaosActionService.activeActions());
    }

    @PostMapping("/stop-all")
    public Result<StopAllResult> stopAll() {
        return Result.success(chaosActionService.stopAll());
    }
}
