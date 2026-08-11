package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomJoinHandlerTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRooms userRooms;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;

    private RoomJoinHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RoomJoinHandler(roomRepository, userRooms, roomLeaveHandler);
    }

    @Test
    void handleJoinRoom_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleJoinRoom(client, "room-1");

        verify(client).sendEvent(eq(JOIN_ROOM_ERROR), any());
        verify(roomRepository, never()).existsByIdAndParticipantIdsContaining(any(), any());
        verify(client, never()).joinRoom(any());
    }

    @Test
    void handleJoinRoom_rejectsNonParticipantWithoutJoiningSocketRoom() {
        stubAuthenticatedClient("user-1", "room-1");
        when(roomRepository.existsByIdAndParticipantIdsContaining("room-1", "user-1"))
                .thenReturn(false);

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository, never()).addParticipant(any(), any());
        verify(client).sendEvent(eq(JOIN_ROOM_ERROR), any());
        verify(client, never()).joinRoom(any());
        verify(userRooms, never()).add(any(), any());
    }

    @Test
    void handleJoinRoom_joinsExistingParticipantAndSendsLightweightAcknowledgement() {
        stubAuthenticatedClient("user-1", "room-1");
        when(roomRepository.existsByIdAndParticipantIdsContaining("room-1", "user-1"))
                .thenReturn(true);

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository, never()).addParticipant(any(), any());
        verify(client).joinRoom("room-1");
        verify(userRooms).add("user-1", "room-1");

        ArgumentCaptor<JoinRoomSuccessResponse> responseCaptor =
                ArgumentCaptor.forClass(JoinRoomSuccessResponse.class);
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), responseCaptor.capture());
        JoinRoomSuccessResponse response = responseCaptor.getValue();
        assertEquals("room-1", response.getRoomId());
        assertNull(response.getParticipants());
        assertNull(response.getMessages());
    }

    @Test
    void handleJoinRoom_sendsSuccessForDuplicateJoinWithoutRepositoryOrRedisStoreAccess() {
        stubAuthenticatedClient("user-1", "room-dup");
        when(client.getAllRooms()).thenReturn(Set.of(), Set.of("room-dup"));
        when(roomRepository.existsByIdAndParticipantIdsContaining("room-dup", "user-1"))
                .thenReturn(true);

        handler.handleJoinRoom(client, "room-dup");
        handler.handleJoinRoom(client, "room-dup");

        verify(roomRepository).existsByIdAndParticipantIdsContaining("room-dup", "user-1");
        verify(roomRepository, never()).addParticipant(any(), any());
        verify(client).joinRoom("room-dup");
        verify(userRooms).add("user-1", "room-dup");
        verify(client, times(2)).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
    }

    private void stubAuthenticatedClient(String userId, String roomId) {
        when(client.get("user")).thenReturn(new SocketUser(userId, "tester", "session-1", "socket-1"));
        when(client.getAllRooms()).thenReturn(Set.of());
        when(client.getSessionId()).thenReturn(UUID.nameUUIDFromBytes(roomId.getBytes()));
    }
}
