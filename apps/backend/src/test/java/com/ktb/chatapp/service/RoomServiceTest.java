package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomListItemResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    @DisplayName("채팅방을 최신순으로 20개씩 조회하고 현재 페이지 범위만 집계한다")
    void getAllRooms_LoadsOnlyRequestedPageInDescendingOrder() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 10, 10, 0);
        Room room2 = room("room-2", Set.of("user-1", "user-2"), createdAt);
        Room room1 = room("room-1", Set.of("user-1"), createdAt);
        PageRequest requestedPage = PageRequest.of(1, RoomService.ROOM_LIST_PAGE_SIZE);

        when(roomRepository.findAll(any(Pageable.class))).thenReturn(
            new PageImpl<>(List.of(room2, room1), requestedPage, 42));
        when(recentMessageCounter.countRecentMessagesByRoomIds(anySet()))
            .thenReturn(Map.of("room-2", 5));

        RoomsResponse response = roomService.getAllRooms(1);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).extracting(RoomListItemResponse::getId)
            .containsExactly("room-2", "room-1");
        assertThat(response.getData()).extracting(RoomListItemResponse::getParticipantCount)
            .containsExactly(2, 1);
        assertThat(response.getData()).extracting(RoomListItemResponse::getRecentMessageCount)
            .containsExactly(5, 0);
        assertThat(response.getMetadata().getTotal()).isEqualTo(42);
        assertThat(response.getMetadata().getPage()).isEqualTo(1);
        assertThat(response.getMetadata().getPageSize()).isEqualTo(20);
        assertThat(response.getMetadata().getTotalPages()).isEqualTo(3);
        assertThat(response.getMetadata().isHasMore()).isTrue();
        assertThat(response.getMetadata().getCurrentCount()).isEqualTo(2);
        assertThat(response.getMetadata().getSort().getField()).isEqualTo("createdAt");
        assertThat(response.getMetadata().getSort().getOrder()).isEqualTo("DESC");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(roomRepository).findAll(pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        verify(recentMessageCounter).countRecentMessagesByRoomIds(Set.of("room-1", "room-2"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("범위를 벗어난 빈 페이지는 추가 조회 없이 메타데이터와 함께 반환한다")
    void getAllRooms_OutOfRangePageSkipsRelatedQueries() {
        PageRequest requestedPage = PageRequest.of(3, RoomService.ROOM_LIST_PAGE_SIZE);
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), requestedPage, 2));

        RoomsResponse response = roomService.getAllRooms(3);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
        assertThat(response.getMetadata().getTotal()).isEqualTo(2);
        assertThat(response.getMetadata().getPage()).isEqualTo(3);
        assertThat(response.getMetadata().getCurrentCount()).isZero();
        assertThat(response.getMetadata().isHasMore()).isFalse();
        verifyNoInteractions(userRepository, recentMessageCounter);
    }

    @Test
    @DisplayName("마지막 페이지는 다음 페이지가 없다고 표시한다")
    void getAllRooms_LastPageHasNoMorePages() {
        Room room = room(
            "room-21",
            Set.of("user-1"),
            LocalDateTime.of(2026, 8, 10, 10, 0));
        PageRequest requestedPage = PageRequest.of(1, RoomService.ROOM_LIST_PAGE_SIZE);
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(room), requestedPage, 21));
        when(recentMessageCounter.countRecentMessagesByRoomIds(anySet())).thenReturn(Map.of());

        RoomsResponse response = roomService.getAllRooms(1);

        assertThat(response.getMetadata().getTotalPages()).isEqualTo(2);
        assertThat(response.getMetadata().isHasMore()).isFalse();
        verify(userRepository, never()).findAllById(anySet());
    }

    private Room room(String id, Set<String> participantIds, LocalDateTime createdAt) {
        return Room.builder()
            .id(id)
            .name("채팅방 " + id)
            .creator("user-1")
            .participantIds(participantIds)
            .createdAt(createdAt)
            .build();
    }
}
