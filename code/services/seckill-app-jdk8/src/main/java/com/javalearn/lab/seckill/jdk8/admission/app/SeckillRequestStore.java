package com.javalearn.lab.seckill.jdk8.admission.app;

import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillRequest;

import java.util.Optional;

/**
 * Redis-backed request state used by polling and idempotent replay.
 */
public interface SeckillRequestStore {

    void save(SeckillRequest request);

    Optional<SeckillRequest> find(String requestId);

    void markCreated(String requestId, long orderId);

    void markFailed(String requestId, String message);

    void delete(String requestId);
}
