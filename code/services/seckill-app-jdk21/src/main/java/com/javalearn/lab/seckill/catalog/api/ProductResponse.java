package com.javalearn.lab.seckill.catalog.api;

import com.javalearn.lab.seckill.catalog.domain.Product;

import java.math.BigDecimal;

/**
 * HTTP representation of a catalog product.
 */
public record ProductResponse(
        long id,
        String name,
        BigDecimal price,
        String status,
        String description) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStatus(),
                product.getDescription());
    }
}
