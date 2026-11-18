package com.platform.ingestion.persistence;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
