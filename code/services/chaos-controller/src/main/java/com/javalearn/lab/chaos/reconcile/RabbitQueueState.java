package com.javalearn.lab.chaos.reconcile;

/** One management API answer for {@code lab.reconcile.mq-queue}. */
public record RabbitQueueState(long messagesReady, long messagesUnacknowledged, long consumers) {
}
