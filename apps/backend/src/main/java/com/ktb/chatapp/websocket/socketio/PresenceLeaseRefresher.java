package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps Redis presence alive while the owning Socket.IO connection responds to pings. */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class PresenceLeaseRefresher {

    static final String LAST_PRESENCE_REFRESH_AT = "lastPresenceRefreshAt";

    private final ConnectedUsers connectedUsers;
    private final long refreshIntervalMillis;

    public PresenceLeaseRefresher(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            @Value("${socketio.presence-refresh-interval:1m}") Duration refreshInterval) {
        this.connectedUsers = connectedUsers;
        this.refreshIntervalMillis = Math.max(0, refreshInterval.toMillis());
        socketIOServer.addPongListener(this::onPong);
    }

    void onPong(SocketIOClient client) {
        SocketUser socketUser = client.get("user");
        if (socketUser == null || !shouldRefreshPresence(client)) {
            return;
        }

        if (connectedUsers.refreshIfCurrent(socketUser)) {
            client.set(LAST_PRESENCE_REFRESH_AT, System.currentTimeMillis());
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Skipped stale presence lease refresh: userId={}, socketId={}",
                    socketUser.id(), socketUser.socketId());
        }
    }

    private boolean shouldRefreshPresence(SocketIOClient client) {
        Long lastRefreshAt = client.get(LAST_PRESENCE_REFRESH_AT);
        return lastRefreshAt == null
                || refreshIntervalMillis == 0
                || System.currentTimeMillis() - lastRefreshAt >= refreshIntervalMillis;
    }
}
