package com.ktb.chatapp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.model.Room;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        Room room = roomRepository.save(Room.builder()
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

        assertThat(roomRepository.findAccessById(room.getId(), "user-2"))
                .contains(new RoomAccessResult(true));
        assertThat(roomRepository.findAccessById(room.getId(), "outsider"))
                .contains(new RoomAccessResult(false));
        assertThat(roomRepository.findAccessById("missing-room", "user-2")).isEmpty();
    }

    @Test
    void concurrentParticipantAddsDoNotLoseMembers() {
        Room room = roomRepository.save(Room.builder()
                .name("concurrent-room")
                .participantIds(Set.of("creator"))
                .build());
        int participantCount = 40;

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CompletableFuture<?>[] additions = IntStream.range(0, participantCount)
                    .mapToObj(index -> CompletableFuture.runAsync(
                            () -> roomRepository.addParticipant(room.getId(), "user-" + index),
                            executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(additions).join();
        }

        Room updated = roomRepository.findById(room.getId()).orElseThrow();
        assertThat(updated.getParticipantIds()).hasSize(participantCount + 1);
        assertThat(updated.getParticipantIds()).contains("creator", "user-0", "user-39");
        assertThat(roomRepository.existsByIdAndParticipantIdsContaining(room.getId(), "user-17"))
                .isTrue();
    }
}
