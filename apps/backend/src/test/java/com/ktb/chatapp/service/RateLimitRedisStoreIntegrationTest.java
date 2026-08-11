package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.service.ratelimit.RateLimitRedisStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "socketio.enabled=false",
        "rate-limit.store.type=redis"
})
class RateLimitRedisStoreIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @BeforeEach
    void clearRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void rejectsRequestsAfterTheSharedLimit() {
        var service = new RateLimitService(new RateLimitRedisStore(redisTemplate));

        assertThat(service.checkRateLimit("client", 2, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(service.checkRateLimit("client", 2, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(service.checkRateLimit("client", 2, Duration.ofMinutes(1)).allowed()).isFalse();
    }

    @Test
    void concurrentInstancesNeverAllowMoreThanTheSharedLimit() throws Exception {
        int maxRequests = 25;
        var firstInstance = new RateLimitService(new RateLimitRedisStore(redisTemplate));
        var secondInstance = new RateLimitService(new RateLimitRedisStore(redisTemplate));
        var executor = Executors.newFixedThreadPool(20);
        try {
            var futures = IntStream.range(0, 100)
                    .mapToObj(index -> executor.submit(() -> {
                        var instance = index % 2 == 0 ? firstInstance : secondInstance;
                        return instance.checkRateLimit(
                                "shared-client", maxRequests, Duration.ofMinutes(1));
                    }))
                    .toList();

            long allowed = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS).allowed()) {
                    allowed++;
                }
            }

            assertThat(allowed).isEqualTo(maxRequests);
        } finally {
            executor.shutdownNow();
        }
    }
}
