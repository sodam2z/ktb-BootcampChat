package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;

/**
 * Data store interface for rate limit storage.
 * Provides operations for storing and retrieving rate limit data.
 */
public interface RateLimitStore {
    
    /**
     * Find rate limit by client ID
     *
     * @param clientId the client identifier
     * @return Optional containing the RateLimit if found, empty otherwise
     */
    Optional<RateLimit> findByClientId(String clientId);
    
    /**
     * Save or update rate limit
     *
     * @param rateLimit the rate limit to save
     * @return the saved rate limit
     */
    RateLimit save(RateLimit rateLimit);

    default RateLimitConsumption consume(String clientId, int maxRequests, Duration window, Instant now) {
        RateLimit rateLimit = findByClientId(clientId).orElse(null);
        Instant expiresAt = now.plus(window);
        if (rateLimit == null) {
            rateLimit = RateLimit.builder().clientId(clientId).count(1).expiresAt(expiresAt).build();
        } else if (!rateLimit.getExpiresAt().isAfter(now)) {
            rateLimit.setCount(1);
            rateLimit.setExpiresAt(expiresAt);
        } else if (rateLimit.getCount() >= maxRequests) {
            return new RateLimitConsumption(rateLimit.getCount(), rateLimit.getExpiresAt(), false);
        } else {
            rateLimit.setCount(rateLimit.getCount() + 1);
        }
        RateLimit saved = save(rateLimit);
        return new RateLimitConsumption(saved.getCount(), saved.getExpiresAt(), true);
    }
}
