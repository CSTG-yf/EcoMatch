package com.tencent.supersonic.headless.core.utils;

import com.tencent.supersonic.headless.core.pojo.DuckDbSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JdbcDuckDbUtilsSecurityTest {

    @Test
    void identifiersRejectStatementInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcDuckDbUtils.requireIdentifier("safe; DROP TABLE account", "view"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcDuckDbUtils.requireIdentifier("\"quoted\"", "view"));
        assertEquals("safe_view", JdbcDuckDbUtils.requireIdentifier("safe_view", "view"));
    }

    @Test
    void parquetPathIsEscapedAsOneSqlLiteral() {
        assertEquals("bank''s/file.parquet",
                JdbcDuckDbUtils.escapeRequiredLiteral("bank's/file.parquet", "path"));
    }

    @Test
    void viewRejectsAdditionalStatementsBeforeExecution() {
        DuckDbSource source = mock(DuckDbSource.class);

        assertThrows(IllegalArgumentException.class, () -> JdbcDuckDbUtils.createView(source,
                "safe_view", "SELECT 1; DROP TABLE account"));
        assertThrows(IllegalArgumentException.class, () -> JdbcDuckDbUtils.createView(source,
                "safe_view", "CREATE TABLE account(id INT)"));
    }

    @Test
    void mysqlConnectionValuesCannotCloseAttachLiteral() throws Exception {
        DuckDbSource source = mock(DuckDbSource.class);

        JdbcDuckDbUtils.attachMysql(source, "db.internal", 3306, "user", "pa'ss", "bank");

        verify(source).execute(contains("password=pa''ss"));
    }

    @Test
    void mysqlConnectionValuesRejectOptionSeparators() {
        DuckDbSource source = mock(DuckDbSource.class);

        assertThrows(IllegalArgumentException.class, () -> JdbcDuckDbUtils.attachMysql(source,
                "db.internal password=attacker", 3306, "user", "pass", "bank"));
    }
}
