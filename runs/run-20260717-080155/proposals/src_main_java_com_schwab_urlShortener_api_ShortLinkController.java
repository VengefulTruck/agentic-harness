package com.schwab.urlShortener.api;

import com.schwab.urlShortener.domain.LinkStats;
import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.service.QrCodeService;
import com.schwab.urlShortener.service.ShortLinkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/links")
public class ShortLinkController {

    private final ShortLinkService service;
    private final QrCodeService qrCodeService;
    private final String baseUrl;
    private final String redirectPath;

    public ShortLinkController(
            ShortLinkService service,
            QrCodeService qrCodeService,
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.redirect-path}") String redirectPath) {
        this.service = service;
        this.qrCodeService = qrCodeService;
        this.baseUrl = baseUrl;
        this.redirectPath = redirectPath;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createShortLink(@RequestBody Map<String, String> request) {
        String longUrl = request.get("url");
        ShortLink shortLink = service.create(longUrl);
        
        String shortUrl = baseUrl + redirectPath + shortLink.getShortCode();
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                        "shortCode", shortLink.getShortCode(),
                        "shortUrl", shortUrl,
                        "longUrl", shortLink.getLongUrl()
                ));
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Map<String, Object>> getShortLink(@PathVariable String shortCode) {
        ShortLink shortLink = service.resolve(shortCode);
        
        String shortUrl = baseUrl + redirectPath + shortLink.getShortCode();
        
        return ResponseEntity.ok(Map.of(
                "shortCode", shortLink.getShortCode(),
                "shortUrl", shortUrl,
                "longUrl", shortLink.getLongUrl(),
                "createdAt", shortLink.getCreatedAt()
        ));
    }

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<LinkStats> getStats(@PathVariable String shortCode) {
        LinkStats stats = service.getStats(shortCode);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{shortCode}/qr")
    public ResponseEntity<byte[]> getQrCode(@PathVariable String shortCode) {
        ShortLink shortLink = service.resolve(shortCode);
        
        String shortUrl = baseUrl + redirectPath + shortLink.getShortCode();
        
        byte[] qrCodeImage = qrCodeService.generateQrCodePng(shortUrl, 250, 250);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(qrCodeImage);
    }
}
