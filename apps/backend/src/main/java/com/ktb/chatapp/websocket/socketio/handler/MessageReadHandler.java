package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {
    
    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final UserRooms userRooms;
    private final RoomRepository roomRepository;
    
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
                return;
            }

            List<String> messageIds = normalizeMessageIds(data.getMessageIds());
            if (messageIds.isEmpty()) {
                return;
            }

            Map<String, List<String>> messageIdsByRoomId = groupMessageIdsByRoom(messageIds);
            if (messageIdsByRoomId.isEmpty()) {
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            boolean denied = false;
            boolean updated = false;
            for (var entry : messageIdsByRoomId.entrySet()) {
                String roomId = entry.getKey();
                List<String> roomMessageIds = entry.getValue();

                if (!hasRoomAccess(userId, roomId)) {
                    denied = true;
                    continue;
                }

                long modifiedCount = messageReadStatusService.updateReadStatus(roomMessageIds, userId, roomId);
                if (modifiedCount == 0) {
                    continue;
                }

                updated = true;
                MessagesReadResponse response = new MessagesReadResponse(userId, roomMessageIds);
                socketIOServer.getRoomOperations(roomId)
                        .sendEvent(MESSAGES_READ, response);
            }

            if (!updated && denied) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
            }

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }

    private List<String> normalizeMessageIds(List<String> messageIds) {
        var uniqueMessageIds = new LinkedHashSet<String>();
        for (String messageId : messageIds) {
            if (messageId != null && !messageId.isBlank()) {
                uniqueMessageIds.add(messageId);
            }
        }
        return new ArrayList<>(uniqueMessageIds);
    }

    private Map<String, List<String>> groupMessageIdsByRoom(List<String> messageIds) {
        Map<String, String> roomIdByMessageId = new HashMap<>();
        messageRepository.findRoomsOnlyByIdIn(messageIds).forEach(message -> {
            if (message.getId() != null && message.getRoomId() != null && !message.getRoomId().isBlank()) {
                roomIdByMessageId.put(message.getId(), message.getRoomId());
            }
        });

        Map<String, List<String>> messageIdsByRoomId = new LinkedHashMap<>();
        for (String messageId : messageIds) {
            String roomId = roomIdByMessageId.get(messageId);
            if (roomId != null) {
                messageIdsByRoomId.computeIfAbsent(roomId, ignored -> new ArrayList<>()).add(messageId);
            }
        }
        return messageIdsByRoomId;
    }

    private boolean hasRoomAccess(String userId, String roomId) {
        if (userRooms.isInRoom(userId, roomId)) {
            return true;
        }
        return roomRepository.existsByIdAndParticipantIdsContaining(roomId, userId);
    }
}
