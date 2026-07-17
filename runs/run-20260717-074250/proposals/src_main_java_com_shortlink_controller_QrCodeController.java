package com.shortlink.controller;

import com.google.zxing.WriterException;
import com.shortlink.qr.QrCodeGenerator;
import com.shortlink.service.ShortLinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class QrCodeController {
    
    private final ShortLinkService shortLinkService;
    private final QrCodeGenerator qrCodeGenerator;
    
    public QrCodeController(ShortLinkService shortLinkService) {
        this.shortLinkService = shortLinkService;
        this.qrCodeGenerator = new QrCodeGenerator();
    }
    
    @GetMapping("/s/{code}/qr")
    public ResponseEntity<byte[]> getQrCode(@PathVariable String code) {
        return shortLinkService.resolveShortLink(code)
                .map(url -> {
                    try {
                        byte[] qrCodeImage = qrCodeGenerator.generateQrCode(url);
                        
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.IMAGE_PNG);
                        headers.setContentLength(qrCodeImage.length);
                        
                        return new ResponseEntity<>(qrCodeImage, headers, HttpStatus.OK);
                    } catch (WriterException | IOException e) {
                        return new ResponseEntity<byte[]>(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                })
                .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }
}
