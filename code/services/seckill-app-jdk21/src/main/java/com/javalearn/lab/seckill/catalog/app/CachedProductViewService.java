package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.api.ProductViewService;
import com.javalearn.lab.seckill.catalog.cache.ProductPenetrationDefense;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Maps the core {@code Product} to the HTTP-facing {@link ProductResponse} and
 * lets Spring's cache abstraction go through the Caffeine L1 + Redis L2 cache.
 *
 * <p>On a cache miss the load goes through {@link ProductReadService}, which
 * opens a read-only transaction; the routing datasource therefore sends the
 * database read to the read pool. The cached method itself must stay
 * non-transactional, otherwise a cache hit would still open a transaction.</p>
 *
 * <p>The method body is the penetration stage of the read path: it only runs
 * after L1 and L2 missed, so {@link ProductPenetrationDefense} sees exactly the
 * traffic the experiment manipulates. An absent product still raises the same
 * {@code 40401} {@link BizException} as before, whatever the defense mode; what
 * changes is only whether the database had to be asked.</p>
 */
@Service
public class CachedProductViewService implements ProductViewService {

    public static final String PRODUCT_CACHE = "product";

    private final ProductReadService productReadService;
    private final ProductPenetrationDefense penetrationDefense;

    public CachedProductViewService(ProductReadService productReadService,
                                    ProductPenetrationDefense penetrationDefense) {
        this.productReadService = Objects.requireNonNull(productReadService, "productReadService");
        this.penetrationDefense = Objects.requireNonNull(penetrationDefense, "penetrationDefense");
    }

    @Override
    @Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")
    public ProductResponse get(long id) {
        return penetrationDefense.load(id, this::findInDatabase)
                .orElseThrow(() -> new BizException(
                        CommonErrorCode.NOT_FOUND, "Product not found: " + id));
    }

    /**
     * Translates the core's throw-on-missing contract into the Optional the
     * defense needs to recognise a miss; any other business error propagates
     * unchanged so it cannot be mistaken for absent data.
     */
    private Optional<ProductResponse> findInDatabase(long id) {
        try {
            return Optional.of(ProductResponse.from(productReadService.get(id)));
        } catch (BizException exception) {
            if (exception.getErrorCode() != CommonErrorCode.NOT_FOUND) {
                throw exception;
            }
            return Optional.empty();
        }
    }
}
