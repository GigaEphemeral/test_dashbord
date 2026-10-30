package com.javalearn.lab.seckill.jdk8.admission.api;

import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillRequest;

/**
 * Polling view of an async seckill request. Same JSON field names as the
 * JDK 21 record, which is what the k6 flow reads.
 */
public class SeckillRequestView {

    private final String requestId;
    private final String status;
    private final Long orderId;
    private final String message;

    public SeckillRequestView(String requestId, String status, Long orderId, String message) {
        this.requestId = requestId;
        this.status = status;
        this.orderId = orderId;
        this.message = message;
    }

    public static SeckillRequestView from(SeckillRequest request) {
        return new SeckillRequestView(
                request.getRequestId(),
                request.getStatus().name(),
                request.getOrderId(),
                request.getMessage());
    }

    public String getRequestId() {
        return requestId;
    }

    public String getStatus() {
        return status;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getMessage() {
        return message;
    }
}
