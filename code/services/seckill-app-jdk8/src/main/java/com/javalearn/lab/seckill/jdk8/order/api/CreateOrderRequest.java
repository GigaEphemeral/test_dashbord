package com.javalearn.lab.seckill.jdk8.order.api;

/**
 * HTTP request body for {@code POST /api/orders}. Java 8 has no records, so
 * this is a bean with the same JSON field names as the JDK 21 record
 * ({@code userId}, {@code productId}, {@code quantity}). Field validation
 * belongs to the core use case so the same rules apply to the seckill endpoint.
 */
public class CreateOrderRequest {

    private String userId;
    private long productId;
    private int quantity;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String userId, long productId, int quantity) {
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
