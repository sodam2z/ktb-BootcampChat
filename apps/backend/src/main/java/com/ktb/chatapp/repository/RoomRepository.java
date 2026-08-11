package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Room;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface RoomRepository extends MongoRepository<Room, String> {

    // 가장 최근에 생성된 방 조회 (Health Check용)
    @Query(value = "{}", sort = "{ 'createdAt': -1 }")
    Optional<Room> findMostRecentRoom();

    // Health Check용 단순 조회 (지연 시간 측정)
    @Query(value = "{}", fields = "{ '_id': 1 }")
    Optional<Room> findOneForHealthCheck();

    List<Room> findByParticipantIdsContaining(String userId);

    boolean existsByIdAndParticipantIdsContaining(String roomId, String userId);

    @Query("{'_id': ?0}")
    @Update("{'$addToSet': {'participantIds': ?1}}")
    long addParticipant(String roomId, String userId);

    @Query("{'_id': ?0}")
    @Update("{'$pull': {'participantIds': ?1}}")
    long removeParticipant(String roomId, String userId);
}
