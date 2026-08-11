package com.ktb.chatapp.event;

import com.ktb.chatapp.dto.RoomListItemResponse;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RoomCreatedEvent extends ApplicationEvent {
    private final RoomListItemResponse roomResponse;

    public RoomCreatedEvent(Object source, RoomListItemResponse roomResponse) {
        super(source);
        this.roomResponse = roomResponse;
    }
}
