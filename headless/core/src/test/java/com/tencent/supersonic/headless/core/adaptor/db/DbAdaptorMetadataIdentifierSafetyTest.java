package com.tencent.supersonic.headless.core.adaptor.db;

import com.tencent.supersonic.headless.core.pojo.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbAdaptorMetadataIdentifierSafetyTest {

    private static final ConnectInfo CONNECT_INFO = new ConnectInfo();

    @Test
    void rejectsUnsafeIdentifiersInGenericMetadataMethods() {
        DefaultDbAdaptor adaptor = new DefaultDbAdaptor();

        assertInvalid(() -> adaptor.getDBs(CONNECT_INFO, "bank; DROP SCHEMA bank"));
        assertInvalid(() -> adaptor.getTables(CONNECT_INFO, null, "bank%"));
        assertInvalid(() -> adaptor.getColumns(CONNECT_INFO, null, "bank", "account%"));
        assertRequired(() -> adaptor.getTables(CONNECT_INFO, null, " "));
        assertRequired(() -> adaptor.getColumns(CONNECT_INFO, null, "bank", null));
    }

    @Test
    void rejectsUnsafeIdentifiersInStatementMetadataAdaptors() {
        PrestoAdaptor presto = new PrestoAdaptor();
        StarrocksAdaptor starrocks = new StarrocksAdaptor();
        KyuubiAdaptor kyuubi = new KyuubiAdaptor();

        assertInvalid(() -> presto.getDBs(CONNECT_INFO, "bank; SELECT 1"));
        assertInvalid(() -> presto.getTables(CONNECT_INFO, "bank", "schema --"));
        assertInvalid(() -> starrocks.getDBs(CONNECT_INFO, "bank\nSHOW DATABASES"));
        assertInvalid(() -> starrocks.getTables(CONNECT_INFO, "bank", "schema%"));
        assertInvalid(() -> starrocks.getColumns(CONNECT_INFO, "bank;SET CATALOG other", "schema",
                "account"));
        assertInvalid(() -> kyuubi.getDBs(CONNECT_INFO, "bank/*comment*/"));
        assertInvalid(() -> kyuubi.getTables(CONNECT_INFO, "bank", "schema%"));
    }

    @Test
    void rejectsJdbcMetadataPatternsInDialectColumnAdaptors() {
        assertInvalid(() -> new DuckdbAdaptor().getColumns(CONNECT_INFO, null, "bank", "account%"));
        assertInvalid(() -> new H2Adaptor().getColumns(CONNECT_INFO, null, "bank%", "account"));
        assertInvalid(() -> new PostgresqlAdaptor().getColumns(CONNECT_INFO, "catalog*", "bank",
                "account"));
    }

    @Test
    void acceptsSafeUnicodeAndQualifiedIdentifiers() throws Exception {
        IdentifierTestAdaptor adaptor = new IdentifierTestAdaptor();

        adaptor.validate("银行-数据.核心库", true);
        adaptor.validate("catalog_01.account_ledger", false);
        adaptor.validate(null, false);
    }

    private void assertInvalid(SqlCall call) {
        SQLException failure = assertThrows(SQLException.class, call::run);
        assertEquals("Invalid database metadata identifier", failure.getMessage());
    }

    private void assertRequired(SqlCall call) {
        SQLException failure = assertThrows(SQLException.class, call::run);
        assertEquals("Database metadata identifier is required", failure.getMessage());
    }

    @FunctionalInterface
    private interface SqlCall {

        void run() throws SQLException;
    }

    private static class IdentifierTestAdaptor extends DefaultDbAdaptor {

        void validate(String identifier, boolean required) throws SQLException {
            validateMetadataIdentifier(identifier, required);
        }
    }
}
