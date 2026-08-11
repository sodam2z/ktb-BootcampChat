package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitStore rateLimitStore;
    public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
        String actualClientId = String.valueOf(_clientId);
        Duration effectiveWindow = window == null || window.isZero() || window.isNegative()
                ? Duration.ofSeconds(1) : window;
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());
        Instant now = Instant.now();
        long nowEpochSeconds = now.getEpochSecond();

        try {
            var consumption = rateLimitStore.consume(actualClientId, maxRequests, effectiveWindow, now);
            if (!consumption.allowed()) {
                long retryAfterSeconds = Math.max(1L,
                    consumption.expiresAt().getEpochSecond() - nowEpochSeconds);
                long resetEpochSeconds = consumption.expiresAt().getEpochSecond();
                return RateLimitCheckResult.rejected(
                        maxRequests, windowSeconds, resetEpochSeconds, retryAfterSeconds);
            }

            int remaining = Math.max(0, maxRequests - consumption.count());
            long ttlSeconds = Math.max(1L, consumption.expiresAt().getEpochSecond() - nowEpochSeconds);
            long resetEpochSeconds = consumption.expiresAt().getEpochSecond();

            return RateLimitCheckResult.allowed(
                    maxRequests, remaining, windowSeconds, resetEpochSeconds, ttlSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests, maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }
    
}
