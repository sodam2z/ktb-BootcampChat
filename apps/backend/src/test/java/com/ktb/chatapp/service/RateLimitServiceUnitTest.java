package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitConsumption;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceUnitTest {

    @Mock private RateLimitStore rateLimitStore;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService(rateLimitStore);
    }

    @Test
    void usesGlobalClientIdAndReturnsRemainingCount() {
        when(rateLimitStore.consume(eq("client-1"), eq(3), eq(Duration.ofSeconds(30)), any()))
                .thenReturn(new RateLimitConsumption(1, Instant.now().plusSeconds(30), true));

        RateLimitCheckResult result = service.checkRateLimit("client-1", 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
    }

    @Test
    void rejectsWhenAtomicStoreRejects() {
        when(rateLimitStore.consume(eq("client-1"), eq(3), eq(Duration.ofSeconds(30)), any()))
                .thenReturn(new RateLimitConsumption(3, Instant.now().plusSeconds(10), false));

        RateLimitCheckResult result = service.checkRateLimit("client-1", 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }

    @Test
    void normalizesZeroWindowToOneSecond() {
        when(rateLimitStore.consume(eq("client-1"), eq(3), eq(Duration.ofSeconds(1)), any()))
                .thenReturn(new RateLimitConsumption(1, Instant.now().plusSeconds(1), true));

        assertThat(service.checkRateLimit("client-1", 3, Duration.ZERO).windowSeconds()).isEqualTo(1);
    }

    @Test
    void failsOpenWhenStoreIsUnavailable() {
        when(rateLimitStore.consume(eq("client-1"), eq(3), eq(Duration.ofSeconds(30)), any()))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = service.checkRateLimit("client-1", 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(3);
    }
}
