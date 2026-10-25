package com.javalearn.lab.seckill.catalog.domain;

import java.math.BigDecimal;

/**
 * Catalog product. Framework-neutral: no annotations and only JDK 8 types.
 */
public final class Product {

    private final long id;
    private final String name;
    private final BigDecimal price;
    private final String status;
    private final String description;

    public Product(long id, String name, BigDecimal price, String status, String description) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.status = status;
        this.description = description;
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

    @Override
    public String toString() {
        return "Product{id=" + id + ", name='" + name + "', price=" + price
                + ", status='" + status + "'}";
    }
}
