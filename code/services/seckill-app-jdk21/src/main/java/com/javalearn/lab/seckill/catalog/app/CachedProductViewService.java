package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.api.ProductViewService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Maps the core {@code Product} to the HTTP-facing {@link ProductResponse} and
 * lets Spring's cache abstraction go through the Caffeine L1 + Redis L2 cache.
 *
 * <p>On a cache miss the load goes through {@link ProductReadService}, which
 * opens a read-only transaction; the routing datasource therefore sends the
 * database read to the read pool. The cached method itself must stay
 * non-transactional, otherwise a cache hit would still open a transaction.</p>
 *
 * <p>A missing product still raises the core {@code BizException}; nothing is
 * cached for absent rows (cache-penetration protection is a later milestone).</p>
 */
@Service
public class CachedProductViewService implements ProductViewService {

    public static final String PRODUCT_CACHE = "product";

    private final ProductReadService productReadService;

    public CachedProductViewService(ProductReadService productReadService) {
        this.productReadService = Objects.requireNonNull(productReadService, "productReadService");
    }

    @Override
    @Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")
    public ProductResponse get(long id) {
        return ProductResponse.from(productReadService.get(id));
    }
}
