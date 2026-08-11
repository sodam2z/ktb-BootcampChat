package com.ktb.chatapp.event;

import com.ktb.chatapp.dto.RoomListItemResponse;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RoomUpdatedEvent extends ApplicationEvent {
    private final String roomId;
    private final RoomListItemResponse roomResponse;

    public RoomUpdatedEvent(Object source, String roomId, RoomListItemResponse roomResponse) {
        super(source);
        this.roomId = roomId;
        this.roomResponse = roomResponse;
    }
}
