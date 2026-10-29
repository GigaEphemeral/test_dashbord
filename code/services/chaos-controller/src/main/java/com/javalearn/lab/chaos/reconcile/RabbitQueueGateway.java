package com.javalearn.lab.chaos.reconcile;

/** Read-only RabbitMQ management probe for one queue. */
public interface RabbitQueueGateway {

    /** @throws RuntimeException when the management API is unreachable or answers badly. */
    RabbitQueueState snapshot();
}
