package com.javalearn.lab.seckill.lab.api;

/**
 * {@code POST /lab/cache/seed} payload: what the experiment dataset looks like
 * after this call.
 *
 * <p>{@code inserted} is zero on every call after the first, which is how the
 * operator sees the seed is idempotent; {@code rowsInRange} and
 * {@code avalancheKeysWritten} report the dataset size as it really is now, so a
 * partial Redis failure is visible instead of being silently reported as success.
 * Never contains a secret.</p>
 */
public record CacheSeedResponse(long startId,
                                long endId,
                                long requestedProducts,
                                long inserted,
                                long alreadyPresent,
                                long rowsInRange,
                                long avalancheKeysWritten,
                                long avalancheTtlSeconds,
                                long hotKeyProductId,
                                boolean hotKeyWritten,
                                long bloomIdsAdded,
                                boolean bloomInitialised,
                                String mode) {
}
