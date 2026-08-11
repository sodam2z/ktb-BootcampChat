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
import java.util.List;
import java.util.Optional;
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
        verify(messageReadStatusService, never()).updateReadStatus(any(), any());
    }

    @Test
    void handleMarkAsRead_updatesStatusAndBroadcasts() {
        MarkAsReadRequest request = request("message-1");
        Message message = Message.builder().id("message-1").roomId("room-1").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findRoomOnlyById("message-1")).thenReturn(Optional.of(message));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageReadStatusService.updateReadStatus(List.of("message-1"), "user-1"))
                .thenReturn(1L);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService).updateReadStatus(List.of("message-1"), "user-1");
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("user-1", response.getUserId());
        assertEquals(List.of("message-1"), response.getMessageIds());
    }

    private MarkAsReadRequest request(String messageId) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setMessageIds(List.of(messageId));
        return request;
    }
}
