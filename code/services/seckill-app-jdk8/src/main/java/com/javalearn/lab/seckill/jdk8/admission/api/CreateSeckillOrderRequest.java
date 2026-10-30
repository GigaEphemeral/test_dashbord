package com.javalearn.lab.seckill.jdk8.admission.api;

/**
 * HTTP body for {@code POST /api/seckill/orders}. Java 8 has no records, so
 * this is a bean with the same JSON field names as the JDK 21 record
 * ({@code userId}, {@code productId}, {@code quantity}). Field validation
 * belongs to the admission service so both the async and the synchronous
 * fallback path apply exactly the same rules as the core order use case.
 */
public class CreateSeckillOrderRequest {

    private String userId;
    private long productId;
    private int quantity;

    public CreateSeckillOrderRequest() {
    }

    public CreateSeckillOrderRequest(String userId, long productId, int quantity) {
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getProductId() {
        return productId;
    }

    public void setProductId(long productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
