package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.service.session.SessionRedisStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "socketio.enabled=false",
        "session.store.type=redis"
})
class SessionRedisStoreIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void sessionCreatedOnOneInstanceIsValidatedOnAnother() {
        var firstInstance = service();
        var secondInstance = service();

        var created = firstInstance.createSession(
                "shared-user", new SessionMetadata("agent", "127.0.0.1", "device"));

        var validation = secondInstance.validateSession("shared-user", created.getSessionId());

        assertThat(validation.isValid()).isTrue();
        assertThat(validation.getSession().getMetadata().deviceInfo()).isEqualTo("device");
    }

    @Test
    void replacingSessionPreventsStaleInstanceFromDeletingTheNewSession() {
        var firstInstance = service();
        var secondInstance = service();
        var oldSession = firstInstance.createSession("shared-user", null);
        var newSession = secondInstance.createSession("shared-user", null);

        firstInstance.removeSession("shared-user", oldSession.getSessionId());

        assertThat(secondInstance.validateSession("shared-user", oldSession.getSessionId()).isValid())
                .isFalse();
        assertThat(secondInstance.validateSession("shared-user", newSession.getSessionId()).isValid())
                .isTrue();
    }

    private SessionService service() {
        return new SessionService(new SessionRedisStore(redisTemplate, objectMapper));
    }
}
