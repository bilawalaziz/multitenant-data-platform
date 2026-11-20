package com.platform.ingestion.tenant;

import java.util.UUID;

/**
 * The row-level-security context of the current thread: either one tenant or "system" (all tenants). It is read
 * every time a connection is taken from the pool, so a scope must be opened before the transaction or repository
 * call that needs it. Scopes nest; closing restores the enclosing one. With no scope the database returns no
 * tenant-owned rows.
 */
public final class TenantScope implements AutoCloseable {

    private record State(UUID tenantId, boolean system) {}

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private final State previous;
    private boolean closed;

    private TenantScope(State state) {
        this.previous = CURRENT.get();
        CURRENT.set(state);
    }

    public static TenantScope tenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return new TenantScope(new State(tenantId, false));
    }

    /** For work that legitimately spans tenants: outbox publishing, retention, metrics, platform administration. */
    public static TenantScope system() {
        return new TenantScope(new State(null, true));
    }

    /** Value for the {@code app.tenant_id} session setting; empty when the scope is not a single tenant. */
    public static String currentTenantSetting() {
        State state = CURRENT.get();
        return state == null || state.tenantId() == null ? "" : state.tenantId().toString();
    }

    /** Value for the {@code app.rls_system} session setting. */
    public static String currentSystemSetting() {
        State state = CURRENT.get();
        return state != null && state.system() ? "true" : "";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
