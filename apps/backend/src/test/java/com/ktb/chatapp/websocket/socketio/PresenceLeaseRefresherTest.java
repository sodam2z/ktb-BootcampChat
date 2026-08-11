package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.PongListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceLeaseRefresherTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private SocketIOClient client;

    @Test
    void pongRefreshesOnlyAuthenticatedSocketPresence() {
        SocketUser user = new SocketUser("user-1", "name", "session-1", "socket-1");
        ArgumentCaptor<PongListener> listener = ArgumentCaptor.forClass(PongListener.class);
        new PresenceLeaseRefresher(socketIOServer, connectedUsers);
        verify(socketIOServer).addPongListener(listener.capture());

        when(client.get("user")).thenReturn(user);
        listener.getValue().onPong(client);

        verify(connectedUsers).refreshIfCurrent(user);
    }

    @Test
    void pongWithoutAuthenticatedUserDoesNotRefreshPresence() {
        ArgumentCaptor<PongListener> listener = ArgumentCaptor.forClass(PongListener.class);
        new PresenceLeaseRefresher(socketIOServer, connectedUsers);
        verify(socketIOServer).addPongListener(listener.capture());

        when(client.get("user")).thenReturn(null);
        listener.getValue().onPong(client);

        verify(connectedUsers, never()).refreshIfCurrent(org.mockito.ArgumentMatchers.any());
    }
}
