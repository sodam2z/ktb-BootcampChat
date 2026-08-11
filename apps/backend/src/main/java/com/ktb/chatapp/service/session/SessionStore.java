package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.util.Optional;

/**
 * Data store interface for session storage.
 * Provides operations for storing and retrieving session data.
 */
public interface SessionStore {

    /**
     * Find session by user ID
     *
     * @param userId the user identifier
     * @return Optional containing the Session if found, empty otherwise
     */
    Optional<Session> findByUserId(String userId);

    Session save(Session session);

    /**
     * Atomically replace or create the session identified by user ID.
     *
     * @param session the new active session
     * @return the stored session
     */
    Session replaceByUserId(Session session);

    Optional<Session> touch(String userId, String sessionId, long lastActivity, java.time.Instant expiresAt);
    
    /**
     * Delete all sessions for a user
     *
     * @param userId the user identifier
     */
    void deleteAll(String userId);
    
    void delete(String userId, String sessionId);
}
