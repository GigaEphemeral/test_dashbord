package com.javalearn.lab.seckill.jdk8.admission.app;

import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillOrderMessage;

/**
 * Publishes an accepted seckill request to RabbitMQ.
 */
public interface SeckillMessagePublisher {

    void publish(SeckillOrderMessage message);
}
