package com.tencent.supersonic.headless.core.pojo;

import javax.sql.DataSource;

import com.tencent.supersonic.headless.core.config.ExecutorConfig;
import com.tencent.supersonic.headless.core.gateway.QueryRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuckDbSourceSecurityTest {

    @Test
    void resultReadFailureIsPropagatedInsteadOfReturningPartialRows() throws Exception {
        ResultSet resultSet = integerResultSet();
        when(resultSet.next()).thenReturn(true).thenThrow(new SQLException("driver-secret"));

        assertThrows(SQLException.class, () -> DuckDbSource.buildResult(resultSet, 10));
    }

    @Test
    void applicationLimitRejectsDriverOverflowRow() throws Exception {
        ResultSet resultSet = integerResultSet();
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getInt(1)).thenReturn(1, 2);

        assertThrows(QueryRejectedException.class, () -> DuckDbSource.buildResult(resultSet, 1));
    }

    @Test
    void invalidResultLimitFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> DuckDbSource.buildResult(mock(ResultSet.class), 0));
    }

    @Test
    void jdbcTemplateReceivesTimeoutFetchAndOverflowProbeLimits() {
        ExecutorConfig config = config(false);
        config.setQueryTimeoutSeconds(17);
        config.setResultLimit(99);
        DuckDbSource source = new DuckDbSource(config) {
            @Override
            protected void init(JdbcTemplate jdbcTemplate) {}
        };

        JdbcTemplate template = source.getDuckDbTemplate(mock(DataSource.class));

        assertEquals(17, template.getQueryTimeout());
        assertEquals(100, template.getMaxRows());
        assertEquals(99, template.getFetchSize());
    }

    @Test
    void enabledDuckDbRejectsInvalidExecutionBoundsBeforeOpeningPool() {
        ExecutorConfig config = config(true);
        config.setResultLimit(0);

        assertThrows(IllegalArgumentException.class, () -> new DuckDbSource(config));
    }

    private ResultSet integerResultSet() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("id");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        return resultSet;
    }

    private ExecutorConfig config(boolean enabled) {
        ExecutorConfig config = new ExecutorConfig();
        config.setDuckEnable(enabled);
        config.setDuckDbTemp("target/duckdb");
        config.setDuckDbMaximumPoolSize(2);
        config.setDuckDbMaxLifetime(1_000);
        config.setMemoryLimit(1);
        config.setThreads(1);
        config.setQueryTimeoutSeconds(30);
        config.setResultLimit(100);
        return config;
    }
}
