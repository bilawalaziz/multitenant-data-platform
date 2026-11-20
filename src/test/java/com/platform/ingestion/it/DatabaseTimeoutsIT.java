package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The configured limits must reach the pooled connections, not just the properties file. */
class DatabaseTimeoutsIT extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void pooledConnectionsCarryStatementAndLockTimeouts() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout())
                .isEqualTo(5000);
        assertThat(appJdbc.queryForObject("SHOW statement_timeout", String.class))
                .isEqualTo("15s");
        assertThat(appJdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }
}
