package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserConnectionLocks;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final UserRooms userRooms;
    private final UserConnectionLocks userConnectionLocks;
    private final RoomJoinHandler roomJoinHandler;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            UserConnectionLocks userConnectionLocks,
            RoomJoinHandler roomJoinHandler,
            MeterRegistry meterRegistry) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.userConnectionLocks = userConnectionLocks;
        this.roomJoinHandler = roomJoinHandler;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
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
                // 다른 노드에 접속된 사용자는 통보 불가
                notifyDuplicateLogin(client, userId);

                // Publish the new owner before restoring rooms. A late disconnect
                // from the old socket must observe this socket as the active one.
                connectedUsers.set(userId, user);

                userRooms.get(userId).forEach(roomId -> {
                    // 재접속 시 기존 참여 방 재입장 처리
                    roomJoinHandler.handleJoinRoom(client, roomId);
                });
            });

            log.info("Socket.IO user connected: {} ({}) - Total concurrent users: {}",
                    getUserName(client), userId, connectedUsers.size());

            client.joinRooms(Set.of("user:" + userId, "room-list"));
            
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
                var socketUser = connectedUsers.get(userId);
                if (socketUser != null && socketId.equals(socketUser.socketId())) {
                    connectedUsers.del(userId);
                } else {
                    log.info("Socket.IO disconnect: User {} has a different active connection. "
                            + "Skipping connection cleanup.", userId);
                }
            });

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO user disconnected: {} ({}) - Total concurrent users: {}",
                    userName, userId, connectedUsers.size());
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
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    /**
     * TODO 멀티 클러스터에서 동작 안함
     * socketIOServer.getRoomOperations("user:" + userId) 로 처리 변경.
     */
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }
        String existingSocketId = socketUser.socketId();
        SocketIOClient existingClient = socketIOServer.getClient(UUID.fromString(existingSocketId));
        if (existingClient == null) {
            return;
        }
        
        // Send duplicate login notification
        existingClient.sendEvent(DUPLICATE_LOGIN, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", client.getHandshakeData().getHttpHeaders().get("User-Agent"),
                "ipAddress", client.getRemoteAddress().toString(),
                "timestamp", System.currentTimeMillis()
        ));
        
        new Thread(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
                existingClient.sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Error in duplicate login notification thread", e);
            }
        }).start();
    }
}
