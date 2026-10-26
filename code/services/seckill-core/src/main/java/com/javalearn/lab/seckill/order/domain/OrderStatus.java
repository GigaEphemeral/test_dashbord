package com.javalearn.lab.seckill.order.domain;

/**
 * Order lifecycle states. Only {@link #CREATED} exists in M1; later milestones
 * add payment and cancellation states.
 */
public enum OrderStatus {
    CREATED
}
