package com.ktb.chatapp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.model.Room;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RoomRepositoryIntegrationTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        roomRepository.deleteAll();
    }

    @Test
    void participantLookupUsesAnExplicitMultikeyIndex() {
        roomRepository.save(Room.builder()
                .name("indexed-room")
                .participantIds(Set.of("user-1", "user-2"))
                .build());

        assertThat(roomRepository.findByParticipantIdsContaining("user-2"))
                .extracting(Room::getName)
                .containsExactly("indexed-room");

        assertThat(mongoTemplate.indexOps(Room.class).getIndexInfo())
                .anySatisfy(index -> {
                    if ("participant_ids_idx".equals(index.getName())) {
                        assertThat(index.getIndexFields())
                                .anySatisfy(field -> assertThat(field.getKey()).isEqualTo("participantIds"));
                    } else {
                        throw new AssertionError("not the participant index");
                    }
                });
    }
}
