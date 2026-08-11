package com.ktb.chatapp.websocket.socketio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectedUsersTest {

    @Mock private ChatDataStore store;

    @Test
    void refreshesLeaseOnlyForCurrentOwner() {
        SocketUser current = new SocketUser("user-1", "name", "session-1", "socket-1");
        String key = "conn_users:userid:user-1";
        when(store.get(key, SocketUser.class)).thenReturn(Optional.of(current));
        when(store.refresh(key)).thenReturn(true);

        assertThat(new ConnectedUsers(store).refreshIfCurrent(current)).isTrue();
        verify(store).refresh(key);
    }

    @Test
    void staleSocketCannotRefreshNewOwnersLease() {
        SocketUser stale = new SocketUser("user-1", "name", "session-1", "socket-1");
        SocketUser current = new SocketUser("user-1", "name", "session-2", "socket-2");
        String key = "conn_users:userid:user-1";
        when(store.get(key, SocketUser.class)).thenReturn(Optional.of(current));

        assertThat(new ConnectedUsers(store).refreshIfCurrent(stale)).isFalse();
        verify(store, never()).refresh(key);
    }
}
