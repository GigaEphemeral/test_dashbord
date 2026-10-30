package com.javalearn.lab.seckill.jdk8.inventory.infra;

import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Outbound adapter: a reservation succeeds only when the conditional stock
 * update touched a row.
 *
 * <p>This adapter is the single funnel for every database stock decrement:
 * the synchronous {@code POST /api/orders} path and the async seckill consumer
 * both arrive here through the same use case. A conditional
 * {@code UPDATE ... WHERE available >= :quantity} that matches zero rows is one
 * oversell attempt refused by the database guard, so this is the convergence
 * point where {@code stock_oversold_total} is incremented exactly once per
 * prevented attempt. Callers above may reject the same request for other
 * reasons (Redis sold out at admission, duplicate key) and are metered by
 * their own counters; those never touch this metric.</p>
 */
@Component
public class JpaInventoryRepositoryAdapter implements InventoryRepository {

    /**
     * Raw meter name; the Prometheus client exports the counter as
     * {@code stock_oversold_total}. {@code SeckillMetrics} registers the same
     * name and description eagerly, so the series exists at zero before the
     * first prevented oversell, and Micrometer resolves both builders to the
     * identical counter.
     */
    public static final String STOCK_OVERSOLD_METRIC = "stock_oversold";
    public static final String STOCK_OVERSOLD_DESCRIPTION =
            "Oversell attempts prevented by the conditional stock update (0 rows affected)";

    private final InventoryStockJpaRepository inventoryStockJpaRepository;
    private final Counter stockOversold;

    public JpaInventoryRepositoryAdapter(InventoryStockJpaRepository inventoryStockJpaRepository,
                                         MeterRegistry meterRegistry) {
        this.inventoryStockJpaRepository = Objects.requireNonNull(
                inventoryStockJpaRepository, "inventoryStockJpaRepository");
        this.stockOversold = Counter.builder(STOCK_OVERSOLD_METRIC)
                .description(STOCK_OVERSOLD_DESCRIPTION)
                .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    @Override
    public boolean reserve(long productId, int quantity) {
        int updated = inventoryStockJpaRepository.reserve(productId, quantity);
        if (updated == 0) {
            // The database guard held: the oversell attempt was prevented.
            stockOversold.increment();
            return false;
        }
        return true;
    }
}
