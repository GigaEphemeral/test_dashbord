package com.javalearn.lab.seckill.lab.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * A fully populated {@link LabCacheProperties} for tests. The YAML supplies these
 * values in production and {@code LabCachePropertiesTest} proves the binding
 * contract; tests that only need a coherent configuration use these defaults and
 * override the one field they care about.
 *
 * <p>Test-scope fixture, not runtime configuration: the numbers here are the
 * application.yml defaults, deliberately small enough to keep tests fast.</p>
 */
public final class TestLabCacheProperties {

    public static final long START_ID = 90_000L;
    public static final long PRODUCT_COUNT = 5L;
    public static final long AVALANCHE_KEY_COUNT = 2L;
    public static final long HOT_KEY_INDEX = 2L;

    private TestLabCacheProperties() {
    }

    public static LabCacheProperties defaults() {
        LabCacheProperties properties = new LabCacheProperties();
        LabCacheProperties.Seed seed = properties.getSeed();
        seed.setProductCount(PRODUCT_COUNT);
        seed.setMaxRequestCount(10_000L);
        seed.setStartId(START_ID);
        seed.setProtectedMaxId(1_000L);
        seed.setAvalancheKeyCount(AVALANCHE_KEY_COUNT);
        seed.setAvalancheTtlSeconds(60L);
        seed.setHotKeyIndex(HOT_KEY_INDEX);
        seed.setHotKeyTtlSeconds(300L);
        seed.setWriteBatchSize(2);
        seed.setNamePrefix("实验商品");
        seed.setStatus("ON_SALE");
        seed.setPrice(new BigDecimal("99.00"));

        properties.getState().setScanCount(500L);

        LabCacheProperties.Keys keys = properties.getKeys();
        keys.setDefaultPrefix("cache:product:");
        keys.setAllowedPrefixes(List.of("cache:product:"));
        keys.setDefaultLimit(100);
        keys.setMaxLimit(1_000);
        keys.setScanCount(500L);
        keys.setScanBatches(1);
        keys.setValueMaxBytes(2_048);
        keys.setResetScanCount(1_000L);
        keys.setResetMaxKeys(50_000L);

        LabCacheProperties.Penetration penetration = properties.getPenetration();
        penetration.setMode("off");
        penetration.getNullCache().setKeyPrefix("null:product:");
        penetration.getNullCache().setTtlSeconds(10L);
        penetration.getNullCache().setJitterSeconds(5L);
        penetration.getBloom().setKey("bloom:product-ids");
        penetration.getBloom().setExpectedInsertions(10_000L);
        penetration.getBloom().setFalseProbability(0.01D);

        LabCacheProperties.Breakdown breakdown = properties.getBreakdown();
        breakdown.getMutex().setKeyPrefix("lock:product:");
        breakdown.getMutex().setWaitMs(200L);
        breakdown.getMutex().setLeaseMs(2_000L);
        breakdown.getMutex().setOnLockTimeout("db");
        LabCacheProperties.LogicalExpire logical = breakdown.getLogicalExpire();
        logical.setLogicalTtlSeconds(30L);
        // Must exceed seed.hotKeyTtlSeconds (300) exactly like the production
        // default; LabCacheProperties.validate() enforces that relationship.
        logical.setRetentionSeconds(600L);
        logical.setMaxEntries(1_000L);
        logical.setRefreshPoolSize(2);
        logical.setRefreshQueueCapacity(100);
        logical.setKeepAliveSeconds(60L);
        logical.setThreadNamePrefix("breakdown-refresh");
        return properties;
    }
}
