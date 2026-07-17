package com.schwab.urlShortener.service;

import com.schwab.urlShortener.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class RateLimiterService {
    private final Map<String, Queue<Instant>> requestTimestamps = new ConcurrentHashMap<>();
    private final int maxRequestsPerMinute;
    private final Clock clock;

    public RateLimiterService(
            @Value("${app.rate-limit.requests-per-minute:60}") int maxRequestsPerMinute,
            Clock clock) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.clock = clock;
    }

    public void checkRateLimit(String ipAddress) {
        Instant now = clock.instant();
        Instant oneMinuteAgo = now.minusSeconds(60);

        Queue<Instant> timestamps = requestTimestamps.computeIfAbsent(
                ipAddress,
                k -> new ConcurrentLinkedQueue<>());

        timestamps.removeIf(timestamp -> timestamp.isBefore(oneMinuteAgo));

        if (timestamps.size() >= maxRequestsPerMinute) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Maximum " + maxRequestsPerMinute + " requests per minute allowed.");
        }

        timestamps.add(now);
    }
}
