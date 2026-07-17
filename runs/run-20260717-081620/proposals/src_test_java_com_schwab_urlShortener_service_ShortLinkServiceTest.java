package com.schwab.urlShortener.service;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.domain.ShortLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkServiceTest {

    @Mock
    private ShortLinkRepository repository;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @InjectMocks
    private ShortLinkService service;

    @Test
    void createShortLink_withValidUrl_shouldSucceed() {
        String longUrl = "https://example.com/very/long/path";
        String shortCode = "abc123";
        
        when(shortCodeGenerator.generate()).thenReturn(shortCode);
        when(repository.existsByShortCode(shortCode)).thenReturn(false);
        when(repository.save(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));
        
        ShortLink result = service.createShortLink(longUrl);
        
        assertNotNull(result);
        assertEquals(longUrl, result.getLongUrl());
        assertEquals(shortCode, result.getShortCode());
        
        verify(urlValidator).validate(longUrl);
        verify(repository).save(any(ShortLink.class));
    }

    @Test
    void createShortLink_withInvalidUrl_shouldThrowException() {
        String invalidUrl = "not-a-valid-url";
        
        doThrow(new InvalidUrlException("Invalid URL format"))
            .when(urlValidator).validate(invalidUrl);
        
        assertThrows(InvalidUrlException.class, () -> {
            service.createShortLink(invalidUrl);
        });
        
        verify(urlValidator).validate(invalidUrl);
        verify(repository, never()).save(any());
    }

    @Test
    void createShortLink_withCollision_shouldRetry() {
        String longUrl = "https://example.com";
        String firstCode = "abc123";
        String secondCode = "def456";
        
        when(shortCodeGenerator.generate())
            .thenReturn(firstCode)
            .thenReturn(secondCode);
        when(repository.existsByShortCode(firstCode)).thenReturn(true);
        when(repository.existsByShortCode(secondCode)).thenReturn(false);
        when(repository.save(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));
        
        ShortLink result = service.createShortLink(longUrl);
        
        assertEquals(secondCode, result.getShortCode());
        verify(shortCodeGenerator, times(2)).generate();
    }

    @Test
    void getLongUrl_withExistingShortCode_shouldReturnUrl() {
        String shortCode = "abc123";
        String longUrl = "https://example.com";
        ShortLink link = new ShortLink(longUrl, shortCode);
        
        when(repository.findByShortCode(shortCode)).thenReturn(Optional.of(link));
        
        Optional<String> result = service.getLongUrl(shortCode);
        
        assertTrue(result.isPresent());
        assertEquals(longUrl, result.get());
    }

    @Test
    void getLongUrl_withNonExistentShortCode_shouldReturnEmpty() {
        String shortCode = "nonexistent";
        
        when(repository.findByShortCode(shortCode)).thenReturn(Optional.empty());
        
        Optional<String> result = service.getLongUrl(shortCode);
        
        assertFalse(result.isPresent());
    }

    @Test
    void incrementClickCount_shouldUpdateRepository() {
        String shortCode = "abc123";
        
        service.incrementClickCount(shortCode);
        
        verify(repository).incrementClickCount(shortCode);
    }

    @Test
    void createShortLink_validatesUrl_beforePersisting() {
        String longUrl = "https://example.com";
        String shortCode = "abc123";
        
        when(shortCodeGenerator.generate()).thenReturn(shortCode);
        when(repository.existsByShortCode(shortCode)).thenReturn(false);
        when(repository.save(any(ShortLink.class))).thenAnswer(i -> i.getArgument(0));
        
        service.createShortLink(longUrl);
        
        verify(urlValidator).validate(longUrl);
    }

    @Test
    void createShortLink_withInternalUrl_shouldRejectAtServiceLevel() {
        String internalUrl = "http://192.168.1.1/admin";
        
        doThrow(new InvalidUrlException("URLs pointing to private or internal IP addresses are not allowed"))
            .when(urlValidator).validate(internalUrl);
        
        InvalidUrlException ex = assertThrows(InvalidUrlException.class, () -> {
            service.createShortLink(internalUrl);
        });
        
        assertTrue(ex.getMessage().contains("private or internal"));
        verify(urlValidator).validate(internalUrl);
        verify(repository, never()).save(any());
    }

    @Test
    void createShortLink_withLocalhostUrl_shouldRejectAtServiceLevel() {
        String localhostUrl = "http://localhost:8080/admin";
        
        doThrow(new InvalidUrlException("URLs pointing to localhost are not allowed"))
            .when(urlValidator).validate(localhostUrl);
        
        InvalidUrlException ex = assertThrows(InvalidUrlException.class, () -> {
            service.createShortLink(localhostUrl);
        });
        
        assertTrue(ex.getMessage().contains("localhost"));
        verify(urlValidator).validate(localhostUrl);
        verify(repository, never()).save(any());
    }
}
