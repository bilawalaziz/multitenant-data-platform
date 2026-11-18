package com.platform.ingestion.common;

import org.springframework.http.HttpStatus;

/** Error codes returned in the {@code code} field of the error body. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN),
    TENANT_NOT_REGISTERED(HttpStatus.FORBIDDEN),
    TENANT_INACTIVE(HttpStatus.FORBIDDEN),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST),
    PAYLOAD_VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    NO_ACTIVE_CONTRACT(HttpStatus.UNPROCESSABLE_ENTITY),
    DEVICE_NOT_REGISTERED(HttpStatus.UNPROCESSABLE_ENTITY),
    TRANSFORMATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INVALID_CONTRACT(HttpStatus.BAD_REQUEST),
    INVALID_MAPPING(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
