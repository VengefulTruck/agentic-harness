package com.schwab.urlShortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IpRateLimiter {
    
    private final Clock clock;
    private final int maxRequestsPerWindow;
    private final long windowSeconds;
    
    private final Map<String, RequestWindow> requestCounts = new ConcurrentHashMap<>();

    public IpRateLimiter(
            Clock clock,
            @Value("${ratelimit.max-requests:100}") int maxRequestsPerWindow,
            @Value("${ratelimit.window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowSeconds = windowSeconds;
    }

    public void checkAndIncrement(HttpServletRequest request) {
        String clientIp = extractClientIp(request);
        long currentTime = clock.instant().getEpochSecond();
        long currentWindow = currentTime / windowSeconds;
        
        RequestWindow window = requestCounts.compute(clientIp, (ip, existing) -> {
            if (existing == null || existing.windowId != currentWindow) {
                return new RequestWindow(currentWindow, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        
        if (window.count.get() > maxRequestsPerWindow) {
            throw new RateLimitExceededException(clientIp, maxRequestsPerWindow, windowSeconds);
        }
        
        // Clean up old windows periodically (simple cleanup on every 100th request)
        if (window.count.get() % 100 == 0) {
            requestCounts.entrySet().removeIf(entry -> 
                entry.getValue().windowId < currentWindow - 1
            );
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        
        return request.getRemoteAddr();
    }

    private static class RequestWindow {
        final long windowId;
        final AtomicInteger count;

        RequestWindow(long windowId, AtomicInteger count) {
            this.windowId = windowId;
            this.count = count;
        }
    }
}
