package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "socketio.enabled=false",
        "socketio.store.type=redis"
})
class RoomActivityDebouncerIntegrationTest {

    @Autowired
    private RoomActivityDebouncer debouncer;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void clearKeys() {
        redissonClient.getKeys().deleteByPattern("room_activity:debounce:*");
    }

    @Test
    void onlyOneInstanceCanAggregateTheSameRoomDuringTheWindow() {
        assertThat(debouncer.tryAcquire("room-1")).isTrue();
        assertThat(debouncer.tryAcquire("room-1")).isFalse();
        assertThat(debouncer.tryAcquire("room-2")).isTrue();
    }
}
