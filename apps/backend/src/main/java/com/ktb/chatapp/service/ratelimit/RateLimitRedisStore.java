package com.ktb.chatapp.service.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rate-limit.store.type", havingValue = "redis", matchIfMissing = true)
public class RateLimitRedisStore implements RateLimitStore {

    private static final String KEY_PREFIX = "rate_limit:";
    private static final DefaultRedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], 1, 'PX', ARGV[2])
                return {1, 1, tonumber(ARGV[2])}
            end

            local ttl = redis.call('PTTL', KEYS[1])
            if tonumber(current) >= tonumber(ARGV[1]) then
                return {0, tonumber(current), ttl}
            end

            local count = redis.call('INCR', KEYS[1])
            return {1, count, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public RateLimitConsumption consume(
            String clientId, int maxRequests, Duration window, Instant now) {
        long windowMillis = Math.max(1L, window.toMillis());
        List<?> result = redisTemplate.execute(
                CONSUME_SCRIPT,
                Collections.singletonList(KEY_PREFIX + clientId),
                Integer.toString(maxRequests),
                Long.toString(windowMillis));

        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis rate limit script returned an invalid result");
        }

        boolean allowed = number(result.get(0)).longValue() == 1L;
        int count = number(result.get(1)).intValue();
        long ttlMillis = Math.max(1L, number(result.get(2)).longValue());
        return new RateLimitConsumption(count, now.plusMillis(ttlMillis), allowed);
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("Redis rate limit script returned a non-numeric value");
    }
}
