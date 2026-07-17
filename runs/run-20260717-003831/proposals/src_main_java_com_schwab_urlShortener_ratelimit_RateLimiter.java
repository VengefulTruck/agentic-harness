package com.schwab.urlShortener.ratelimit;

public interface RateLimiter {
    void checkRateLimit(String ipAddress) throws RateLimitExceededException;
}
