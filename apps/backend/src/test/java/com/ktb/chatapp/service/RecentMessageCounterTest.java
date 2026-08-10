package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RecentMessageCount;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecentMessageCounter 단위 테스트")
class RecentMessageCounterTest {

    @Mock private MessageRepository messageRepository;

    private RecentMessageCounter recentMessageCounter;

    @BeforeEach
    void setUp() {
        recentMessageCounter = new RecentMessageCounter(messageRepository);
    }

    @Test
    @DisplayName("여러 채팅방의 최근 메시지 수를 한 번에 조회해 Map으로 변환한다")
    void countRecentMessagesByRoomIds_LoadsCountsOnceAndMapsByRoomId() {
        Set<String> roomIds = Set.of("room-1", "room-2");
        when(messageRepository.countRecentMessagesByRoomIds(
                eq(roomIds), any(LocalDateTime.class)))
            .thenReturn(List.of(
                new RecentMessageCount("room-1", 3),
                new RecentMessageCount("room-2", 5)));

        Map<String, Integer> result =
            recentMessageCounter.countRecentMessagesByRoomIds(roomIds);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
            Map.of("room-1", 3, "room-2", 5));
        verify(messageRepository).countRecentMessagesByRoomIds(
            eq(roomIds), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("채팅방 ID가 없으면 메시지를 조회하지 않는다")
    void countRecentMessagesByRoomIds_EmptyRoomIdsSkipsRepository() {
        Map<String, Integer> result =
            recentMessageCounter.countRecentMessagesByRoomIds(Set.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("단건 최근 메시지 조회 동작을 유지한다")
    void countRecentMessages_KeepsSingleRoomQuery() {
        when(messageRepository.countRecentMessagesByRoomId(
                eq("room-1"), any(LocalDateTime.class)))
            .thenReturn(7L);

        int result = recentMessageCounter.countRecentMessages("room-1");

        assertThat(result).isEqualTo(7);
        verify(messageRepository).countRecentMessagesByRoomId(
            eq("room-1"), any(LocalDateTime.class));
    }
}
