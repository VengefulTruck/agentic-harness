package com.schwab.urlShortener.api;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.schwab.urlShortener.dto.CreateLinkRequest;
import com.schwab.urlShortener.dto.LinkStatsResponse;
import com.schwab.urlShortener.dto.ShortLinkResponse;
import com.schwab.urlShortener.service.ShortLinkService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/links")
public class ShortLinkController {

    private final ShortLinkService service;
    private final String baseUrl;
    private final String redirectPath;

    public ShortLinkController(
            ShortLinkService service,
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.redirect-path}") String redirectPath) {
        this.service = service;
        this.baseUrl = baseUrl;
        this.redirectPath = redirectPath;
    }

    @PostMapping
    public ResponseEntity<ShortLinkResponse> createShortLink(@Valid @RequestBody CreateLinkRequest request) {
        var link = service.createShortLink(request.targetUrl());
        var response = ShortLinkResponse.from(link, baseUrl, redirectPath);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<ShortLinkResponse> getShortLink(@PathVariable String shortCode) {
        var link = service.resolve(shortCode);
        var response = ShortLinkResponse.from(link, baseUrl, redirectPath);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<LinkStatsResponse> getStats(@PathVariable String shortCode) {
        var link = service.resolve(shortCode);
        var stats = service.getStats(shortCode);
        return ResponseEntity.ok(LinkStatsResponse.from(link, stats, baseUrl, redirectPath));
    }

    @GetMapping("/{shortCode}/qr")
    public ResponseEntity<byte[]> getQrCode(@PathVariable String shortCode) throws WriterException, IOException {
        var link = service.resolve(shortCode);
        String shortUrl = baseUrl + redirectPath + "/" + link.getShortCode();
        
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(shortUrl, BarcodeFormat.QR_CODE, 250, 250);
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        byte[] qrCodeBytes = outputStream.toByteArray();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(qrCodeBytes);
    }
}
