package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserConnectionLocks;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private SocketIOClient client;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                new UserConnectionLocks(),
                roomJoinHandler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list"));
    }

    @Test
    void onDisconnect_removesOnlyCurrentConnectionAndPreservesRoomMembership() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(connectedUsers).del(user.id());
        verify(userRooms, never()).remove(user.id(), "room-1");
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }

    @Test
    void onDisconnect_staleSocketDoesNotDeleteActiveConnectionOrRoomMembership() {
        UUID staleSocketId = UUID.randomUUID();
        SocketUser staleUser = new SocketUser(
                "user-1", "tester", "session-1", staleSocketId.toString());
        SocketUser activeUser = new SocketUser(
                "user-1", "tester", "session-2", UUID.randomUUID().toString());
        when(client.get("user")).thenReturn(staleUser);
        when(client.getSessionId()).thenReturn(staleSocketId);
        when(connectedUsers.get(staleUser.id())).thenReturn(activeUser);

        handler.onDisconnect(client);

        verify(connectedUsers, never()).del(staleUser.id());
        verify(userRooms, never()).remove(staleUser.id(), "room-1");
        verify(client).leaveRooms(Set.of("user:" + staleUser.id(), "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }
}
