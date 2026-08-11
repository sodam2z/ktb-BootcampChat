package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomListSubscriptionHandlerTest {

    private final RoomListSubscriptionHandler handler = new RoomListSubscriptionHandler();
    private final SocketIOClient client = mock(SocketIOClient.class);

    @Test
    void authenticatedClientCanSubscribeAndUnsubscribe() {
        when(client.get("user")).thenReturn(new SocketUser("user-1", "name", "session-1", "socket-1"));

        handler.joinRoomList(client);
        handler.leaveRoomList(client);

        verify(client).joinRoom("room-list");
        verify(client).leaveRoom("room-list");
    }

    @Test
    void unauthenticatedClientCannotSubscribe() {
        handler.joinRoomList(client);

        verify(client, never()).joinRoom("room-list");
    }
}
