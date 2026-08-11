package com.ktb.chatapp.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rooms")
@CompoundIndex(name = "rooms_created_at_id_desc", def = "{'createdAt': -1, '_id': -1}")
public class Room {

    @Id
    private String id;

    private String name;

    private String creator;

    private boolean hasPassword;

    @JsonIgnore
    private String password;

    // Health Check가 최신 방 1개를 createdAt desc로 찾으므로 정렬 비용을 줄인다.
    @Indexed(name = "created_at_desc_idx", direction = IndexDirection.DESCENDING)
    @CreatedDate
    private LocalDateTime createdAt;

    @Field("participantIds")
    @Builder.Default
    private Set<String> participantIds = new HashSet<>();
    
    /**
     * 방에 참가자를 추가한다.
     *
     * @param userId 추가할 사용자 ID
     */
    public void addParticipant(String userId) {
        if (this.participantIds == null) {
            this.participantIds = new HashSet<>();
        }
        this.participantIds.add(userId);
    }
    
    /**
     * 방에서 참가자를 제거한다.
     *
     * @param userId 제거할 사용자 ID
     */
    public void removeParticipant(String userId) {
        if (this.participantIds != null) {
            this.participantIds.remove(userId);
        }
    }
    
    /**
     * 방이 비어있는지 확인한다.
     *
     * @return 참가자가 없으면 true
     */
    public boolean isEmpty() {
        return this.participantIds == null || this.participantIds.isEmpty();
    }
    
    /**
     * 방의 참가자 수를 반환한다.
     *
     * @return 참가자 수
     */
    public int getParticipantCount() {
        return this.participantIds != null ? this.participantIds.size() : 0;
    }
}
