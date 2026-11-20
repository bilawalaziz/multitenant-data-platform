package com.platform.ingestion.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldError> errors;
    private final HttpStatus statusOverride;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldError> errors) {
        this(errorCode, message, errors, null);
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldError> errors, HttpStatus statusOverride) {
        super(message);
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.statusOverride = statusOverride;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public HttpStatus status() {
        return statusOverride != null ? statusOverride : errorCode.status();
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + " not found");
    }
}
