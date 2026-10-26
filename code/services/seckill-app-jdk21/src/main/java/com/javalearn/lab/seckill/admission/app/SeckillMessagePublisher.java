package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;

/**
 * Publishes an accepted seckill request to RabbitMQ.
 */
public interface SeckillMessagePublisher {

    void publish(SeckillOrderMessage message);
}
