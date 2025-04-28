package com.moniewise.moniewise_backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TncAcceptanceRequiredException.class)
    public ResponseEntity<?> handleTncAcceptance(TncAcceptanceRequiredException ex) {
        return ResponseEntity.status(403).body(Map.of(
                "error", ex.getMessage(),
                "tnc", ex.getTncContent(),
                "version", ex.getTncVersion()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }


    @ExceptionHandler(OtpVerificationException.class)
    public ResponseEntity<Map<String, String>> handleOtpVerificationException(OtpVerificationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}