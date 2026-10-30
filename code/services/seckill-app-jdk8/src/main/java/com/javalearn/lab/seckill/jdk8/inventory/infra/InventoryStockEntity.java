package com.javalearn.lab.seckill.jdk8.inventory.infra;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * JPA mapping for {@code inventory_stock}. The primary key is the product id;
 * the demo schema and the JDK 21 shell's Flyway migration own the table and its
 * {@code available >= 0} check constraint.
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
