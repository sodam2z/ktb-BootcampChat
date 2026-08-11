package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    public static final int ROOM_LIST_PAGE_SIZE = 20;

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<Integer, CachedRoomsResponse> roomListCache = new ConcurrentHashMap<>();

    @Value("${rooms.list-cache-ttl:2s}")
    private Duration roomListCacheTtl;

    public RoomsResponse getAllRooms(int page) {
        long now = System.nanoTime();
        CachedRoomsResponse cached = roomListCache.get(page);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.response();
        }

        try {
            PageRequest pageRequest = PageRequest.of(
                page,
                ROOM_LIST_PAGE_SIZE,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
            Page<Room> roomPage = roomRepository.findAll(pageRequest);
            List<Room> rooms = roomPage.getContent();
            if (rooms.isEmpty()) {
                return successfulRoomsResponse(List.of(), roomPage);
            }

            Set<String> roomIds = rooms.stream()
                .map(Room::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            Map<String, Integer> recentMessageCounts =
                recentMessageCounter.countRecentMessagesByRoomIds(roomIds);

            List<RoomListItemResponse> roomResponses = rooms.stream()
                .map(room -> mapToRoomListItemResponse(
                    room,
                    recentMessageCounts.getOrDefault(room.getId(), 0)))
                .toList();

            RoomsResponse response = successfulRoomsResponse(roomResponses, roomPage);
            cacheRoomsResponse(page, response, now);
            return response;

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        roomListCache.clear();
        
        // Publish event for room created
        try {
            RoomListItemResponse roomResponse = mapToRoomListItemResponse(savedRoom, 0);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }
        
        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        
        // Publish event for room updated
        try {
            RoomListItemResponse roomResponse = mapToRoomListItemResponse(
                room,
                recentMessageCounter.countRecentMessages(room.getId()));
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    private RoomListItemResponse mapToRoomListItemResponse(Room room, int recentMessageCount) {
        return RoomListItemResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .participantCount(room.getParticipantCount())
            .createdAtDateTime(room.getCreatedAt())
            .recentMessageCount(recentMessageCount)
            .build();
    }

    private RoomsResponse successfulRoomsResponse(
            List<RoomListItemResponse> roomResponses,
            Page<Room> roomPage) {
        PageMetadata metadata = PageMetadata.builder()
            .total(roomPage.getTotalElements())
            .page(roomPage.getNumber())
            .pageSize(ROOM_LIST_PAGE_SIZE)
            .totalPages(roomPage.getTotalPages())
            .hasMore(roomPage.hasNext())
            .currentCount(roomResponses.size())
            .sort(PageMetadata.SortInfo.builder()
                .field("createdAt")
                .order("DESC")
                .build())
            .build();

        return RoomsResponse.builder()
            .success(true)
            .data(roomResponses)
            .metadata(metadata)
            .build();
    }

    private void cacheRoomsResponse(int page, RoomsResponse response, long nowNanos) {
        long ttlNanos = roomListCacheTtl != null ? roomListCacheTtl.toNanos() : 0;
        if (ttlNanos <= 0) {
            return;
        }
        roomListCache.put(page, new CachedRoomsResponse(response, nowNanos + ttlNanos));
    }

    private record CachedRoomsResponse(RoomsResponse response, long expiresAtNanos) {
    }
}
