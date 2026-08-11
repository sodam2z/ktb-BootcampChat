package com.ktb.chatapp.service.ratelimit;

import java.time.Instant;

public record RateLimitConsumption(int count, Instant expiresAt, boolean allowed) {
}
