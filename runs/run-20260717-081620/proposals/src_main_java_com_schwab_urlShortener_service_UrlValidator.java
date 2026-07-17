package com.schwab.urlShortener.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates URLs for security and format requirements.
 * 
 * Security controls:
 * - Allowlist of safe schemes (http, https only) to prevent XSS via javascript:, data:, etc.
 * - Rejection of private/internal IP addresses to prevent SSRF attacks against internal infrastructure
 * 
 * Known limitations:
 * - DNS rebinding attacks: An attacker controlling DNS could pass validation with a public IP,
 *   then change DNS to resolve to a private IP between validation and redirect time (TOCTOU).
 * - IP obfuscation in hostnames is detected, but new encoding schemes may emerge.
 * - Does not protect against phishing, malware domains, or other content-based threats.
 */
@Component
public class UrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    
    // RFC 1918 private address ranges
    private static final String[] PRIVATE_IP_PATTERNS = {
        "^10\\..*",           // 10.0.0.0/8
        "^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*",  // 172.16.0.0/12
        "^192\\.168\\..*"     // 192.168.0.0/16
    };
    
    public void validate(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new InvalidUrlException("URL cannot be empty");
        }
        
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Invalid URL format: " + e.getMessage());
        }
        
        validateScheme(uri);
        validateHost(uri);
    }
    
    private void validateScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException(
                "URL scheme must be http or https. Found: " + scheme
            );
        }
    }
    
    private void validateHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new InvalidUrlException("URL must have a valid host");
        }
        
        // Check for localhost variations
        if (isLocalhost(host)) {
            throw new InvalidUrlException("URLs pointing to localhost are not allowed");
        }
        
        // Try to resolve and check if it's a private/internal address
        try {
            InetAddress address = InetAddress.getByName(host);
            if (isPrivateOrInternalAddress(address)) {
                throw new InvalidUrlException("URLs pointing to private or internal IP addresses are not allowed");
            }
        } catch (UnknownHostException e) {
            // If we can't resolve the host, we'll allow it through
            // The redirect will fail naturally if the host doesn't exist
            // This avoids being overly restrictive for valid public domains with temporary DNS issues
        }
    }
    
    private boolean isLocalhost(String host) {
        String lowerHost = host.toLowerCase();
        return lowerHost.equals("localhost") || 
               lowerHost.equals("localhost.localdomain") ||
               lowerHost.startsWith("localhost.");
    }
    
    private boolean isPrivateOrInternalAddress(InetAddress address) {
        // Check loopback (127.0.0.0/8 for IPv4, ::1 for IPv6)
        if (address.isLoopbackAddress()) {
            return true;
        }
        
        // Check link-local (169.254.0.0/16 for IPv4, fe80::/10 for IPv6)
        if (address.isLinkLocalAddress()) {
            return true;
        }
        
        // Check site-local/private (deprecated for IPv6 but still checked)
        if (address.isSiteLocalAddress()) {
            return true;
        }
        
        // Additional check for RFC 1918 private ranges using string matching
        // This catches cases where the Java API might not flag them
        String hostAddress = address.getHostAddress();
        for (String pattern : PRIVATE_IP_PATTERNS) {
            if (hostAddress.matches(pattern)) {
                return true;
            }
        }
        
        return false;
    }
}
