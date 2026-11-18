package com.platform.ingestion.common;

/** One entry of the {@code errors} array of the error body. */
public record FieldError(String field, String message) {}
