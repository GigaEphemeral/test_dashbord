package com.javalearn.lab.seckill.catalog.domain;

import java.util.Optional;

/**
 * Catalog persistence port. Implementations live in the application shell so
 * the core stays free of Spring, JPA and runtime-specific APIs.
 */
@FunctionalInterface
public interface ProductRepository {

    Optional<Product> findById(long id);
}
