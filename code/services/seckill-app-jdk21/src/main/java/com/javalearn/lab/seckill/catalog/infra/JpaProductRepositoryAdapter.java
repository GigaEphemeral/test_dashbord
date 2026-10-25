package com.javalearn.lab.seckill.catalog.infra;

import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Outbound adapter: translates between the JPA entity and the framework-neutral
 * {@link Product} domain type.
 */
@Component
public class JpaProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    public JpaProductRepositoryAdapter(ProductJpaRepository productJpaRepository) {
        this.productJpaRepository = Objects.requireNonNull(productJpaRepository, "productJpaRepository");
    }

    @Override
    public Optional<Product> findById(long id) {
        return productJpaRepository.findById(id).map(JpaProductRepositoryAdapter::toDomain);
    }

    private static Product toDomain(ProductJpaEntity entity) {
        return new Product(
                entity.getId(),
                entity.getName(),
                entity.getPrice(),
                entity.getStatus(),
                entity.getDescription());
    }
}
