package com.ktb.chatapp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
    "spring.data.mongodb.auto-index-creation=true",
    "socketio.enabled=false"
})
@DisplayName("MessageRepository 통합 테스트")
class MessageRepositoryIntegrationTest {

    @Autowired private MessageRepository messageRepository;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    @Test
    @DisplayName("여러 채팅방의 최근 메시지 수를 한 번의 집계로 반환한다")
    void countRecentMessagesByRoomIds_GroupsRecentMessagesByRequestedRoom() {
        LocalDateTime now = LocalDateTime.now();
        saveMessage("room-1", now.minusMinutes(10));
        saveMessage("room-1", now.minusMinutes(20));
        saveMessage("room-1", now.minusMinutes(40));
        saveMessage("room-2", now.minusMinutes(5));
        saveMessage("room-3", now.minusMinutes(5));

        List<RecentMessageCount> result =
            messageRepository.countRecentMessagesByRoomIds(
                Set.of("room-1", "room-2"),
                now.minusMinutes(30));

        assertThat(result)
            .extracting(RecentMessageCount::roomKey, RecentMessageCount::count)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("room-1", 2L),
                org.assertj.core.groups.Tuple.tuple("room-2", 1L));
    }

    private void saveMessage(String roomId, LocalDateTime timestamp) {
        Message saved = messageRepository.save(Message.builder()
            .roomId(roomId)
            .content("메시지")
            .build());
        saved.setTimestamp(timestamp);
        messageRepository.save(saved);
    }
}
