package com.schwab.urlShortener.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class UrlValidatorTest {
    
    private final UrlValidator validator = new UrlValidator();

    @Test
    void validHttpUrl_shouldPass() {
        assertDoesNotThrow(() -> validator.validate("http://example.com"));
    }

    @Test
    void validHttpsUrl_shouldPass() {
        assertDoesNotThrow(() -> validator.validate("https://example.com"));
    }

    @Test
    void validUrlWithPath_shouldPass() {
        assertDoesNotThrow(() -> validator.validate("https://example.com/path/to/resource"));
    }

    @Test
    void validUrlWithQueryParams_shouldPass() {
        assertDoesNotThrow(() -> validator.validate("https://example.com/path?param=value&other=123"));
    }

    @Test
    void nullUrl_shouldThrowException() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate(null)
        );
        assertTrue(ex.getMessage().contains("cannot be empty"));
    }

    @Test
    void emptyUrl_shouldThrowException() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("")
        );
        assertTrue(ex.getMessage().contains("cannot be empty"));
    }

    @Test
    void javascriptScheme_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("javascript:alert('xss')")
        );
        assertTrue(ex.getMessage().contains("scheme must be http or https"));
    }

    @Test
    void dataScheme_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("data:text/html,<script>alert('xss')</script>")
        );
        assertTrue(ex.getMessage().contains("scheme must be http or https"));
    }

    @Test
    void fileScheme_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("file:///etc/passwd")
        );
        assertTrue(ex.getMessage().contains("scheme must be http or https"));
    }

    @Test
    void ftpScheme_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("ftp://files.example.com/file.txt")
        );
        assertTrue(ex.getMessage().contains("scheme must be http or https"));
    }

    @Test
    void localhostUrl_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://localhost:8080/admin")
        );
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    void localhostVariation_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://localhost.localdomain/admin")
        );
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    void loopbackIpv4_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://127.0.0.1/admin")
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @Test
    void loopbackIpv4Variant_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://127.1.1.1/admin")
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://10.0.0.1/admin",
        "http://10.255.255.255/admin",
        "http://10.1.2.3:8080/resource"
    })
    void rfc1918ClassA_shouldBeRejected(String url) {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate(url)
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://172.16.0.1/admin",
        "http://172.31.255.255/admin",
        "http://172.20.10.5:8080/resource"
    })
    void rfc1918ClassB_shouldBeRejected(String url) {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate(url)
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://192.168.0.1/admin",
        "http://192.168.255.255/admin",
        "http://192.168.1.1:8080/resource"
    })
    void rfc1918ClassC_shouldBeRejected(String url) {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate(url)
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://169.254.0.1/metadata",
        "http://169.254.169.254/latest/meta-data"
    })
    void linkLocalAddress_shouldBeRejected(String url) {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate(url)
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @Test
    void ipv6Loopback_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://[::1]/admin")
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @Test
    void ipv6LinkLocal_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http://[fe80::1]/admin")
        );
        assertTrue(ex.getMessage().contains("private or internal"));
    }

    @Test
    void urlWithoutHost_shouldBeRejected() {
        InvalidUrlException ex = assertThrows(
            InvalidUrlException.class,
            () -> validator.validate("http:///path")
        );
        assertTrue(ex.getMessage().contains("valid host"));
    }
}
