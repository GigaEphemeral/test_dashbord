package com.javalearn.lab.seckill.inventory.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping for {@code inventory_stock}. The primary key is the product id;
 * Flyway owns the schema and the {@code available >= 0} check constraint.
 */
@Entity
@Table(name = "inventory_stock")
public class InventoryStockEntity {

    @Id
    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    protected InventoryStockEntity() {
    }

    public long getProductId() {
        return productId;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }
}
