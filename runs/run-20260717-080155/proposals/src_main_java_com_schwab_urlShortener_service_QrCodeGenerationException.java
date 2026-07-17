package com.schwab.urlShortener.service;

public class QrCodeGenerationException extends RuntimeException {
    
    public QrCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
