package com.schwab.urlShortener;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ShortLinkIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShortLinkRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        cacheManager.getCacheNames().forEach(cacheName ->
                cacheManager.getCache(cacheName).clear());
    }

    @Test
    void testCreateAndRetrieveShortLink() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortCode = createResponse.split("\"shortCode\":\"")[1].split("\"")[0];

        mockMvc.perform(get("/api/v1/links/" + shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com"));
    }

    @Test
    void testRedirectIncrementsAccessCount() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortCode = createResponse.split("\"shortCode\":\"")[1].split("\"")[0];

        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));

        mockMvc.perform(get("/api/v1/links/" + shortCode + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessCount").value(1));
    }

    @Test
    void testGetNonExistentShortLink() throws Exception {
        mockMvc.perform(get("/api/v1/links/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short Link Not Found"));
    }

    @Test
    void testRedirectNonExistentShortCode() throws Exception {
        mockMvc.perform(get("/s/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short Link Not Found"));
    }

    @Test
    void testInvalidUrlValidation() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"not-a-valid-url\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void testQrCodeEndpoint() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortCode = createResponse.split("\"shortCode\":\"")[1].split("\"")[0];

        byte[] qrCodeBytes = mockMvc.perform(get("/api/v1/links/" + shortCode + "/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assert qrCodeBytes.length > 0;
        assert qrCodeBytes[0] == (byte) 0x89;
        assert qrCodeBytes[1] == (byte) 0x50;
        assert qrCodeBytes[2] == (byte) 0x4E;
        assert qrCodeBytes[3] == (byte) 0x47;
    }

    @Test
    void testQrCodeEndpoint_NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/links/nonexistent/qr"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short Link Not Found"));
    }
}
