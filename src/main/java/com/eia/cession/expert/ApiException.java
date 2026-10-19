package com.eia.cession.expert;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API-visible failure carrying the structured error code and HTTP status.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Request validation failed",
                Map.of(field, message));
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of());
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, Map.of());
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message, Map.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
