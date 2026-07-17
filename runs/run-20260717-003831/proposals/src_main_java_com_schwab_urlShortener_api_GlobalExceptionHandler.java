package com.schwab.urlShortener.api;

import com.schwab.urlShortener.exception.InvalidUrlException;
import com.schwab.urlShortener.exception.ShortCodeGenerationException;
import com.schwab.urlShortener.exception.ShortLinkNotFoundException;
import com.schwab.urlShortener.ratelimit.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ProblemDetail handleShortLinkNotFound(ShortLinkNotFoundException ex) {
        logger.debug("Short link not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/short-link-not-found"));
        problemDetail.setTitle("Short Link Not Found");
        return problemDetail;
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException ex) {
        logger.debug("Invalid URL provided: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/invalid-url"));
        problemDetail.setTitle("Invalid URL");
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        logger.debug("Validation error: {}", ex.getMessage());
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            detail
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/validation-error"));
        problemDetail.setTitle("Validation Error");
        return problemDetail;
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ProblemDetail handleShortCodeGeneration(ShortCodeGenerationException ex) {
        logger.error("Failed to generate short code: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Failed to generate a unique short code. Please try again."
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/short-code-generation-failed"));
        problemDetail.setTitle("Short Code Generation Failed");
        return problemDetail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        logger.debug("Malformed JSON request: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Malformed JSON request"
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/malformed-request"));
        problemDetail.setTitle("Malformed Request");
        return problemDetail;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail handleRateLimitExceeded(RateLimitExceededException ex) {
        logger.debug("Rate limit exceeded: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS,
            ex.getMessage()
        );
        problemDetail.setType(URI.create("https://api.schwab.com/problems/rate-limit-exceeded"));
        problemDetail.setTitle("Rate Limit Exceeded");
        return problemDetail;
    }
}
