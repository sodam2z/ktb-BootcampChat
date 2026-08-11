package com.ktb.chatapp.event;

import java.util.Map;
import org.springframework.context.ApplicationEvent;

public class RoomActivitiesEvent extends ApplicationEvent {

    private final Map<String, Integer> recentMessageCounts;

    public RoomActivitiesEvent(Object source, Map<String, Integer> recentMessageCounts) {
        super(source);
        this.recentMessageCounts = Map.copyOf(recentMessageCounts);
    }

    public Map<String, Integer> getRecentMessageCounts() {
        return recentMessageCounts;
    }
}
