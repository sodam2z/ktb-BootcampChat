package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserConnectionLocks;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserConnectionLocks userConnectionLocks;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserConnectionLocks userConnectionLocks,
            MeterRegistry meterRegistry) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userConnectionLocks = userConnectionLocks;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.connections", this::localConnectionCount)
                .description("Current Socket.IO connections on this instance")
                .register(meterRegistry);
    }
    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();
        
        try {
            client.set("user", user);

            userConnectionLocks.withLock(userId, () -> {
                // Atomic replacement works across nodes when RedisChatDataStore is active.
                SocketUser previousUser = connectedUsers.replace(userId, user);
                notifyDuplicateLogin(client, previousUser);
            });

            if (log.isDebugEnabled()) {
                log.debug("Socket.IO user connected: {} ({}) - Local connections: {}",
                        getUserName(client), userId, localConnectionCount());
            }

            // Global room-list broadcasts are opt-in. Joining every authenticated
            // socket makes concurrent room creation O(connections * creations).
            client.joinRooms(Set.of("user:" + userId, "socket:" + user.socketId()));
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);
        
        try {
            if (userId == null) {
                return;
            }
            
            String socketId = client.getSessionId().toString();

            userConnectionLocks.withLock(userId, () -> {
                // Disconnect only releases connection ownership. Room membership
                // is persistent and is removed exclusively by an explicit LEAVE_ROOM.
                if (!connectedUsers.delIfCurrent(userId, getUserDto(client))) {
                    log.debug("Socket.IO disconnect: User {} has a different active connection. "
                            + "Skipping connection cleanup.", userId);
                }
            });

            client.leaveRooms(Set.of("user:" + userId, "socket:" + socketId));
            client.del("user");

            if (log.isDebugEnabled()) {
                log.debug("Socket.IO user disconnected: {} ({}) - Local connections: {}",
                        userName, userId, localConnectionCount());
            }
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }

    private int localConnectionCount() {
        var clients = socketIOServer.getAllClients();
        return clients != null ? clients.size() : 0;
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    private void notifyDuplicateLogin(SocketIOClient client, SocketUser previousUser) {
        if (previousUser == null) {
            return;
        }

        String targetRoom = "socket:" + previousUser.socketId();
        socketIOServer.getRoomOperations(targetRoom).sendEvent(DUPLICATE_LOGIN, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", client.getHandshakeData().getHttpHeaders().get("User-Agent"),
                "ipAddress", client.getRemoteAddress().toString(),
                "timestamp", System.currentTimeMillis()
        ));

        CompletableFuture.delayedExecutor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> socketIOServer.getRoomOperations(targetRoom).sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                )));
    }
}
