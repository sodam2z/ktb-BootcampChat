package com.ktb.chatapp.controller;

import com.ktb.chatapp.annotation.RateLimit;
import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RecentMessageCounter;
import com.ktb.chatapp.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "채팅방 (Rooms)", description = "채팅방 생성 및 관리 API - 채팅방 목록 조회, 생성, 참여, 헬스체크")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final RoomService roomService;

    @Value("${spring.profiles.active:production}")
    private String activeProfile;

    // Health Check 엔드포인트
    @Operation(summary = "채팅방 서비스 헬스체크", description = "채팅방 서비스의 상태를 확인합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "서비스 정상",
            content = @Content(schema = @Schema(implementation = HealthResponse.class))),
        @ApiResponse(responseCode = "503", description = "서비스 사용 불가",
            content = @Content(schema = @Schema(implementation = HealthResponse.class)))
    })
    @SecurityRequirement(name = "")
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> healthCheck() {
        try {
            HealthResponse healthResponse = roomService.getHealthStatus();

            // 캐시 비활성화 헤더 설정
            return ResponseEntity
                .status(healthResponse.isSuccess() ? 200 : 503)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(healthResponse);

        } catch (Exception e) {
            log.error("Health check 에러", e);

            HealthResponse errorResponse = HealthResponse.builder()
                .success(false)
                .build();

            return ResponseEntity
                .status(503)
                .cacheControl(CacheControl.noCache())
                .body(errorResponse);
        }
    }

    // 채팅방 목록 페이지 조회
    @Operation(summary = "채팅방 목록 조회", description = "채팅방 목록을 최신순으로 20개씩 조회합니다. Rate Limit이 적용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "채팅방 목록 조회 성공",
            content = @Content(schema = @Schema(implementation = RoomsResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 페이지 번호",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "429", description = "요청 한도 초과",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"요청 한도를 초과했습니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @RateLimit
    public ResponseEntity<?> getAllRooms(
            @Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
            @RequestParam(defaultValue = "0") int page) {

        if (page < 0) {
            return ResponseEntity.badRequest().body(
                StandardResponse.error("페이지 번호는 0 이상이어야 합니다."));
        }

        try {
            RoomsResponse response = roomService.getAllRooms(page);

            // 캐시 설정
            return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)))
                .header("Last-Modified", java.time.Instant.now().toString())
                .body(response);

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);

            // 환경별 에러 처리
            ErrorResponse errorResponse = new ErrorResponse(false, "채팅방 목록을 불러오는데 실패했습니다.");
            if ("development".equals(activeProfile)) {
                // 개발 환경에서는 상세 에러 정보 제공
                errorResponse = ErrorResponse.builder()
                    .success(false)
                    .message("채팅방 목록을 불러오는데 실패했습니다.")
                    .error(Map.of(
                        "code", "ROOMS_FETCH_ERROR",
                        "details", e.getMessage(),
                        "stack", e.getStackTrace()
                    ))
                    .build();
            }

            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @Operation(summary = "채팅방 생성", description = "새로운 채팅방을 생성합니다. 비밀번호를 설정하여 비공개 방을 만들 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "채팅방 생성 성공",
            content = @Content(schema = @Schema(implementation = RoomResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 입력값",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"방 이름은 필수입니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping
    public ResponseEntity<?> createRoom(@Valid @RequestBody CreateRoomRequest createRoomRequest, Principal principal) {
        try {
            if (createRoomRequest.getName() == null || createRoomRequest.getName().trim().isEmpty()) {
                return ResponseEntity.status(400).body(
                    StandardResponse.error("방 이름은 필수입니다.")
                );
            }

            Room savedRoom = roomService.createRoom(createRoomRequest, principal.getName());
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, principal.getName());

            return ResponseEntity.status(201).body(
                Map.of(
                    "success", true,
                    "data", roomResponse
                )
            );

        } catch (Exception e) {
            log.error("방 생성 에러", e);

            String errorMessage = "채팅방 생성에 실패했습니다.";
            if ("development".equals(activeProfile)) {
                errorMessage += " (" + e.getMessage() + ")";
            }

            return ResponseEntity.status(500).body(
                StandardResponse.error(errorMessage)
            );
        }
    }

    @Operation(summary = "채팅방 상세 조회", description = "채팅방 ID로 특정 채팅방의 상세 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "채팅방 조회 성공",
            content = @Content(schema = @Schema(implementation = RoomResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"채팅방을 찾을 수 없습니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @GetMapping("/{roomId}")
    public ResponseEntity<?> getRoomById(@Parameter(description = "채팅방 ID", example = "60d5ec49f1b2c8b9e8c4f2a1") @PathVariable String roomId, Principal principal) {
        try {
            Optional<Room> roomOpt = roomService.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                return ResponseEntity.status(404).body(
                    StandardResponse.error("채팅방을 찾을 수 없습니다.")
                );
            }

            Room room = roomOpt.get();
            RoomResponse roomResponse = mapToRoomResponse(room, principal.getName());

            return ResponseEntity.ok(
                Map.of(
                    "success", true,
                    "data", roomResponse
                )
            );

        } catch (Exception e) {
            log.error("채팅방 조회 에러", e);
            return ResponseEntity.status(500).body(
                StandardResponse.error("채팅방 정보를 불러오는데 실패했습니다.")
            );
        }
    }

    @Operation(summary = "채팅방 참여", description = "채팅방에 참여합니다. 비공개 방인 경우 비밀번호가 필요합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "채팅방 참여 성공",
            content = @Content(schema = @Schema(implementation = JoinRoomSuccessResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "비밀번호가 일치하지 않음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"비밀번호가 일치하지 않습니다.\"}"))),
        @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/{roomId}/join")
    public ResponseEntity<?> joinRoom(
            @Parameter(description = "채팅방 ID", example = "60d5ec49f1b2c8b9e8c4f2a1") @PathVariable String roomId,
            @RequestBody JoinRoomRequest joinRoomRequest,
            Principal principal) {
        try {
            Room joinedRoom = roomService.joinRoom(roomId, joinRoomRequest.getPassword(), principal.getName());

            if (joinedRoom == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(StandardResponse.error("채팅방을 찾을 수 없습니다."));
            }

            RoomResponse roomResponse = mapToRoomResponse(joinedRoom, principal.getName());
            
            return ResponseEntity.ok(
                Map.of(
                    "success", true,
                    "data", roomResponse
                )
            );

        } catch (RuntimeException e) {
            if (e.getMessage().contains("비밀번호")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(StandardResponse.error("비밀번호가 일치하지 않습니다."));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(StandardResponse.error(e.getMessage()));

        } catch (Exception e) {
            log.error("채팅방 참여 에러", e);
            return ResponseEntity.status(500).body(
                StandardResponse.error("채팅방 참여에 실패했습니다.")
            );
        }
    }

    private RoomResponse mapToRoomResponse(Room room, String name) {
        LinkedHashSet<String> responseUserIds = new LinkedHashSet<>(room.getParticipantIds());
        responseUserIds.add(room.getCreator());
        Map<String, User> usersById = loadUsersById(responseUserIds);
        User creator = usersById.get(room.getCreator());
        if (creator == null) {
            throw new RuntimeException("Creator not found for room " + room.getId());
        }
        UserResponse creatorSummary = UserResponse.from(creator);
        List<UserResponse> participantSummaries = room.getParticipantIds()
                .stream()
                .map(usersById::get)
                .filter(java.util.Objects::nonNull)
                .map(UserResponse::from)
                .toList();

        boolean isCreator = room.getCreator().equals(name) || creator.getEmail().equalsIgnoreCase(name);

        int recentMessageCount = recentMessageCounter.countRecentMessages(room.getId());

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .hasPassword(room.isHasPassword())
                .creator(creatorSummary)
                .participants(participantSummaries)
                .createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
                .isCreator(isCreator)
                .recentMessageCount((int) recentMessageCount)
                .build();
    }

    private Map<String, User> loadUsersById(Collection<String> userIds) {
        LinkedHashSet<String> uniqueIds = userIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(uniqueIds)
                .stream()
                .collect(Collectors.toMap(
                        User::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }
}
