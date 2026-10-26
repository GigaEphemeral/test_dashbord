package com.javalearn.lab.seckill.admission.api;

import com.javalearn.lab.seckill.admission.domain.SeckillRequest;

/**
 * Polling view of an async seckill request.
 */
public record SeckillRequestView(
        String requestId,
        String status,
        Long orderId,
        String message) {

    public static SeckillRequestView from(SeckillRequest request) {
        return new SeckillRequestView(
                request.requestId(),
                request.status().name(),
                request.orderId(),
                request.message());
    }
}
