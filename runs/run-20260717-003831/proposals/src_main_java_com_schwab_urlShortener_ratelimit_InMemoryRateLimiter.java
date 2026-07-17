package com.schwab.urlShortener.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRateLimiter implements RateLimiter {

    private final int requestsPerWindow;
    private final long windowDurationSeconds;
    private final ConcurrentHashMap<String, TokenBucket> buckets;

    public InMemoryRateLimiter(
            @Value("${ratelimit.requests-per-window:60}") int requestsPerWindow,
            @Value("${ratelimit.window-duration-seconds:60}") long windowDurationSeconds) {
        this.requestsPerWindow = requestsPerWindow;
        this.windowDurationSeconds = windowDurationSeconds;
        this.buckets = new ConcurrentHashMap<>();
    }

    @Override
    public void checkRateLimit(String ipAddress) throws RateLimitExceededException {
        TokenBucket bucket = buckets.compute(ipAddress, (key, existing) -> {
            if (existing == null) {
                return new TokenBucket(requestsPerWindow, windowDurationSeconds);
            }
            return existing;
        });

        if (!bucket.tryConsume()) {
            throw new RateLimitExceededException(ipAddress);
        }
    }

    private static class TokenBucket {
        private final int capacity;
        private final long windowDurationSeconds;
        private int tokens;
        private long windowStart;

        TokenBucket(int capacity, long windowDurationSeconds) {
            this.capacity = capacity;
            this.windowDurationSeconds = windowDurationSeconds;
            this.tokens = capacity;
            this.windowStart = Instant.now().getEpochSecond();
        }

        synchronized boolean tryConsume() {
            long now = Instant.now().getEpochSecond();
            
            if (now - windowStart >= windowDurationSeconds) {
                tokens = capacity;
                windowStart = now;
            }

            if (tokens > 0) {
                tokens--;
                return true;
            }
            
            return false;
        }
    }
}
