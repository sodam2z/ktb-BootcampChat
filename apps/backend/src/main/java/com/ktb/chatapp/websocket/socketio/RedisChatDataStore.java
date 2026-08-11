package com.ktb.chatapp.websocket.socketio;

import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.store.type", havingValue = "redis", matchIfMissing = true)
public class RedisChatDataStore implements ChatDataStore {

    private static final Duration PRESENCE_TTL = Duration.ofMinutes(35);
    private final RedissonClient redissonClient;

    public RedisChatDataStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = redissonClient.getBucket(key).get();
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public void set(String key, Object value) {
        redissonClient.getBucket(key).set(value, PRESENCE_TTL);
    }

    @Override
    public <T> T getAndSet(String key, T value, Class<T> type) {
        Object previous = redissonClient.getBucket(key).getAndSet(value, PRESENCE_TTL);
        return type.isInstance(previous) ? type.cast(previous) : null;
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key).delete();
    }

    @Override
    public boolean refresh(String key) {
        return redissonClient.getBucket(key).expire(PRESENCE_TTL);
    }

    @Override
    public boolean compareAndDelete(String key, Object expectedValue) {
        return redissonClient.getBucket(key).compareAndSet(expectedValue, null);
    }

    @Override
    public java.util.Set<String> getSet(String key) {
        return redissonClient.<String>getSet(key).readAll();
    }

    @Override
    public void addToSet(String key, String value) {
        var values = redissonClient.<String>getSet(key);
        values.add(value);
    }

    @Override
    public void removeFromSet(String key, String value) {
        var values = redissonClient.<String>getSet(key);
        values.remove(value);
        if (values.isEmpty()) {
            values.delete();
        }
    }

    @Override
    public int size() {
        int count = 0;
        for (String ignored : redissonClient.getKeys().getKeysByPattern("conn_users:userid:*")) {
            count++;
        }
        return count;
    }
}
