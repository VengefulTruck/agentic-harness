package com.schwab.urlShortener.ratelimit;

public class RateLimitExceededException extends RuntimeException {
    private final String ipAddress;

    public RateLimitExceededException(String ipAddress) {
        super("Rate limit exceeded for IP: " + ipAddress);
        this.ipAddress = ipAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
