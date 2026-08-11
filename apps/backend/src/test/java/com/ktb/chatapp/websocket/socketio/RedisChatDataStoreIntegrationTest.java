package com.ktb.chatapp.websocket.socketio;

import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisChatDataStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    private static RedissonClient firstClient;
    private static RedissonClient secondClient;
    private static RedisChatDataStore firstStore;
    private static RedisChatDataStore secondStore;

    @BeforeAll
    static void setUp() {
        firstClient = createClient();
        secondClient = createClient();
        firstStore = new RedisChatDataStore(firstClient);
        secondStore = new RedisChatDataStore(secondClient);
    }

    @AfterAll
    static void tearDown() {
        if (firstClient != null) firstClient.shutdown();
        if (secondClient != null) secondClient.shutdown();
    }

    @Test
    void connectionOwnershipIsSharedAndConditionallyDeletedAcrossInstances() {
        String key = "conn_users:userid:integration-user";
        SocketUser first = new SocketUser("integration-user", "name", "session-1", "socket-1");
        SocketUser second = new SocketUser("integration-user", "name", "session-2", "socket-2");

        firstStore.set(key, first);
        assertThat(secondStore.getAndSet(key, second, SocketUser.class)).isEqualTo(first);
        assertThat(firstStore.compareAndDelete(key, first)).isFalse();
        assertThat(firstStore.compareAndDelete(key, second)).isTrue();
        assertThat(secondStore.get(key, SocketUser.class)).isEmpty();
    }

    @Test
    void roomSetUpdatesAreVisibleAcrossInstances() {
        String key = "userroom:roomids:integration-user";

        firstStore.addToSet(key, "room-1");
        secondStore.addToSet(key, "room-2");
        assertThat(firstStore.getSet(key)).isEqualTo(Set.of("room-1", "room-2"));

        secondStore.removeFromSet(key, "room-1");
        assertThat(firstStore.getSet(key)).containsExactly("room-2");
        firstStore.delete(key);
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }
}
