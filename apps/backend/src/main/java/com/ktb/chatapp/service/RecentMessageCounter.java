package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RecentMessageCount;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    public Map<String, Integer> countRecentMessagesByRoomIds(Set<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }

        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return messageRepository.countRecentMessagesByRoomIds(roomIds, since).stream()
            .collect(Collectors.toMap(
                RecentMessageCount::roomKey,
                count -> Math.toIntExact(count.count())));
    }
}
