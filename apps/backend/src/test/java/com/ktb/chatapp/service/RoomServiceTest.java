package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoomService 단위 테스트")
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
            roomRepository,
            userRepository,
            recentMessageCounter,
            passwordEncoder,
            eventPublisher);
    }

    @Test
    @DisplayName("채팅방 목록의 생성자와 참여자를 한 번에 조회해 응답을 조립한다")
    void getAllRooms_LoadsDistinctUsersOnceAndMapsResponses() {
        LocalDateTime older = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime newer = older.plusHours(1);
        Room olderRoom = room("room-1", "user-1", Set.of("user-1", "user-2"), older);
        Room newerRoom = room("room-2", "user-2", Set.of("user-2", "user-3"), newer);
        User user1 = user("user-1", "사용자 1");
        User user2 = user("user-2", "사용자 2");
        User user3 = user("user-3", "사용자 3");

        when(roomRepository.findAll()).thenReturn(List.of(olderRoom, newerRoom));
        when(userRepository.findAllById(anySet())).thenReturn(List.of(user1, user2, user3));
        when(recentMessageCounter.countRecentMessagesByRoomIds(anySet()))
            .thenReturn(Map.of("room-1", 3, "room-2", 5));

        RoomsResponse response = roomService.getAllRooms("user@example.com");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).extracting(RoomResponse::getId)
            .containsExactly("room-2", "room-1");
        assertThat(response.getData().get(0).getCreator().getId()).isEqualTo("user-2");
        assertThat(response.getData().get(0).getParticipants())
            .extracting(participant -> participant.getId())
            .containsExactlyInAnyOrder("user-2", "user-3");
        assertThat(response.getData().get(0).getRecentMessageCount()).isEqualTo(5);
        assertThat(response.getMetadata().getTotal()).isEqualTo(2);
        assertThat(response.getMetadata().getCurrentCount()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> userIdsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository).findAllById(userIdsCaptor.capture());
        assertThat(StreamSupport.stream(userIdsCaptor.getValue().spliterator(), false))
            .containsExactlyInAnyOrder("user-1", "user-2", "user-3");
        verify(userRepository, never()).findById(anyString());
        verify(recentMessageCounter, times(1))
            .countRecentMessagesByRoomIds(Set.of("room-1", "room-2"));
        verify(recentMessageCounter, never()).countRecentMessages(anyString());
    }

    @Test
    @DisplayName("조회되지 않은 생성자는 null로 두고 조회되지 않은 참여자는 제외한다")
    void getAllRooms_OmitsMissingParticipantsAndKeepsMissingCreatorNull() {
        Room room = room("room-1", "missing-creator", Set.of("user-1", "missing-user"),
            LocalDateTime.of(2026, 8, 10, 10, 0));
        when(roomRepository.findAll()).thenReturn(List.of(room));
        when(userRepository.findAllById(anySet())).thenReturn(List.of(user("user-1", "사용자 1")));
        when(recentMessageCounter.countRecentMessagesByRoomIds(anySet())).thenReturn(Map.of());

        RoomsResponse response = roomService.getAllRooms("user@example.com");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).singleElement().satisfies(roomResponse -> {
            assertThat(roomResponse.getCreator()).isNull();
            assertThat(roomResponse.getParticipants())
                .extracting(participant -> participant.getId())
                .containsExactly("user-1");
            assertThat(roomResponse.getRecentMessageCount()).isZero();
        });
        verify(userRepository).findAllById(anySet());
        verify(userRepository, never()).findById(anyString());
        verify(recentMessageCounter).countRecentMessagesByRoomIds(Set.of("room-1"));
        verify(recentMessageCounter, never()).countRecentMessages(anyString());
    }

    @Test
    @DisplayName("채팅방이 없으면 사용자와 최근 메시지를 조회하지 않는다")
    void getAllRooms_EmptyRoomsSkipsUserAndMessageQueries() {
        when(roomRepository.findAll()).thenReturn(List.of());

        RoomsResponse response = roomService.getAllRooms("user@example.com");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
        assertThat(response.getMetadata().getTotal()).isZero();
        verifyNoInteractions(userRepository, recentMessageCounter);
    }

    private Room room(
            String id,
            String creatorId,
            Set<String> participantIds,
            LocalDateTime createdAt) {
        return Room.builder()
            .id(id)
            .name("채팅방 " + id)
            .creator(creatorId)
            .participantIds(participantIds)
            .createdAt(createdAt)
            .build();
    }

    private User user(String id, String name) {
        return User.builder()
            .id(id)
            .name(name)
            .email(id + "@example.com")
            .build();
    }
}
