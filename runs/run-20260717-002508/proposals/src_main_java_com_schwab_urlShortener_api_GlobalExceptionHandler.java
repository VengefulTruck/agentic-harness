package com.schwab.urlShortener.api;

import com.schwab.urlShortener.domain.InvalidUrlException;
import com.schwab.urlShortener.domain.ShortLinkNotFoundException;
import com.schwab.urlShortener.ratelimit.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Global exception handler that converts domain exceptions into RFC 9457 ProblemDetail responses.
 * <p>
 * This handler ensures consistent error response format across all API endpoints.
 * It logs client errors (4xx) at DEBUG level since they represent normal traffic patterns,
 * not application problems requiring investigation.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShortLinkNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleShortLinkNotFound(ShortLinkNotFoundException ex) {
        log.debug("Short link not found: {}", ex.getMessage());
        return problem(
                HttpStatus.NOT_FOUND,
                "Short Link Not Found",
                ex.getMessage(),
                "short-link-not-found"
        );
    }

    @ExceptionHandler(InvalidUrlException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleInvalidUrl(InvalidUrlException ex) {
        log.debug("Invalid URL submitted: {}", ex.getMessage());
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid URL",
                ex.getMessage(),
                "invalid-url"
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ProblemDetail handleRateLimitExceeded(RateLimitExceededException ex) {
        log.debug("Rate limit exceeded for IP {}: {} requests per {} seconds", 
                  ex.getClientIp(), ex.getLimit(), ex.getWindowSeconds());
        return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate Limit Exceeded",
                String.format("Too many requests. Limit: %d requests per %d seconds.", 
                              ex.getLimit(), ex.getWindowSeconds()),
                "rate-limit-exceeded"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.debug("Validation failed: {}", errors);
        return problem(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                errors,
                "validation-error"
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected error processing request", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred processing your request",
                "internal-error"
        );
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create("https://schwab.com/problems/" + type));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
