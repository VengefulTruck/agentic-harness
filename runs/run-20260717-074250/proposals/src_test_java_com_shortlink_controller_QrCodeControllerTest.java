package com.shortlink.controller;

import com.shortlink.service.ShortLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QrCodeController.class)
class QrCodeControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private ShortLinkService shortLinkService;
    
    @Test
    void testGetQrCode_ValidCode_ReturnsOkWithPngImage() throws Exception {
        String code = "abc123";
        String url = "https://example.com";
        
        when(shortLinkService.resolveShortLink(code)).thenReturn(Optional.of(url));
        
        mockMvc.perform(get("/s/{code}/qr", code))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().exists("Content-Length"))
                .andExpect(content().bytes(org.hamcrest.Matchers.notNullValue()));
    }
    
    @Test
    void testGetQrCode_InvalidCode_ReturnsNotFound() throws Exception {
        String code = "invalid";
        
        when(shortLinkService.resolveShortLink(code)).thenReturn(Optional.empty());
        
        mockMvc.perform(get("/s/{code}/qr", code))
                .andExpect(status().isNotFound());
    }
    
    @Test
    void testGetQrCode_ValidCode_ResponseHeadersCorrect() throws Exception {
        String code = "def456";
        String url = "https://example.com/test";
        
        when(shortLinkService.resolveShortLink(code)).thenReturn(Optional.of(url));
        
        mockMvc.perform(get("/s/{code}/qr", code))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().exists("Content-Length"));
    }
    
    @Test
    void testGetQrCode_DifferentCodes_GeneratesDifferentQrCodes() throws Exception {
        String code1 = "code1";
        String code2 = "code2";
        String url1 = "https://example.com/url1";
        String url2 = "https://example.com/url2";
        
        when(shortLinkService.resolveShortLink(code1)).thenReturn(Optional.of(url1));
        when(shortLinkService.resolveShortLink(code2)).thenReturn(Optional.of(url2));
        
        byte[] qr1 = mockMvc.perform(get("/s/{code}/qr", code1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        
        byte[] qr2 = mockMvc.perform(get("/s/{code}/qr", code2))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        
        org.junit.jupiter.api.Assertions.assertNotEquals(qr1.length, qr2.length);
    }
}
