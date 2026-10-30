package com.javalearn.lab.seckill.jdk8.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.javalearn.lab.seckill.catalog.domain.Product;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * HTTP representation of a catalog product. Field names match the JDK 21 shell's
 * {@code com.javalearn.lab.seckill.catalog.api.ProductResponse} record so one k6
 * script can scrape either runtime unchanged; Java 8 has no record, so this is a
 * plain immutable POJO with a JSON creator for the L2 round trip.
 */
public final class ProductResponse {

    private final long id;
    private final String name;
    private final BigDecimal price;
    private final String status;
    private final String description;

    @JsonCreator
    public ProductResponse(@JsonProperty("id") long id,
                           @JsonProperty("name") String name,
                           @JsonProperty("price") BigDecimal price,
                           @JsonProperty("status") String status,
                           @JsonProperty("description") String description) {
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductResponse)) {
            return false;
        }
        ProductResponse that = (ProductResponse) other;
        return id == that.id
                && Objects.equals(name, that.name)
                && Objects.equals(price, that.price)
                && Objects.equals(status, that.status)
                && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, price, status, description);
    }

    @Override
    public String toString() {
        return "ProductResponse{id=" + id + ", name='" + name + "', price=" + price
                + ", status='" + status + "', description='" + description + "'}";
    }
}
