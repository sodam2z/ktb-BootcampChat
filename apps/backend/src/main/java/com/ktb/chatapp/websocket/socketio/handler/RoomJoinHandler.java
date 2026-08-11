package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.PARTICIPANTS_UPDATE;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private static final long PARTICIPANT_UPDATE_DEBOUNCE_MILLIS = 200;
    private static final long JOIN_GUARD_TTL_MINUTES = 10;
    private static final Set<String> JOIN_RESPONSE_GUARD = ConcurrentHashMap.newKeySet();

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    @SuppressWarnings("unused")
    private final RoomLeaveHandler roomLeaveHandler;
    private final Map<String, List<UserResponse>> pendingParticipantUpdates = new ConcurrentHashMap<>();
    private final Set<String> scheduledParticipantUpdates = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService participantUpdateExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "participant-update-debouncer");
                thread.setDaemon(true);
                return thread;
            });

    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            String joinKey = userId + ":" + roomId + ":" + client.getSessionId();
            boolean duplicateJoinRequest = !JOIN_RESPONSE_GUARD.add(joinKey);
            if (duplicateJoinRequest) {
                log.debug("Handling duplicate joinRoom event without side effects. userId={}, roomId={}", userId, roomId);
            } else {
                participantUpdateExecutor.schedule(
                        () -> JOIN_RESPONSE_GUARD.remove(joinKey),
                        JOIN_GUARD_TTL_MINUTES,
                        TimeUnit.MINUTES);
            }

            boolean alreadyTrackedInRoom = userRooms.isInRoom(userId, roomId);
            boolean newParticipant = !duplicateJoinRequest
                    && !alreadyTrackedInRoom
                    && roomRepository.addParticipant(roomId, userId) > 0;

            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            Optional<Room> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Room not found"));
                return;
            }

            Room room = roomOpt.get();
            if (room.getParticipantIds() == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Room access denied"));
                return;
            }

            Message joinMessage = null;
            if (newParticipant) {
                joinMessage = Message.builder()
                        .roomId(roomId)
                        .content(userName + " joined the room.")
                        .type(MessageType.system)
                        .timestamp(LocalDateTime.now())
                        .mentions(new ArrayList<>())
                        .reactions(new HashMap<>())
                        .readers(new ArrayList<>())
                        .metadata(new HashMap<>())
                        .build();

                joinMessage = messageRepository.save(joinMessage);
            }

            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            List<UserResponse> participants = loadParticipants(room.getParticipantIds());

            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(messageLoadResult.getMessages())
                    .hasMore(messageLoadResult.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            if (newParticipant && joinMessage != null) {
                socketIOServer.getRoomOperations(roomId)
                        .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

                scheduleParticipantsUpdate(roomId, participants);
            }

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                    userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "Failed to join room."
            ));
        }
    }

    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }

    private List<UserResponse> loadParticipants(Collection<String> participantIds) {
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }
        Map<String, User> usersById = StreamSupport.stream(userRepository.findAllById(participantIds).spliterator(), false)
                .collect(Collectors.toMap(
                        User::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return participantIds.stream()
                .map(usersById::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();
    }

    private void scheduleParticipantsUpdate(String roomId, List<UserResponse> participants) {
        pendingParticipantUpdates.put(roomId, List.copyOf(participants));
        if (scheduledParticipantUpdates.add(roomId)) {
            participantUpdateExecutor.schedule(
                    () -> flushParticipantsUpdate(roomId),
                    PARTICIPANT_UPDATE_DEBOUNCE_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void flushParticipantsUpdate(String roomId) {
        try {
            List<UserResponse> participants = pendingParticipantUpdates.remove(roomId);
            if (participants != null) {
                socketIOServer.getRoomOperations(roomId)
                        .sendEvent(PARTICIPANTS_UPDATE, participants);
            }
        } finally {
            scheduledParticipantUpdates.remove(roomId);
            if (pendingParticipantUpdates.containsKey(roomId)) {
                scheduleParticipantsUpdate(roomId, pendingParticipantUpdates.get(roomId));
            }
        }
    }

    @PreDestroy
    void shutdown() {
        participantUpdateExecutor.shutdownNow();
    }
}
