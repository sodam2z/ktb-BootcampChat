package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.RoomListItemResponse;
import com.ktb.chatapp.event.RoomActivityEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.event.SessionEndedEvent;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.SESSION_ENDED;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ROOM_ACTIVITY;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ROOM_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocketIOEventListenerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private BroadcastOperations userOperations;
    @Mock private BroadcastOperations roomListOperations;

    private SocketIOEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new SocketIOEventListener(socketIOServer);
    }

    @Test
    void handleSessionEndedEvent_sendsSessionEndedToUserRoom() {
        SessionEndedEvent event =
                new SessionEndedEvent(this, "user-1", "duplicate_login", "ended");
        when(socketIOServer.getRoomOperations("user:user-1")).thenReturn(userOperations);

        listener.handleSessionEndedEvent(event);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(userOperations).sendEvent(eq(SESSION_ENDED), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) payloadCaptor.getValue();
        assertEquals("duplicate_login", payload.get("reason"));
        assertEquals("ended", payload.get("message"));
    }

    @Test
    void handleSessionEndedEvent_swallowsBroadcastException() {
        SessionEndedEvent event =
                new SessionEndedEvent(this, "user-1", "duplicate_login", "ended");
        when(socketIOServer.getRoomOperations("user:user-1")).thenReturn(userOperations);
        doThrow(new RuntimeException("socket down"))
                .when(userOperations)
                .sendEvent(eq(SESSION_ENDED), any());

        listener.handleSessionEndedEvent(event);

        verify(userOperations).sendEvent(eq(SESSION_ENDED), any());
    }

    @Test
    void handleRoomUpdatedEvent_sendsRoomUpdatedToRoomList() {
        RoomListItemResponse roomResponse = RoomListItemResponse.builder()
                .id("room-1")
                .name("Updated room")
                .build();
        RoomUpdatedEvent event = new RoomUpdatedEvent(this, "room-1", roomResponse);
        when(socketIOServer.getRoomOperations("room-list")).thenReturn(roomListOperations);

        listener.handleRoomUpdatedEvent(event);

        verify(roomListOperations).sendEvent(ROOM_UPDATE, roomResponse);
    }

    @Test
    void handleRoomActivityEvent_sendsRecentMessageCountToRoomList() {
        RoomActivityEvent event = new RoomActivityEvent(this, "room-1", 12);
        when(socketIOServer.getRoomOperations("room-list")).thenReturn(roomListOperations);

        listener.handleRoomActivityEvent(event);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomListOperations).sendEvent(eq(ROOM_ACTIVITY), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("room-1", payload.get("_id"));
        assertEquals(12, payload.get("recentMessageCount"));
    }
}
