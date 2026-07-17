package com.schwab.urlShortener.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.service.InvalidUrlException;
import com.schwab.urlShortener.service.ShortLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShortLinkController.class)
class ShortLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ShortLinkService shortLinkService;

    @Test
    void createShortLink_withValidUrl_shouldReturn201() throws Exception {
        String longUrl = "https://example.com/very/long/path";
        String shortCode = "abc123";
        ShortLink link = new ShortLink(longUrl, shortCode);
        
        when(shortLinkService.createShortLink(longUrl)).thenReturn(link);
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(longUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(header().string("Location", containsString("/r/" + shortCode)))
            .andExpect(jsonPath("$.shortCode").value(shortCode))
            .andExpect(jsonPath("$.longUrl").value(longUrl))
            .andExpect(jsonPath("$.shortUrl").value(containsString("/r/" + shortCode)));
    }

    @Test
    void createShortLink_withNullUrl_shouldReturn400() throws Exception {
        CreateShortLinkRequest request = new CreateShortLinkRequest(null);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value(containsString("longUrl")));
    }

    @Test
    void createShortLink_withEmptyUrl_shouldReturn400() throws Exception {
        CreateShortLinkRequest request = new CreateShortLinkRequest("");
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value(containsString("longUrl")));
    }

    @Test
    void createShortLink_withInvalidUrl_shouldReturn400() throws Exception {
        String invalidUrl = "not-a-url";
        
        when(shortLinkService.createShortLink(invalidUrl))
            .thenThrow(new InvalidUrlException("Invalid URL format"));
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(invalidUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid URL"))
            .andExpect(jsonPath("$.detail").value(containsString("Invalid URL format")));
    }

    @Test
    void createShortLink_withDangerousScheme_shouldReturn400() throws Exception {
        String dangerousUrl = "javascript:alert('xss')";
        
        when(shortLinkService.createShortLink(dangerousUrl))
            .thenThrow(new InvalidUrlException("URL scheme must be http or https"));
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(dangerousUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid URL"));
    }

    @Test
    void createShortLink_withInternalIpAddress_shouldReturn400() throws Exception {
        String internalUrl = "http://192.168.1.1/admin";
        
        when(shortLinkService.createShortLink(internalUrl))
            .thenThrow(new InvalidUrlException("URLs pointing to private or internal IP addresses are not allowed"));
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(internalUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid URL"))
            .andExpect(jsonPath("$.detail").value(containsString("private or internal")));
    }

    @Test
    void createShortLink_withLocalhostUrl_shouldReturn400() throws Exception {
        String localhostUrl = "http://localhost:8080/admin";
        
        when(shortLinkService.createShortLink(localhostUrl))
            .thenThrow(new InvalidUrlException("URLs pointing to localhost are not allowed"));
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(localhostUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid URL"))
            .andExpect(jsonPath("$.detail").value(containsString("localhost")));
    }

    @Test
    void createShortLink_withMalformedJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createShortLink_withUrlExceedingMaxLength_shouldReturn400() throws Exception {
        String veryLongUrl = "https://example.com/" + "a".repeat(2048);
        
        CreateShortLinkRequest request = new CreateShortLinkRequest(veryLongUrl);
        
        mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value(containsString("longUrl")));
    }
}
