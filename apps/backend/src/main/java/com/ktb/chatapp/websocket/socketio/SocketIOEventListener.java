package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.event.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SocketIOEventListener {

    private static final String ROOM_LIST = "room-list";

    private final SocketIOServer socketIOServer;

    @EventListener
    public void handleSessionEndedEvent(SessionEndedEvent event) {
        try {
            socketIOServer.getRoomOperations("user:" + event.getUserId())
                    .sendEvent(SESSION_ENDED, Map.of(
                            "reason", event.getReason(),
                            "message", event.getMessage()
                    ));
            log.info("session_ended 이벤트 발송: userId={}, reason={}", event.getUserId(), event.getReason());
        } catch (Exception e) {
            log.error("session_ended 이벤트 발송 실패: userId={}", event.getUserId(), e);
        }
    }

    @EventListener
    public void handleRoomCreatedEvent(RoomCreatedEvent event) {
        try {
            socketIOServer.getRoomOperations(ROOM_LIST).sendEvent(ROOM_CREATED, event.getRoomResponse());
            log.info("roomCreated 이벤트 발송: roomId={}", event.getRoomResponse().getId());
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발송 실패", e);
        }
    }

    @EventListener
    public void handleRoomUpdatedEvent(RoomUpdatedEvent event) {
        try {
            socketIOServer.getRoomOperations(ROOM_LIST).sendEvent(ROOM_UPDATE, event.getRoomResponse());
            log.info("roomUpdate 이벤트 발송: roomId={}", event.getRoomId());
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }

    @EventListener
    public void handleRoomActivityEvent(RoomActivityEvent event) {
        try {
            sendRoomActivity(
                    socketIOServer.getRoomOperations(ROOM_LIST),
                    event.getRoomId(),
                    event.getRecentMessageCount());
            log.debug("roomActivity 이벤트 발송: roomId={}, recentMessageCount={}",
                    event.getRoomId(), event.getRecentMessageCount());
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }

    @EventListener
    public void handleRoomActivitiesEvent(RoomActivitiesEvent event) {
        try {
            BroadcastOperations roomListOperations = socketIOServer.getRoomOperations(ROOM_LIST);
            event.getRecentMessageCounts().forEach((roomId, recentMessageCount) ->
                    sendRoomActivity(roomListOperations, roomId, recentMessageCount));
            log.debug("roomActivity batch event sent: count={}", event.getRecentMessageCounts().size());
        } catch (Exception e) {
            log.error("roomActivity batch event failed: roomIds={}", event.getRecentMessageCounts().keySet(), e);
        }
    }

    private void sendRoomActivity(
            BroadcastOperations roomListOperations,
            String roomId,
            int recentMessageCount) {
        roomListOperations.sendEvent(ROOM_ACTIVITY, Map.of(
                "_id", roomId,
                "recentMessageCount", recentMessageCount
        ));
    }

    @EventListener
    public void handleAiMessageStartEvent(AiMessageStartEvent event) {
        try {
            Map<String, Object> data = Map.of(
                "messageId", event.getMessageId(),
                "aiType", event.getAiType(),
                "timestamp", event.getStartTime()
            );
            socketIOServer.getRoomOperations(event.getRoomId())
                    .sendEvent(AI_MESSAGE_START, data);
            log.info("aiMessageStart 이벤트 발송: roomId={}, messageId={}",
                    event.getRoomId(), event.getMessageId());
        } catch (Exception e) {
            log.error("aiMessageStart 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }

    @EventListener
    public void handleAiMessageChunkEvent(AiMessageChunkEvent event) {
        try {
            Map<String, Object> data = Map.of(
                "messageId", event.getMessageId(),
                "fullContent", event.getFullContent(),
                "isCodeBlock", event.isCodeBlock(),
                "isComplete", false
            );
            socketIOServer.getRoomOperations(event.getRoomId())
                    .sendEvent(AI_MESSAGE_CHUNK, data);
        } catch (Exception e) {
            log.error("aiMessageChunk 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }

    @EventListener
    public void handleAiMessageCompleteEvent(AiMessageSavedEvent event) {
        
        try {
            Map<String, Object> data = Map.of(
                "_id", event.getSavedMessageId(),
                "content", event.getContent(),
                "aiType", event.getAiType(),
                "timestamp", event.getStartTime()
            );
            socketIOServer.getRoomOperations(event.getRoomId())
                    .sendEvent(AI_MESSAGE_COMPLETE, data);
            log.info("aiMessageComplete 이벤트 발송: roomId={}, messageId={}",
                    event.getRoomId(), event.getSavedMessageId());
        } catch (Exception e) {
            log.error("aiMessageComplete 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }

    @EventListener
    public void handleAiMessageErrorEvent(AiMessageErrorEvent event) {
        try {
            Map<String, Object> data = Map.of(
                "messageId", event.getMessageId(),
                "error", event.getErrorMessage(),
                "aiType", event.getAiType()
            );
            socketIOServer.getRoomOperations(event.getRoomId())
                    .sendEvent(AI_MESSAGE_ERROR, data);
            log.error("aiMessageError 이벤트 발송: roomId={}, messageId={}, error={}",
                    event.getRoomId(), event.getMessageId(), event.getErrorMessage());
        } catch (Exception e) {
            log.error("aiMessageError 이벤트 발송 실패: roomId={}", event.getRoomId(), e);
        }
    }
}
