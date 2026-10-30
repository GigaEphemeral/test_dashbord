package com.javalearn.lab.seckill.jdk8.admission.infra;

import com.javalearn.lab.seckill.jdk8.admission.app.SeckillStockStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;

/**
 * Redis stock store. Reservation is one Lua script, so the check and the
 * decrement are a single atomic Redis operation even across app instances.
 *
 * <p>The Lua resource path is supplied by {@code app.seckill.stock.lua-path}
 * and loaded at construction time so a typo fails at startup, not on the
 * first request. The script text is identical to the JDK 21 shell's
 * {@code lua/seckill_stock.lua}, so all three instances execute the same
 * server-side logic.</p>
 */
public final class RedisSeckillStockStore implements SeckillStockStore {

    /** Lua result codes shared with {@code lua/seckill_stock.lua}. */
    private static final long RESULT_MISSING = -2L;
    private static final long RESULT_SOLD_OUT = -1L;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final DefaultRedisScript<Long> reserveScript;

    public RedisSeckillStockStore(StringRedisTemplate redisTemplate,
                                  String keyPrefix,
                                  String luaPath) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.reserveScript = loadScript(luaPath);
    }

    @Override
    public Reservation reserve(long productId, int quantity) {
        Long result = redisTemplate.execute(
                reserveScript,
                Collections.singletonList(key(productId)),
                Integer.toString(quantity));
        if (result == null) {
            throw new IllegalStateException("Seckill stock script returned no result");
        }
        if (result == RESULT_MISSING) {
            return new Reservation(ReservationStatus.MISSING, 0L);
        }
        if (result == RESULT_SOLD_OUT) {
            return new Reservation(ReservationStatus.SOLD_OUT, 0L);
        }
        return new Reservation(ReservationStatus.RESERVED, result);
    }

    @Override
    public boolean restore(long productId, int quantity) {
        if (quantity <= 0) {
            return false;
        }
        String key = key(productId);
        // The key may have been removed (expired/manual reset); never recreate
        // stock out of thin air.
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return false;
        }
        Long restored = redisTemplate.opsForValue().increment(key, quantity);
        return restored != null;
    }

    @Override
    public boolean seedIfAbsent(long productId, int available) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key(productId), Integer.toString(available)));
    }

    private String key(long productId) {
        return keyPrefix + productId;
    }

    private static DefaultRedisScript<Long> loadScript(String luaPath) {
        ClassPathResource resource = new ClassPathResource(luaPath);
        try {
            String script = StreamUtils.copyToString(resource.getInputStream(),
                    StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(script, Long.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to load seckill stock Lua script from classpath:" + luaPath, ex);
        }
    }
}
