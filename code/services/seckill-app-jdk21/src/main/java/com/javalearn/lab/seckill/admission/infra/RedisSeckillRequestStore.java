package com.javalearn.lab.seckill.admission.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.admission.app.SeckillRequestStore;
import com.javalearn.lab.seckill.admission.domain.SeckillRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * JSON request state in Redis, keyed by request id with a configurable TTL.
 */
public final class RedisSeckillRequestStore implements SeckillRequestStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSeckillRequestStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisSeckillRequestStore(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    String keyPrefix,
                                    Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public void save(SeckillRequest request) {
        redisTemplate.opsForValue().set(key(request.requestId()), write(request), ttl);
    }

    @Override
    public Optional<SeckillRequest> find(String requestId) {
        String payload = redisTemplate.opsForValue().get(key(requestId));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, SeckillRequest.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt seckill request state: " + requestId, ex);
        }
    }

    @Override
    public void markCreated(String requestId, long orderId) {
        update(requestId, request -> request.created(orderId));
    }

    @Override
    public void markFailed(String requestId, String message) {
        update(requestId, request -> request.failed(message));
    }

    @Override
    public void delete(String requestId) {
        redisTemplate.delete(key(requestId));
    }

    private void update(String requestId,
                        java.util.function.UnaryOperator<SeckillRequest> change) {
        Optional<SeckillRequest> existing = find(requestId);
        if (existing.isEmpty()) {
            log.warn("Seckill request state {} is missing; status update skipped", requestId);
            return;
        }
        save(change.apply(existing.get()));
    }

    private String write(SeckillRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to serialize seckill request " + request.requestId(), ex);
        }
    }

    private String key(String requestId) {
        return keyPrefix + requestId;
    }
}
