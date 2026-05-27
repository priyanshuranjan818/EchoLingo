package com.echolingo.server.exception;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AppError.class)
    ResponseEntity<ApiErrorResponse> handleAppError(AppError error) {
        return ResponseEntity.status(error.status())
                .body(new ApiErrorResponse(error.status().value(), error.getMessage(), Instant.now().toString()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception error) {
        return ResponseEntity.internalServerError()
                .body(new ApiErrorResponse(500, "Unexpected server error", Instant.now().toString()));
    }

    record ApiErrorResponse(int status, String message, String timestamp) {
    }
}
