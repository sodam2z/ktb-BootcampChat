package com.ktb.chatapp.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RoomActivityDebouncer {

    private static final String KEY_PREFIX = "room_activity:debounce:";

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final Duration debounceWindow;
    private final AtomicBoolean fallbackWarningLogged = new AtomicBoolean();

    public RoomActivityDebouncer(
            ObjectProvider<RedissonClient> redissonClientProvider,
            @Value("${room-activity.debounce-window:1s}") Duration debounceWindow) {
        this.redissonClientProvider = redissonClientProvider;
        this.debounceWindow = debounceWindow;
    }

    public boolean tryAcquire(String roomId) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return true;
        }

        try {
            return redissonClient.getBucket(KEY_PREFIX + roomId)
                    .setIfAbsent("1", debounceWindow);
        } catch (RuntimeException e) {
            if (fallbackWarningLogged.compareAndSet(false, true)) {
                log.warn("Redis room activity debounce unavailable; using local batching", e);
            }
            return true;
        }
    }
}
