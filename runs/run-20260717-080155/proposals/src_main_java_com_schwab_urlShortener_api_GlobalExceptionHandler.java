package com.schwab.urlShortener.api;

import com.schwab.urlShortener.service.InvalidUrlException;
import com.schwab.urlShortener.service.QrCodeGenerationException;
import com.schwab.urlShortener.service.ShortCodeGenerationException;
import com.schwab.urlShortener.service.ShortLinkNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setType(URI.create("https://api.schwab.com/problems/invalid-url"));
        problem.setTitle("Invalid URL");
        return problem;
    }

    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ProblemDetail handleShortLinkNotFound(ShortLinkNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setType(URI.create("https://api.schwab.com/problems/short-link-not-found"));
        problem.setTitle("Short Link Not Found");
        return problem;
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ProblemDetail handleShortCodeGeneration(ShortCodeGenerationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage()
        );
        problem.setType(URI.create("https://api.schwab.com/problems/short-code-generation"));
        problem.setTitle("Short Code Generation Failed");
        return problem;
    }

    @ExceptionHandler(QrCodeGenerationException.class)
    public ProblemDetail handleQrCodeGeneration(QrCodeGenerationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage()
        );
        problem.setType(URI.create("https://api.schwab.com/problems/qr-code-generation"));
        problem.setTitle("QR Code Generation Failed");
        return problem;
    }
}
