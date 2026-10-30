package com.javalearn.lab.seckill.jdk8.admission.infra;

import com.javalearn.lab.seckill.jdk8.admission.app.SeckillStockStore;
import com.javalearn.lab.seckill.jdk8.admission.config.SeckillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Seeds Redis stock from the durable {@code inventory_stock} table at startup.
 *
 * <p>{@code SET NX} is used per product, so an existing Redis key is never
 * overwritten (a restart must not reset reserved stock, and the two other
 * business instances share the same keys). Seeding failures are logged and
 * swallowed: Redis is best-effort for startup health, exactly like the product
 * cache, and the app must still boot during a Redis outage.</p>
 *
 * <p>The seed read deliberately uses {@link JdbcTemplate} outside a read-only
 * transaction. It is a startup consistency read and therefore goes through the
 * write pool.</p>
 */
public final class SeckillStockSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockSeeder.class);

    private static final String SELECT_STOCK_SQL =
            "SELECT product_id, available FROM inventory_stock";

    private final SeckillProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final SeckillStockStore stockStore;

    public SeckillStockSeeder(SeckillProperties properties,
                              JdbcTemplate jdbcTemplate,
                              SeckillStockStore stockStore) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.stockStore = Objects.requireNonNull(stockStore, "stockStore");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getStock().isSeedOnStartup()) {
            log.info("Seckill stock seeding disabled by app.seckill.stock.seed-on-startup");
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_STOCK_SQL);
            int seeded = 0;
            int skipped = 0;
            for (Map<String, Object> row : rows) {
                long productId = ((Number) row.get("product_id")).longValue();
                int available = ((Number) row.get("available")).intValue();
                if (stockStore.seedIfAbsent(productId, available)) {
                    seeded++;
                } else {
                    skipped++;
                }
            }
            log.info("Seckill stock seeding finished: seeded={}, skipped(existing)={}",
                    seeded, skipped);
        } catch (RuntimeException failure) {
            log.warn("Seckill stock seeding skipped after Redis/DB failure: {}",
                    failure.toString());
        }
    }
}
