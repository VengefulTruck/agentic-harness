package com.schwab.urlShortener.ratelimit;

public class RateLimitExceededException extends RuntimeException {
    private final String clientIp;
    private final int limit;
    private final long windowSeconds;

    public RateLimitExceededException(String clientIp, int limit, long windowSeconds) {
        super(String.format("Rate limit exceeded for IP %s: %d requests per %d seconds", 
              clientIp, limit, windowSeconds));
        this.clientIp = clientIp;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
