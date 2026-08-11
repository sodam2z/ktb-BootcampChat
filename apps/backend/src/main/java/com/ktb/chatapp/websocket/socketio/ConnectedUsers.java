package com.ktb.chatapp.websocket.socketio;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ConnectedUsers {
    
    private static final String USER_SOCKET_KEY_PREFIX = "conn_users:userid:";
    
    private final ChatDataStore chatDataStore;
    
    public SocketUser get(String userId) {
        return chatDataStore.get(buildKey(userId), SocketUser.class).orElse(null);
    }
    
    public void set(String userId, SocketUser sockerUser) {
        chatDataStore.set(buildKey(userId), sockerUser);
    }

    public SocketUser replace(String userId, SocketUser socketUser) {
        return chatDataStore.getAndSet(buildKey(userId), socketUser, SocketUser.class);
    }
    
    public void del(String userId) {
        chatDataStore.delete(buildKey(userId));
    }

    public boolean delIfCurrent(String userId, SocketUser socketUser) {
        return chatDataStore.compareAndDelete(buildKey(userId), socketUser);
    }
    
    public int size() {
        return chatDataStore.size();
    }
    
    private String buildKey(String userId) {
        return USER_SOCKET_KEY_PREFIX + userId;
    }
}
