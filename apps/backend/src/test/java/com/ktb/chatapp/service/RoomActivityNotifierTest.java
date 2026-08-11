package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivitiesEvent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RoomActivityDebouncer roomActivityDebouncer;

    private RoomActivityNotifier notifier() {
        return new RoomActivityNotifier(recentMessageCounter, eventPublisher, roomActivityDebouncer);
    }

    @Test
    void flushPublishesRecentMessageCount() {
        when(recentMessageCounter.countRecentMessagesByRoomIds(Set.of("room-1")))
                .thenReturn(Map.of("room-1", 7));
        when(roomActivityDebouncer.tryAcquire("room-1")).thenReturn(true);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.flushPendingRoomActivities();

        ArgumentCaptor<RoomActivitiesEvent> event = ArgumentCaptor.forClass(RoomActivitiesEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getRecentMessageCounts()).containsEntry("room-1", 7);
    }

    @Test
    void flushCoalescesRepeatedNotificationsForSameRoom() {
        when(recentMessageCounter.countRecentMessagesByRoomIds(Set.of("room-1")))
                .thenReturn(Map.of("room-1", 3));
        when(roomActivityDebouncer.tryAcquire("room-1")).thenReturn(true);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");
        notifier.flushPendingRoomActivities();

        verify(recentMessageCounter).countRecentMessagesByRoomIds(Set.of("room-1"));
        verify(eventPublisher).publishEvent(any(RoomActivitiesEvent.class));
    }

    @Test
    void nullRoomIdDoesNothing() {
        RoomActivityNotifier notifier = notifier();
        notifier.notifyMessageStored(null);
        notifier.flushPendingRoomActivities();

        verifyNoInteractions(recentMessageCounter, eventPublisher);
    }

    @Test
    void failedBatchIsRetriedOnNextFlush() {
        when(recentMessageCounter.countRecentMessagesByRoomIds(Set.of("room-1")))
                .thenThrow(new RuntimeException("mongo down"))
                .thenReturn(Map.of("room-1", 1));
        when(roomActivityDebouncer.tryAcquire("room-1")).thenReturn(true);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.flushPendingRoomActivities();
        verify(eventPublisher, never()).publishEvent(any(RoomActivitiesEvent.class));

        notifier.flushPendingRoomActivities();
        verify(recentMessageCounter, times(2)).countRecentMessagesByRoomIds(Set.of("room-1"));
        verify(eventPublisher).publishEvent(any(RoomActivitiesEvent.class));
    }

    @Test
    void distributedDebounceSkipsDuplicateAggregation() {
        when(roomActivityDebouncer.tryAcquire("room-1")).thenReturn(false);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.flushPendingRoomActivities();

        verifyNoInteractions(recentMessageCounter, eventPublisher);
    }
}
