package com.shortlink.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShortLinkService {
    
    private final Map<String, String> shortLinkStore = new ConcurrentHashMap<>();
    
    public ShortLinkService() {
        shortLinkStore.put("abc123", "https://example.com/very-long-url");
        shortLinkStore.put("def456", "https://example.com/another-long-url");
    }
    
    public Optional<String> resolveShortLink(String code) {
        return Optional.ofNullable(shortLinkStore.get(code));
    }
    
    public String createShortLink(String url, String code) {
        shortLinkStore.put(code, url);
        return code;
    }
}
