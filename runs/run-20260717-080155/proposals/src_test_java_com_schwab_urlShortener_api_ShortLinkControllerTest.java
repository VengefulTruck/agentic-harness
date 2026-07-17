package com.schwab.urlShortener.api;

import com.schwab.urlShortener.domain.LinkStats;
import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.service.QrCodeService;
import com.schwab.urlShortener.service.ShortLinkNotFoundException;
import com.schwab.urlShortener.service.ShortLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShortLinkController.class)
class ShortLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShortLinkService service;

    @MockBean
    private QrCodeService qrCodeService;

    @Test
    void createShortLink_shouldReturnCreatedLink() throws Exception {
        ShortLink shortLink = new ShortLink("abc123", "https://example.com", Instant.now());
        when(service.create(anyString())).thenReturn(shortLink);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andExpect(jsonPath("$.shortUrl").exists());
    }

    @Test
    void getShortLink_shouldReturnLinkDetails() throws Exception {
        ShortLink shortLink = new ShortLink("abc123", "https://example.com", Instant.now());
        when(service.resolve("abc123")).thenReturn(shortLink);

        mockMvc.perform(get("/api/v1/links/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andExpect(jsonPath("$.shortUrl").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void getShortLink_shouldReturn404ForUnknownCode() throws Exception {
        when(service.resolve("unknown")).thenThrow(new ShortLinkNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/links/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStats_shouldReturnLinkStatistics() throws Exception {
        LinkStats stats = new LinkStats("abc123", "https://example.com", 42, Instant.now(), Instant.now());
        when(service.getStats("abc123")).thenReturn(stats);

        mockMvc.perform(get("/api/v1/links/abc123/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.accessCount").value(42));
    }

    @Test
    void getQrCode_shouldReturnPngImageFor ValidShortCode() throws Exception {
        ShortLink shortLink = new ShortLink("abc123", "https://example.com", Instant.now());
        when(service.resolve("abc123")).thenReturn(shortLink);
        
        byte[] mockQrCode = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        when(qrCodeService.generateQrCodePng(anyString(), eq(250), eq(250))).thenReturn(mockQrCode);

        mockMvc.perform(get("/api/v1/links/abc123/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(mockQrCode));
    }

    @Test
    void getQrCode_shouldReturn404ForUnknownShortCode() throws Exception {
        when(service.resolve("unknown")).thenThrow(new ShortLinkNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/links/unknown/qr"))
                .andExpect(status().isNotFound());
    }
}
