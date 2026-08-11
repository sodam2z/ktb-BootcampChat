package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.PARTICIPANTS_UPDATE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomJoinHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRooms userRooms;
    @Mock private MessageLoader messageLoader;
    @Mock private MessageResponseMapper messageResponseMapper;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private RoomJoinHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RoomJoinHandler(
                socketIOServer,
                messageRepository,
                roomRepository,
                userRepository,
                userRooms,
                messageLoader,
                messageResponseMapper,
                roomLeaveHandler);
    }

    @Test
    void handleJoinRoom_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleJoinRoom(client, "room-1");

        verify(client).sendEvent(eq(JOIN_ROOM_ERROR), any());
    }

    @Test
    void handleJoinRoom_addsParticipantLoadsMessagesAndBroadcasts() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-1").name("room").participantIds(Set.of("user-1")).build();
        MessageResponse joinMessageResponse = MessageResponse.builder()
                .id("message-1")
                .roomId("room-1")
                .content("tester님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(1L)
                .build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRepository.findAllById(Set.of("user-1"))).thenReturn(List.of(user));
        when(roomRepository.addParticipant("room-1", "user-1")).thenReturn(1L);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-1");
            message.setTimestamp(LocalDateTime.now());
            return message;
        });
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);
        when(messageResponseMapper.mapToMessageResponse(any(Message.class), eq(null)))
                .thenReturn(joinMessageResponse);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository).addParticipant("room-1", "user-1");
        verify(client).joinRoom("room-1");
        verify(userRooms).add("user-1", "room-1");
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
        verify(roomOperations).sendEvent(MESSAGE, joinMessageResponse);
        verify(roomOperations, timeout(1000)).sendEvent(eq(PARTICIPANTS_UPDATE), any());
    }

    @Test
    void handleJoinRoom_sendsSuccessForDuplicateJoinRequest() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-dup").name("room").participantIds(Set.of("user-1")).build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(client.getSessionId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(userRooms.isInRoom("user-1", "room-dup")).thenReturn(true);
        when(roomRepository.findById("room-dup")).thenReturn(Optional.of(room));
        when(userRepository.findAllById(Set.of("user-1"))).thenReturn(List.of(user));
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);

        handler.handleJoinRoom(client, "room-dup");
        handler.handleJoinRoom(client, "room-dup");

        verify(roomRepository, never()).addParticipant("room-dup", "user-1");
        verify(messageRepository, never()).save(any(Message.class));
        verify(client, times(2)).joinRoom("room-dup");
        verify(userRooms, times(2)).add("user-1", "room-dup");
        verify(client, times(2)).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
    }
}
