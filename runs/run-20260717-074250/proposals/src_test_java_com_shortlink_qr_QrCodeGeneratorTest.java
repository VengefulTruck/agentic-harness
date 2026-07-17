package com.shortlink.qr;

import com.google.zxing.WriterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeGeneratorTest {
    
    private QrCodeGenerator qrCodeGenerator;
    
    @BeforeEach
    void setUp() {
        qrCodeGenerator = new QrCodeGenerator();
    }
    
    @Test
    void testGenerateQrCode_ValidUrl_ReturnsPngBytes() throws WriterException, IOException {
        String url = "https://example.com";
        
        byte[] result = qrCodeGenerator.generateQrCode(url);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(isPngImage(result));
    }
    
    @Test
    void testGenerateQrCode_CustomDimensions_ReturnsPngBytes() throws WriterException, IOException {
        String url = "https://example.com";
        int width = 400;
        int height = 400;
        
        byte[] result = qrCodeGenerator.generateQrCode(url, width, height);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(isPngImage(result));
    }
    
    @Test
    void testGenerateQrCode_LongUrl_ReturnsPngBytes() throws WriterException, IOException {
        String url = "https://example.com/very/long/path/with/many/segments/and/query?param1=value1&param2=value2";
        
        byte[] result = qrCodeGenerator.generateQrCode(url);
        
        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(isPngImage(result));
    }
    
    @Test
    void testGenerateQrCode_EmptyUrl_ThrowsException() {
        String url = "";
        
        assertThrows(WriterException.class, () -> {
            qrCodeGenerator.generateQrCode(url);
        });
    }
    
    private boolean isPngImage(byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }
        return data[0] == (byte) 0x89 && 
               data[1] == (byte) 0x50 && 
               data[2] == (byte) 0x4E && 
               data[3] == (byte) 0x47 &&
               data[4] == (byte) 0x0D &&
               data[5] == (byte) 0x0A &&
               data[6] == (byte) 0x1A &&
               data[7] == (byte) 0x0A;
    }
}
