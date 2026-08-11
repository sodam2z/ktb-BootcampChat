package com.ktb.chatapp.service.ratelimit;

import java.time.Duration;
import java.time.Instant;

/**
 * Data store interface for rate limit storage.
 * Provides operations for storing and retrieving rate limit data.
 */
public interface RateLimitStore {
    RateLimitConsumption consume(String clientId, int maxRequests, Duration window, Instant now);
}
