package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final RoomRepository roomRepository;
    private final UserRooms userRooms;
    @SuppressWarnings("unused")
    private final RoomLeaveHandler roomLeaveHandler;
    private final Set<String> joinsInProgress = ConcurrentHashMap.newKeySet();

    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        String joinKey = null;
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            Set<String> clientRooms = client.getAllRooms();
            if (clientRooms != null && clientRooms.contains(roomId)) {
                sendJoinSuccess(client, roomId);
                log.debug("Socket already joined room. userId={}, roomId={}", userId, roomId);
                return;
            }

            joinKey = userId + ":" + roomId + ":" + client.getSessionId();
            if (!joinsInProgress.add(joinKey)) {
                log.debug("JoinRoom request already in progress. userId={}, roomId={}", userId, roomId);
                return;
            }

            if (!roomRepository.existsByIdAndParticipantIdsContaining(roomId, userId)) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Room access denied"));
                return;
            }

            client.joinRoom(roomId);
            userRooms.add(userId, roomId);
            sendJoinSuccess(client, roomId);

            log.debug("User joined Socket.IO room successfully. userId={}, roomId={}", userId, roomId);
        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "Failed to join room."
            ));
        } finally {
            if (joinKey != null) {
                joinsInProgress.remove(joinKey);
            }
        }
    }

    private void sendJoinSuccess(SocketIOClient client, String roomId) {
        client.sendEvent(JOIN_ROOM_SUCCESS, JoinRoomSuccessResponse.builder()
                .roomId(roomId)
                .activeStreams(Collections.emptyList())
                .build());
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = client.get("user");
        return user != null ? user.id() : null;
    }
}
