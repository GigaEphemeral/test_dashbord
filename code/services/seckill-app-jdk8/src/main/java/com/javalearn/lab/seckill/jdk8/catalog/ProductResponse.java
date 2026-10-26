package com.javalearn.lab.seckill.jdk8.catalog;

import com.javalearn.lab.seckill.catalog.domain.Product;

import java.math.BigDecimal;

/**
 * HTTP representation of a catalog product. Field names match the JDK 21 shell's
 * {@code com.javalearn.lab.seckill.catalog.api.ProductResponse} record so one k6
 * script can scrape either runtime unchanged; Java 8 has no record, so this is a
 * plain read-only POJO.
 */
public final class ProductResponse {

    private final long id;
    private final String name;
    private final BigDecimal price;
    private final String status;
    private final String description;

    public ProductResponse(long id, String name, BigDecimal price, String status, String description) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.status = status;
        this.description = description;
    }

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStatus(),
                product.getDescription());
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }
}
