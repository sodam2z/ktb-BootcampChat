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

    private final ConnectedUsers connectedUsers;

    public PresenceLeaseRefresher(SocketIOServer socketIOServer, ConnectedUsers connectedUsers) {
        this.connectedUsers = connectedUsers;
        socketIOServer.addPongListener(this::onPong);
    }

    void onPong(SocketIOClient client) {
        SocketUser socketUser = client.get("user");
        if (socketUser != null && !connectedUsers.refreshIfCurrent(socketUser)) {
            log.debug("Skipped stale presence lease refresh: userId={}, socketId={}",
                    socketUser.id(), socketUser.socketId());
        }
    }
}
