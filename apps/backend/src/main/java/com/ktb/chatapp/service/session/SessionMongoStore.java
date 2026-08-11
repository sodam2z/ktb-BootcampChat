package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    private final MongoTemplate mongoTemplate;
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return sessionRepository.findByUserId(userId);
    }
    
    @Override
    public Session save(Session session) {
        return sessionRepository.save(session);
    }

    @Override
    public Session replaceByUserId(Session session) {
        Query query = Query.query(Criteria.where("userId").is(session.getUserId()));
        Update update = new Update()
                .set("userId", session.getUserId())
                .set("sessionId", session.getSessionId())
                .set("createdAt", session.getCreatedAt())
                .set("lastActivity", session.getLastActivity())
                .set("metadata", session.getMetadata())
                .set("expiresAt", session.getExpiresAt());

        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Session.class
        );
    }

    @Override
    public Optional<Session> touch(String userId, String sessionId, long lastActivity, java.time.Instant expiresAt) {
        Query query = Query.query(Criteria.where("userId").is(userId).and("sessionId").is(sessionId));
        Update update = new Update().set("lastActivity", lastActivity).set("expiresAt", expiresAt);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Session.class));
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        Query query = Query.query(Criteria.where("userId").is(userId).and("sessionId").is(sessionId));
        mongoTemplate.remove(query, Session.class);
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
