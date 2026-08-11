package com.ktb.chatapp.websocket.socketio;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.PongListener;

@ExtendWith(MockitoExtension.class)
class PresenceLeaseRefresherTest {

    @Mock
    private SocketIOServer socketIOServer;
    @Mock
    private ConnectedUsers connectedUsers;
    @Mock
    private SocketIOClient client;

    @Test
    void pongRefreshesOnlyAuthenticatedSocketPresence() {
        SocketUser user = new SocketUser("user-1", "name", "session-1", "socket-1");
        ArgumentCaptor<PongListener> listener = ArgumentCaptor.forClass(PongListener.class);
        new PresenceLeaseRefresher(socketIOServer, connectedUsers, Duration.ZERO);
        verify(socketIOServer).addPongListener(listener.capture());

        when(client.get("user")).thenReturn(user);
        when(connectedUsers.refreshIfCurrent(user)).thenReturn(true);
        listener.getValue().onPong(client);

        verify(connectedUsers).refreshIfCurrent(user);
        verify(client).set(eq(PresenceLeaseRefresher.LAST_PRESENCE_REFRESH_AT), anyLong());
    }

    @Test
    void pongWithoutAuthenticatedUserDoesNotRefreshPresence() {
        ArgumentCaptor<PongListener> listener = ArgumentCaptor.forClass(PongListener.class);
        new PresenceLeaseRefresher(socketIOServer, connectedUsers, Duration.ZERO);
        verify(socketIOServer).addPongListener(listener.capture());

        when(client.get("user")).thenReturn(null);
        listener.getValue().onPong(client);

        verify(connectedUsers, never()).refreshIfCurrent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pongWithinRefreshIntervalDoesNotTouchPresenceStore() {
        SocketUser user = new SocketUser("user-1", "name", "session-1", "socket-1");
        ArgumentCaptor<PongListener> listener = ArgumentCaptor.forClass(PongListener.class);
        new PresenceLeaseRefresher(socketIOServer, connectedUsers, Duration.ofMinutes(1));
        verify(socketIOServer).addPongListener(listener.capture());

        when(client.get("user")).thenReturn(user);
        when(client.get(PresenceLeaseRefresher.LAST_PRESENCE_REFRESH_AT))
                .thenReturn(System.currentTimeMillis());

        listener.getValue().onPong(client);

        verify(connectedUsers, never()).refreshIfCurrent(user);
    }
}
