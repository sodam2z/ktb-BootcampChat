package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps Redis presence alive while the owning Socket.IO connection responds to pings. */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class PresenceLeaseRefresher {

    private static final String LAST_REFRESH_AT = "presenceLeaseRefreshedAt";
    private static final long REFRESH_INTERVAL_MILLIS = java.time.Duration.ofMinutes(5).toMillis();

    private final ConnectedUsers connectedUsers;

    public PresenceLeaseRefresher(SocketIOServer socketIOServer, ConnectedUsers connectedUsers) {
        this.connectedUsers = connectedUsers;
        socketIOServer.addPongListener(this::onPong);
    }

    void onPong(SocketIOClient client) {
        SocketUser socketUser = client.get("user");
        Long lastRefreshAt = client.get(LAST_REFRESH_AT);
        long now = System.currentTimeMillis();
        if (socketUser == null || (lastRefreshAt != null && now - lastRefreshAt < REFRESH_INTERVAL_MILLIS)) {
            return;
        }

        client.set(LAST_REFRESH_AT, now);
        if (!connectedUsers.refreshIfCurrent(socketUser)) {
            log.debug("Skipped stale presence lease refresh: userId={}, socketId={}",
                    socketUser.id(), socketUser.socketId());
        }
    }
}
