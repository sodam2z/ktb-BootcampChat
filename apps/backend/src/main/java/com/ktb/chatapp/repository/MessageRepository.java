package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByRoomIdAndTimestampBefore(String roomId, LocalDateTime timestamp, Pageable pageable);
    /**
     * 특정 시간 이후의 메시지 수 카운트
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    @Aggregation(pipeline = {
        "{ '$match': { 'room': { '$in': ?0 }, 'timestamp': { '$gte': ?1 } } }",
        "{ '$group': { '_id': '$room', 'count': { '$sum': 1 } } }",
        "{ '$project': { '_id': 0, 'roomKey': '$_id', 'count': 1 } }"
    })
    List<RecentMessageCount> countRecentMessagesByRoomIds(
        Set<String> roomIds,
        LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);

    @Query(value = "{ '_id': ?0 }", fields = "{ 'room': 1 }")
    Optional<Message> findRoomOnlyById(String messageId);
}
