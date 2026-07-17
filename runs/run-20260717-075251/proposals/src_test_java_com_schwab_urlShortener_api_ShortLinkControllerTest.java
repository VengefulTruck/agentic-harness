package com.schwab.urlShortener.api;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.dto.LinkStats;
import com.schwab.urlShortener.exception.ShortLinkNotFoundException;
import com.schwab.urlShortener.service.ShortLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShortLinkController.class)
class ShortLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShortLinkService service;

    @Test
    void testCreateShortLink() throws Exception {
        var now = Instant.now();
        var link = new ShortLink("abc123", "https://example.com", now);
        when(service.createShortLink("https://example.com")).thenReturn(link);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com"))
                .andExpect(jsonPath("$.shortUrl").exists());
    }

    @Test
    void testCreateShortLink_InvalidUrl() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"not-a-url\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void testGetShortLink() throws Exception {
        var now = Instant.now();
        var link = new ShortLink("abc123", "https://example.com", now);
        when(service.resolve("abc123")).thenReturn(link);

        mockMvc.perform(get("/api/v1/links/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com"));
    }

    @Test
    void testGetShortLink_NotFound() throws Exception {
        when(service.resolve("nonexistent")).thenThrow(new ShortLinkNotFoundException("nonexistent"));

        mockMvc.perform(get("/api/v1/links/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short Link Not Found"));
    }

    @Test
    void testGetStats() throws Exception {
        var now = Instant.now();
        var link = new ShortLink("abc123", "https://example.com", now);
        var stats = new LinkStats(42L, now);
        when(service.resolve("abc123")).thenReturn(link);
        when(service.getStats("abc123")).thenReturn(stats);

        mockMvc.perform(get("/api/v1/links/abc123/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.accessCount").value(42));
    }

    @Test
    void testGetQrCode_Success() throws Exception {
        var now = Instant.now();
        var link = new ShortLink("abc123", "https://example.com", now);
        when(service.resolve("abc123")).thenReturn(link);

        mockMvc.perform(get("/api/v1/links/abc123/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(notNullValue()))
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void testGetQrCode_NotFound() throws Exception {
        when(service.resolve("nonexistent")).thenThrow(new ShortLinkNotFoundException("nonexistent"));

        mockMvc.perform(get("/api/v1/links/nonexistent/qr"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short Link Not Found"));
    }
}
