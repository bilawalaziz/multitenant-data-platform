package com.platform.ingestion.common;

import java.util.List;

/** The single error body used for every error response. */
public record ErrorResponse(String status, String errorCode, String message, List<FieldError> errors) {

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> errors) {
        return new ErrorResponse("REJECTED", code.name(), message, errors == null ? List.of() : errors);
    }
}
