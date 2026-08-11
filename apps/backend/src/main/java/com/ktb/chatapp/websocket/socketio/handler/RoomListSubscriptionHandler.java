package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_LIST;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.LEAVE_ROOM_LIST;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class RoomListSubscriptionHandler {

    private static final String ROOM_LIST = "room-list";

    @OnEvent(JOIN_ROOM_LIST)
    public void joinRoomList(SocketIOClient client) {
        if (client.get("user") == null) {
            log.debug("Ignoring unauthenticated room-list subscription: socketId={}", client.getSessionId());
            return;
        }
        client.joinRoom(ROOM_LIST);
    }

    @OnEvent(LEAVE_ROOM_LIST)
    public void leaveRoomList(SocketIOClient client) {
        client.leaveRoom(ROOM_LIST);
    }
}
