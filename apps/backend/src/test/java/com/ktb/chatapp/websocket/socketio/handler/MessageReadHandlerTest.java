package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageReadStatusService messageReadStatusService;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRooms userRooms;
    @Mock private RoomRepository roomRepository;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReadHandler(
                socketIOServer,
                messageReadStatusService,
                messageRepository,
                userRooms,
                roomRepository);
    }

    @Test
    void handleMarkAsRead_rejectsUnauthorizedClient() {
        MarkAsReadRequest request = request("message-1");
        when(client.get("user")).thenReturn(null);

        handler.handleMarkAsRead(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(messageReadStatusService, never()).updateReadStatus(any(), any(), any());
    }

    @Test
    void handleMarkAsRead_updatesStatusAndBroadcasts() {
        MarkAsReadRequest request = request("message-1");
        Message message = Message.builder().id("message-1").roomId("room-1").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findRoomsOnlyByIdIn(List.of("message-1"))).thenReturn(List.of(message));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageReadStatusService.updateReadStatus(List.of("message-1"), "user-1", "room-1"))
                .thenReturn(1L);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService).updateReadStatus(List.of("message-1"), "user-1", "room-1");
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("user-1", response.getUserId());
        assertEquals(List.of("message-1"), response.getMessageIds());
    }

    @Test
    void handleMarkAsRead_deduplicatesMessageIdsBeforeUpdate() {
        MarkAsReadRequest request = request("message-1", "message-1", "", null);
        Message message = Message.builder().id("message-1").roomId("room-1").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findRoomsOnlyByIdIn(List.of("message-1"))).thenReturn(List.of(message));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageReadStatusService.updateReadStatus(List.of("message-1"), "user-1", "room-1"))
                .thenReturn(1L);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageRepository).findRoomsOnlyByIdIn(List.of("message-1"));
        verify(messageReadStatusService).updateReadStatus(List.of("message-1"), "user-1", "room-1");
    }

    @Test
    void handleMarkAsRead_groupsMixedRoomBatchByRoom() {
        MarkAsReadRequest request = request("message-1", "message-2");
        Message roomOneMessage = Message.builder().id("message-1").roomId("room-1").build();
        Message roomTwoMessage = Message.builder().id("message-2").roomId("room-2").build();
        BroadcastOperations roomTwoOperations = org.mockito.Mockito.mock(BroadcastOperations.class);

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findRoomsOnlyByIdIn(List.of("message-1", "message-2")))
                .thenReturn(List.of(roomOneMessage, roomTwoMessage));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(userRooms.isInRoom("user-1", "room-2")).thenReturn(true);
        when(messageReadStatusService.updateReadStatus(List.of("message-1"), "user-1", "room-1"))
                .thenReturn(1L);
        when(messageReadStatusService.updateReadStatus(List.of("message-2"), "user-1", "room-2"))
                .thenReturn(1L);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);
        when(socketIOServer.getRoomOperations("room-2")).thenReturn(roomTwoOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService).updateReadStatus(List.of("message-1"), "user-1", "room-1");
        verify(messageReadStatusService).updateReadStatus(List.of("message-2"), "user-1", "room-2");
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), any());
        verify(roomTwoOperations).sendEvent(eq(MESSAGES_READ), any());
    }

    @Test
    void handleMarkAsRead_rejectsWhenUserHasNoRoomAccess() {
        MarkAsReadRequest request = request("message-1");
        Message message = Message.builder().id("message-1").roomId("room-1").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findRoomsOnlyByIdIn(List.of("message-1"))).thenReturn(List.of(message));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);
        when(roomRepository.existsByIdAndParticipantIdsContaining("room-1", "user-1")).thenReturn(false);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService, never()).updateReadStatus(any(), any(), any());
        verify(client).sendEvent(eq(ERROR), any());
    }

    private MarkAsReadRequest request(String... messageIds) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setMessageIds(Arrays.asList(messageIds));
        return request;
    }
}
