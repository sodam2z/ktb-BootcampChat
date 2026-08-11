package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "session.store.type", havingValue = "redis", matchIfMissing = true)
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "session:user:";
    private static final DefaultRedisScript<Long> REPLACE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1],
                'userId', ARGV[1], 'sessionId', ARGV[2],
                'createdAt', ARGV[3], 'lastActivity', ARGV[4],
                'metadata', ARGV[5], 'expiresAt', ARGV[6])
            redis.call('PEXPIREAT', KEYS[1], ARGV[6])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'sessionId') ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'lastActivity', ARGV[2], 'expiresAt', ARGV[3])
            redis.call('PEXPIREAT', KEYS[1], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'sessionId') ~= ARGV[1] then
                return 0
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Session> findByUserId(String userId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(userId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSession(values));
    }

    @Override
    public Session save(Session session) {
        return replaceByUserId(session);
    }

    @Override
    public Session replaceByUserId(Session session) {
        redisTemplate.execute(REPLACE_SCRIPT, List.of(key(session.getUserId())),
                session.getUserId(), session.getSessionId(),
                Long.toString(session.getCreatedAt()), Long.toString(session.getLastActivity()),
                serializeMetadata(session.getMetadata()),
                Long.toString(session.getExpiresAt().toEpochMilli()));
        return session;
    }

    @Override
    public Optional<Session> touch(
            String userId, String sessionId, long lastActivity, Instant expiresAt) {
        Long updated = redisTemplate.execute(TOUCH_SCRIPT, List.of(key(userId)),
                sessionId, Long.toString(lastActivity), Long.toString(expiresAt.toEpochMilli()));
        return updated != null && updated == 1L ? findByUserId(userId) : Optional.empty();
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        redisTemplate.execute(DELETE_SCRIPT, List.of(key(userId)), sessionId);
    }

    private Session toSession(Map<Object, Object> values) {
        String metadata = string(values, "metadata");
        return Session.builder()
                .userId(string(values, "userId"))
                .sessionId(string(values, "sessionId"))
                .createdAt(Long.parseLong(string(values, "createdAt")))
                .lastActivity(Long.parseLong(string(values, "lastActivity")))
                .metadata(deserializeMetadata(metadata))
                .expiresAt(Instant.ofEpochMilli(Long.parseLong(string(values, "expiresAt"))))
                .build();
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private String string(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Redis session is missing field: " + field);
        }
        return value.toString();
    }

    private String serializeMetadata(SessionMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize session metadata", e);
        }
    }

    private SessionMetadata deserializeMetadata(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, SessionMetadata.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize session metadata", e);
        }
    }
}
