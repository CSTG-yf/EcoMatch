package com.tencent.supersonic.headless.core.adaptor.db;

import com.tencent.supersonic.headless.core.pojo.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseDbAdaptorMetadataSafetyTest {

    @Test
    void closesConnectionAfterDatabaseMetadataRead() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet schemas = mock(ResultSet.class);
        ResultSet catalogs = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSchemas()).thenReturn(schemas);
        when(metadata.getCatalogs()).thenReturn(catalogs);
        when(schemas.next()).thenReturn(true, false);
        when(schemas.getString("TABLE_SCHEM")).thenReturn("bank");
        when(catalogs.next()).thenReturn(false);

        assertEquals(1, new TestAdaptor(connection).getDBs(new ConnectInfo()).size());

        verify(schemas).close();
        verify(catalogs).close();
        verify(connection).close();
    }

    @Test
    void fallsBackToCatalogsWhenSchemaMetadataFails() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet schemas = mock(ResultSet.class);
        ResultSet catalogs = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSchemas()).thenReturn(schemas);
        when(metadata.getCatalogs()).thenReturn(catalogs);
        when(schemas.next()).thenThrow(new SQLException("schemas unsupported"));
        when(catalogs.next()).thenReturn(true, false);
        when(catalogs.getString("TABLE_CAT")).thenReturn("bank");

        assertEquals(List.of("bank"), new TestAdaptor(connection).getDBs(new ConnectInfo()));

        verify(schemas).close();
        verify(catalogs).close();
        verify(connection).close();
    }

    @Test
    void rejectsMetadataReadWhenSchemasAndCatalogsBothFail() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet schemas = mock(ResultSet.class);
        ResultSet catalogs = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSchemas()).thenReturn(schemas);
        when(metadata.getCatalogs()).thenReturn(catalogs);
        when(schemas.next()).thenThrow(new SQLException("schemas failed"));
        when(catalogs.next()).thenThrow(new SQLException("catalogs failed"));

        SQLException failure = assertThrows(SQLException.class,
                () -> new TestAdaptor(connection).getDBs(new ConnectInfo()));

        assertEquals("Database schema and catalog metadata reads failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        verify(schemas).close();
        verify(catalogs).close();
        verify(connection).close();
    }

    @Test
    void configuresStatementTimeoutAndRowLimit() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new TestAdaptor(connection).getCatalogs(new ConnectInfo());

        verify(statement).setQueryTimeout(BaseDbAdaptor.METADATA_QUERY_TIMEOUT_SECONDS);
        verify(statement).setMaxRows(BaseDbAdaptor.MAX_METADATA_ROWS + 1);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void closesStatementWhenMetadataConfigurationFails() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doThrow(new SQLException("unsupported timeout")).when(statement)
                .setQueryTimeout(BaseDbAdaptor.METADATA_QUERY_TIMEOUT_SECONDS);

        assertThrows(SQLException.class,
                () -> new TestAdaptor(connection).getCatalogs(new ConnectInfo()));

        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void rejectsOversizedMetadataAndClosesResources() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("catalog");

        assertThrows(SQLException.class,
                () -> new TestAdaptor(connection).getCatalogs(new ConnectInfo()));

        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void closesConnectionWhenColumnMetadataReadFails() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getColumns(null, "bank", "account", null)).thenReturn(columns);
        when(columns.next()).thenThrow(new SQLException("driver failed"));

        assertThrows(SQLException.class, () -> new TestAdaptor(connection)
                .getColumns(new ConnectInfo(), null, "bank", "account"));

        verify(columns).close();
        verify(connection).close();
    }

    @Test
    void escapesJdbcMetadataWildcardsInExactIdentifiers() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getColumns(null, "bank\\_core", "account\\_ledger", null))
                .thenReturn(columns);
        when(columns.next()).thenReturn(false);

        new TestAdaptor(connection).getColumns(new ConnectInfo(), null, "bank_core",
                "account_ledger");

        verify(metadata).getColumns(null, "bank\\_core", "account\\_ledger", null);
        verify(columns).close();
        verify(connection).close();
    }

    @Test
    void rejectsMissingJdbcMetadataPatternEscapeAndClosesConnection() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("");

        SQLException failure = assertThrows(SQLException.class, () -> new TestAdaptor(connection)
                .getColumns(new ConnectInfo(), null, "bank_core", "account_ledger"));

        assertEquals("Database metadata pattern escape is unavailable", failure.getMessage());
        verify(connection).close();
    }

    private static class TestAdaptor extends DefaultDbAdaptor {

        private final Connection connection;

        TestAdaptor(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection(ConnectInfo connectionInfo) {
            return connection;
        }
    }
}
