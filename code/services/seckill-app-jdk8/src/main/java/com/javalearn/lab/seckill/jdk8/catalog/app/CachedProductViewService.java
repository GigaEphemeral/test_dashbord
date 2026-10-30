package com.javalearn.lab.seckill.jdk8.catalog.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.jdk8.catalog.ProductReadService;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.catalog.ProductViewService;
import com.javalearn.lab.seckill.jdk8.catalog.cache.ProductAvalancheDefense;
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
 * <p>The method body is the defense stage of the read path: it only runs after
 * L1 and L2 missed, so {@link ProductAvalancheDefense} sees exactly the traffic
 * the experiment manipulates. It dispatches on the runtime mode: the avalanche
 * strategies handle an L2 avalanche, the M4-2 breakdown strategies
 * ({@code mutex}, {@code logical-expire}) the expired hot key, and every other
 * mode delegates unchanged to the M4-2a {@code ProductBreakdownDefense}, which
 * in turn runs the M4-1 {@code ProductPenetrationDefense} for the penetration
 * modes. An
 * absent product still raises the same {@code 40401} {@link BizException}
 * whatever the defense mode; what changes is only whether the database had to
 * be asked (or, for {@code db-fallback}, whether the budget admitted the
 * question at all).</p>
 *
 * <p>The {@code cacheResolver} is the write-side half of {@code ttl-jitter}: it
 * hands this read path an {@code AvalancheTtlCache} decorator that rewrites the
 * L2 TTL after the write when the mode is active. The raw two-level cache and
 * every other consumer are untouched, so {@code off} stays byte-for-byte the
 * historical behaviour.</p>
 */
@Service
public class CachedProductViewService implements ProductViewService {

    public static final String PRODUCT_CACHE = "product";

    private final ProductReadService productReadService;
    private final ProductAvalancheDefense avalancheDefense;

    public CachedProductViewService(ProductReadService productReadService,
                                    ProductAvalancheDefense avalancheDefense) {
        this.productReadService = Objects.requireNonNull(productReadService, "productReadService");
        this.avalancheDefense = Objects.requireNonNull(avalancheDefense, "avalancheDefense");
    }

    @Override
    @Cacheable(cacheNames = PRODUCT_CACHE, key = "#id", cacheResolver = "avalancheCacheResolver")
    public ProductResponse get(long id) {
        return avalancheDefense.load(id, this::findInDatabase)
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
