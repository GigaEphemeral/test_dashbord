package com.javalearn.lab.seckill.order.api;

/**
 * Input for order creation. Validation belongs to the use case, not to the
 * transport; the idempotency key is required by the use case even though the
 * HTTP binding declares the header optional so a {@code Result} envelope can
 * be returned for a missing header.
 */
public final class CreateOrderCommand {

    private final String userId;
    private final long productId;
    private final int quantity;
    private final String idempotencyKey;

    public CreateOrderCommand(String userId, long productId, int quantity, String idempotencyKey) {
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
