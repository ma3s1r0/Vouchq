package com.vouchq.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates service-layer exceptions into clean JSON error responses for the
 * public {@code /api} surface. Keeps controllers free of try/catch noise and
 * never leaks stack traces or JPA internals to clients.
 */
@RestControllerAdvice(basePackages = {"com.vouchq.api", "com.vouchq.install", "com.vouchq.verify"})
public class ApiExceptionHandler {

    /** Unknown id / cross-org / bad input → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ApiError> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiDtos.ApiError("bad_request", ex.getMessage()));
    }

    /** Rejected upload (zip-slip / too large / too many entries) → 400. */
    @ExceptionHandler(com.vouchq.ingestion.ZipExtractor.UnsafeZipException.class)
    public ResponseEntity<ApiDtos.ApiError> handleUnsafeZip(
            com.vouchq.ingestion.ZipExtractor.UnsafeZipException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiDtos.ApiError("bad_request", ex.getMessage()));
    }

    /** Illegal lifecycle transition (e.g. approving an unversioned tool) → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiDtos.ApiError> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiDtos.ApiError("conflict", ex.getMessage()));
    }
}
