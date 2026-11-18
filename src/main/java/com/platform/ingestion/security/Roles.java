package com.platform.ingestion.security;

/** Authority names (without ROLE_ prefix) derived from Keycloak realm roles. */
public final class Roles {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TENANT_INGEST = "TENANT_INGEST";

    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String HEADER_TENANT_ID = "X-Tenant-ID";

    private Roles() {}
}
