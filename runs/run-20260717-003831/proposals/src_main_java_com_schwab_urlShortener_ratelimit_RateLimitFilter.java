package com.schwab.urlShortener.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final IpExtractor ipExtractor;
    private final RateLimiter rateLimiter;

    public RateLimitFilter(IpExtractor ipExtractor, RateLimiter rateLimiter) {
        this.ipExtractor = ipExtractor;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        if (shouldRateLimit(request)) {
            String ipAddress = ipExtractor.extractIp(request);
            rateLimiter.checkRateLimit(ipAddress);
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldRateLimit(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) 
            && request.getRequestURI().startsWith("/api/v1/links");
    }
}
