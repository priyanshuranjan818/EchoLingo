package com.echolingo.server.exception;

import org.springframework.http.HttpStatus;

public class AppError extends RuntimeException {
    private final HttpStatus status;

    public AppError(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
