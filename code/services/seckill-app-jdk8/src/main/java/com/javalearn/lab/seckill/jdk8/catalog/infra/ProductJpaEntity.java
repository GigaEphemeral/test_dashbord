package com.javalearn.lab.seckill.jdk8.catalog.infra;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import java.math.BigDecimal;

/**
 * JPA mapping for {@code catalog_product} on the Boot 2.7 / javax.persistence
 * stack. The schema is owned by {@code schema.sql} (demo) or by the JDK 21
 * shell's Flyway migrations (mysql profile); Hibernate only validates.
 */
@Entity
@Table(name = "catalog_product")
public class ProductJpaEntity {

    @Id
    private long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "description", length = 512)
    private String description;

    protected ProductJpaEntity() {
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
