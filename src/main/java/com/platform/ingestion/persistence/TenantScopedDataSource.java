package com.platform.ingestion.persistence;

import com.platform.ingestion.tenant.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Publishes the current {@link TenantScope} to PostgreSQL on every checkout. Both settings are always overwritten
 * (empty when there is no scope), so a pooled connection can never carry the previous caller's tenant and the
 * row-level-security policies see no rows unless a scope is active.
 */
public class TenantScopedDataSource extends DelegatingDataSource {

    private static final String APPLY_SCOPE =
            "SELECT set_config('app.tenant_id', ?, false), set_config('app.rls_system', ?, false)";

    public TenantScopedDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scoped(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scoped(super.getConnection(username, password));
    }

    private static Connection scoped(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SCOPE)) {
            statement.setString(1, TenantScope.currentTenantSetting());
            statement.setString(2, TenantScope.currentSystemSetting());
            statement.execute();
        } catch (SQLException | RuntimeException e) {
            // never hand out a connection whose scope is unknown
            try {
                connection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return connection;
    }
}
