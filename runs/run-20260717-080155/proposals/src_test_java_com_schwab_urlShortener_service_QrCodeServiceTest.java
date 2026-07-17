package com.schwab.urlShortener.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeServiceTest {

    private final QrCodeService qrCodeService = new QrCodeService();

    @Test
    void generateQrCodePng_shouldReturnValidPngByteArray() {
        String testData = "https://example.com/abc123";
        
        byte[] result = qrCodeService.generateQrCodePng(testData, 250, 250);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(result[0] == (byte) 0x89 && result[1] == 0x50 && result[2] == 0x4E && result[3] == 0x47);
    }

    @Test
    void generateQrCodePng_shouldHandleDifferentDimensions() {
        String testData = "https://example.com/test";
        
        byte[] result = qrCodeService.generateQrCodePng(testData, 100, 100);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void generateQrCodePng_shouldHandleEmptyString() {
        byte[] result = qrCodeService.generateQrCodePng("", 250, 250);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void generateQrCodePng_shouldHandleLongUrls() {
        String longUrl = "https://example.com/" + "a".repeat(1000);
        
        byte[] result = qrCodeService.generateQrCodePng(longUrl, 250, 250);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
    }
}
