package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivitiesEvent;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장된 채팅방을 모아
 * 최근 메시지 수를 주기적으로 일괄 갱신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final RoomActivityDebouncer roomActivityDebouncer;

    private final Set<String> pendingRoomIds =
            ConcurrentHashMap.newKeySet();

    /**
     * 메시지 저장 경로에서는 DB 조회를 하지 않고
     * 갱신이 필요한 채팅방만 기록한다.
     */
    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        pendingRoomIds.add(roomId);
    }

    /**
     * 짧은 주기로 변경된 채팅방의 최근 메시지 수를
     * 한 번의 집계 쿼리로 조회한다.
     */
    @Scheduled(fixedDelayString = "${room-activity.flush-delay:1s}")
    void flushPendingRoomActivities() {
        if (pendingRoomIds.isEmpty()) {
            return;
        }

        Set<String> roomIds = new HashSet<>();

        for (String roomId : pendingRoomIds) {
            if (pendingRoomIds.remove(roomId)) {
                roomIds.add(roomId);
            }
        }

        if (roomIds.isEmpty()) {
            return;
        }

        roomIds.removeIf(roomId -> !roomActivityDebouncer.tryAcquire(roomId));
        if (roomIds.isEmpty()) {
            return;
        }

        try {
            Map<String, Integer> recentMessageCounts =
                    recentMessageCounter.countRecentMessagesByRoomIds(roomIds);

            Map<String, Integer> activityCounts = new HashMap<>();
            for (String roomId : roomIds) {
                activityCounts.put(roomId, recentMessageCounts.getOrDefault(roomId, 0));
            }

            if (!activityCounts.isEmpty()) {
                eventPublisher.publishEvent(new RoomActivitiesEvent(this, activityCounts));
            }

        } catch (Exception e) {
            // 일시적으로 집계에 실패하면 다음 주기에 다시 시도한다.
            pendingRoomIds.addAll(roomIds);

            log.error(
                    "roomActivity 일괄 갱신 실패: roomIds={}",
                    roomIds,
                    e
            );
        }
    }
}
