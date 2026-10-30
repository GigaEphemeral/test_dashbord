package com.javalearn.lab.seckill.jdk8.order.app;

/**
 * Caller scope of a durable order creation.
 *
 * <p>Task 18 counts {@code idempotent_reject} only for the synchronous
 * {@code POST /api/orders} path: a repeated {@code Idempotency-Key} that
 * returns an existing order there is the business signal the D4 panel shows.
 * The seckill admission fallback and the MQ consumer share the same durable
 * order code but are <em>not</em> part of that signal; their replays stay on
 * {@code seckill_attempts{result="replay"}} (admission) and
 * {@code order_consumer_processed{result="duplicate"}} (consumer).</p>
 */
public enum OrderRequestSource {

    /** Synchronous HTTP {@code POST /api/orders}; idempotent replays are metered. */
    SYNC_API,

    /** Seckill admission fallback or MQ consumer; idempotent replays are not metered here. */
    SECKILL
}
