package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessageResponse;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.PARTICIPANTS_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomLeaveHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRooms userRooms;
    @Mock private MessageResponseMapper messageResponseMapper;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private RoomLeaveHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RoomLeaveHandler(
                socketIOServer,
                messageRepository,
                roomRepository,
                userRepository,
                userRooms,
                messageResponseMapper);
    }

    @Test
    void handleLeaveRoom_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleLeaveRoom(client, "room-1");

        verify(client).sendEvent(eq(ERROR), any());
        verify(roomRepository, never()).removeParticipant(any(), any());
    }

    @Test
    void handleLeaveRoom_removesParticipantAndBroadcasts() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        User remainingUser = User.builder().id("user-2").name("remaining").email("remaining@example.com").build();
        Room roomBeforeLeave = Room.builder()
                .id("room-1")
                .name("room")
                .participantIds(Set.of("user-1", "user-2"))
                .build();
        Room roomAfterLeave = Room.builder()
                .id("room-1")
                .name("room")
                .participantIds(Set.of("user-2"))
                .build();
        MessageResponse leaveMessageResponse = MessageResponse.builder()
                .id("message-1")
                .roomId("room-1")
                .content("tester님이 퇴장하였습니다.")
                .type(MessageType.system)
                .timestamp(1L)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(roomRepository.removeParticipant("room-1", "user-1")).thenReturn(1L);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.findById("user-2")).thenReturn(Optional.of(remainingUser));
        when(roomRepository.findById("room-1"))
                .thenReturn(Optional.of(roomBeforeLeave), Optional.of(roomAfterLeave));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-1");
            message.setTimestamp(LocalDateTime.now());
            return message;
        });
        when(messageResponseMapper.mapToMessageResponse(any(Message.class), eq(null)))
                .thenReturn(leaveMessageResponse);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleLeaveRoom(client, "room-1");

        verify(roomRepository).removeParticipant("room-1", "user-1");
        verify(client).leaveRoom("room-1");
        verify(userRooms).remove("user-1", "room-1");
        verify(roomOperations).sendEvent(MESSAGE, leaveMessageResponse);
        ArgumentCaptor<Object> participantsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(PARTICIPANTS_UPDATE), participantsCaptor.capture());
        @SuppressWarnings("unchecked")
        var participants = (java.util.List<UserResponse>) participantsCaptor.getValue();
        assertEquals(List.of("user-2"), participants.stream().map(UserResponse::getId).toList());
        verify(roomOperations, never()).sendEvent(eq("userLeft"), any());
    }
}
