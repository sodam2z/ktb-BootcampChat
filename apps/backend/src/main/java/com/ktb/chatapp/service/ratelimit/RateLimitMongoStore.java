package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.repository.RateLimitRepository;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 * Uses RateLimitRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {
    
    private final RateLimitRepository rateLimitRepository;
    private final MongoTemplate mongoTemplate;
    
    @Override
    public Optional<RateLimit> findByClientId(String clientId) {
        return rateLimitRepository.findByClientId(clientId);
    }
    
    @Override
    public RateLimit save(RateLimit rateLimit) {
        return rateLimitRepository.save(rateLimit);
    }

    @Override
    public RateLimitConsumption consume(String clientId, int maxRequests, Duration window, Instant now) {
        Instant expiresAt = now.plus(window);

        Query activeBelowLimit = Query.query(Criteria.where("clientId").is(clientId)
                .and("expiresAt").gt(now).and("count").lt(maxRequests));
        RateLimit updated = mongoTemplate.findAndModify(activeBelowLimit,
                new Update().inc("count", 1),
                FindAndModifyOptions.options().returnNew(true), RateLimit.class);
        if (updated != null) {
            return result(updated, true);
        }

        Query expired = Query.query(Criteria.where("clientId").is(clientId)
                .and("expiresAt").lte(now));
        updated = mongoTemplate.findAndModify(expired,
                new Update().set("count", 1).set("expiresAt", expiresAt),
                FindAndModifyOptions.options().returnNew(true), RateLimit.class);
        if (updated != null) {
            return result(updated, true);
        }

        RateLimit existing = rateLimitRepository.findByClientId(clientId).orElse(null);
        if (existing != null) {
            return result(existing, false);
        }

        try {
            RateLimit created = rateLimitRepository.insert(RateLimit.builder()
                    .clientId(clientId).count(1).expiresAt(expiresAt).build());
            return result(created, true);
        } catch (DuplicateKeyException race) {
            return consume(clientId, maxRequests, window, now);
        }
    }

    private RateLimitConsumption result(RateLimit value, boolean allowed) {
        return new RateLimitConsumption(value.getCount(), value.getExpiresAt(), allowed);
    }
}
