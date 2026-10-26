package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.seckill.catalog.api.ProductQueryService;
import com.javalearn.lab.seckill.catalog.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Read-only catalog entry point for the application shell.
 *
 * <p>The transaction is deliberately declared here instead of on
 * {@link CachedProductViewService#get(long)}: a cache hit must not open a
 * database transaction at all, and a cache miss must acquire its connection
 * through the read pool. Spring's transaction manager marks the transaction
 * read-only before the first statement runs, so the routing datasource picks
 * {@code DataSourceType.READ} when the lazy connection is finally fetched.</p>
 *
 * <p>This class lives in the JDK 21 shell; the core {@code ProductQueryService}
 * stays framework-neutral.</p>
 */
@Service
public class ProductReadService {

    private final ProductQueryService productQueryService;

    public ProductReadService(ProductQueryService productQueryService) {
        this.productQueryService = Objects.requireNonNull(productQueryService, "productQueryService");
    }

    @Transactional(readOnly = true)
    public Product get(long id) {
        return productQueryService.get(id);
    }
}
