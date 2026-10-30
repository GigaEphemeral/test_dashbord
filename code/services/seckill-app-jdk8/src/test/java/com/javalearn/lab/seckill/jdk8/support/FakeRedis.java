package com.javalearn.lab.seckill.jdk8.support;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory stand-in for {@link StringRedisTemplate}, used by tests whose subject
 * is the cache/L1 logic rather than Redis itself (the JDK 21 shell drove these
 * cases through a Testcontainers Redis; this shell must stay container-free).
 *
 * <p>Only the operations the production code actually performs are modelled:
 * value get/set with TTL, delete (single key and batch), {@code getExpire} and a
 * prefix SCAN. TTLs are tracked as absolute deadlines so a test can assert real
 * remaining seconds without sleeping. Unknown operations return Mockito defaults,
 * which is the same "Redis answered nothing" behaviour the cache treats as a miss.</p>
 */
public final class FakeRedis {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, Long> expiresAtMillis = new LinkedHashMap<>();
    private final ValueOperations<String, String> operations;
    private final StringRedisTemplate template;

    public FakeRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockedOperations = mock(ValueOperations.class);
        this.operations = mockedOperations;
        this.template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            set(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class),
                    invocation.getArgument(2, Duration.class));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        doAnswer(invocation -> {
            putWithoutTtl(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class));
            return null;
        }).when(operations).set(anyString(), anyString());
        when(template.getExpire(anyString())).thenAnswer(invocation ->
                remainingSeconds(invocation.getArgument(0, String.class)));
        when(template.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(invocation ->
                remainingSeconds(invocation.getArgument(0, String.class)));
        when(template.delete(anyString())).thenAnswer(invocation ->
                deleteOne(invocation.getArgument(0, String.class)));
        when(template.delete(any(Collection.class))).thenAnswer(invocation ->
                deleteMany(invocation.getArgument(0, Collection.class)));
        when(template.scan(any(ScanOptions.class))).thenAnswer(invocation ->
                scan(invocation.getArgument(0, ScanOptions.class)));
    }

    public StringRedisTemplate template() {
        return template;
    }

    /** Empties the fake keyspace, like {@code FLUSHDB} at the start of a test. */
    public void flushDb() {
        values.clear();
        expiresAtMillis.clear();
    }

    /** Puts a value without a TTL (a foreign key the cache must leave alone). */
    public void putWithoutTtl(String key, String value) {
        values.put(key, value);
        expiresAtMillis.remove(key);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        return values.get(key);
    }

    private void set(String key, String value, Duration ttl) {
        values.put(key, value);
        expiresAtMillis.put(key, System.currentTimeMillis() + ttl.toMillis());
    }

    private Long remainingSeconds(String key) {
        if (!values.containsKey(key)) {
            return -2L;
        }
        Long deadline = expiresAtMillis.get(key);
        if (deadline == null) {
            return -1L;
        }
        long millis = deadline - System.currentTimeMillis();
        return millis <= 0L ? -2L : millis / 1000L;
    }

    private Boolean deleteOne(String key) {
        expiresAtMillis.remove(key);
        return values.remove(key) != null;
    }

    private Long deleteMany(Collection<?> keys) {
        long removed = 0L;
        for (Object key : keys) {
            if (deleteOne(String.valueOf(key))) {
                removed++;
            }
        }
        return removed;
    }

    private Cursor<String> scan(ScanOptions options) {
        String pattern = options.getPattern();
        String prefix = pattern.endsWith("*")
                ? pattern.substring(0, pattern.length() - 1)
                : pattern;
        final List<String> matches = new ArrayList<>();
        for (String key : values.keySet()) {
            if (key.startsWith(prefix)) {
                matches.add(key);
            }
        }
        final int[] index = {0};
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(invocation -> index[0] < matches.size());
        when(cursor.next()).thenAnswer(invocation -> matches.get(index[0]++));
        return cursor;
    }
}
