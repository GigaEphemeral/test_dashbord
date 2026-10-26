package com.javalearn.lab.seckill.jdk8.catalog.infra;

import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapts Spring Data JPA to the framework-neutral core port, same as the
 * JDK 21 shell's adapter; the domain mapping is shared code from seckill-core.
 */
@Component
public class JpaProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    public JpaProductRepositoryAdapter(ProductJpaRepository productJpaRepository) {
        this.productJpaRepository = productJpaRepository;
    }

    @Override
    public Optional<Product> findById(long id) {
        return productJpaRepository.findById(id)
                .map(entity -> new Product(entity.getId(), entity.getName(), entity.getPrice(),
                        entity.getStatus(), entity.getDescription()));
    }
}
