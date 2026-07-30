package com.tencent.supersonic.headless.core.adaptor.db;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.enums.FieldType;
import com.tencent.supersonic.headless.core.pojo.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

@Slf4j
public abstract class BaseDbAdaptor implements DbAdaptor {

    protected static final int MAX_METADATA_ROWS = 10_000;
    protected static final int METADATA_QUERY_TIMEOUT_SECONDS = 30;
    private static final Pattern SAFE_METADATA_IDENTIFIER =
            Pattern.compile("^[\\p{L}\\p{N}_$-]+(?:\\.[\\p{L}\\p{N}_$-]+)*$");

    @Override
    public List<String> getCatalogs(ConnectInfo connectInfo) throws SQLException {
        List<String> catalogs = Lists.newArrayList();
        try (Connection con = getConnection(connectInfo);
                Statement st = createMetadataStatement(con);
                ResultSet rs = st.executeQuery("SHOW CATALOGS")) {
            while (rs.next()) {
                checkMetadataRowLimit(catalogs.size() + 1);
                catalogs.add(rs.getString(1));
            }
        }
        return catalogs;
    }

    public List<String> getDBs(ConnectInfo connectionInfo, String catalog) throws SQLException {
        // Except for special types implemented separately, the generic logic catalog does not take
        // effect.
        validateMetadataIdentifier(catalog, false);
        return getDBs(connectionInfo);
    }

    protected List<String> getDBs(ConnectInfo connectionInfo) throws SQLException {
        List<String> dbs = Lists.newArrayList();
        Exception schemaFailure = null;
        Exception catalogFailure = null;
        try (Connection connection = getConnection(connectionInfo)) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet schemaSet = metadata.getSchemas()) {
                while (schemaSet.next()) {
                    checkMetadataRowLimit(dbs.size() + 1);
                    String db = schemaSet.getString("TABLE_SCHEM");
                    dbs.add(db);
                }
            } catch (MetadataLimitExceededException e) {
                throw e;
            } catch (Exception e) {
                schemaFailure = e;
                log.warn("Get metadata schemas failed: type={}, error=[{}]",
                        e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
                log.warn("get meta schemas failed, try to get catalogs");
            }
            try (ResultSet catalogSet = metadata.getCatalogs()) {
                while (catalogSet.next()) {
                    checkMetadataRowLimit(dbs.size() + 1);
                    String db = catalogSet.getString("TABLE_CAT");
                    dbs.add(db);
                }
            } catch (MetadataLimitExceededException e) {
                throw e;
            } catch (Exception e) {
                catalogFailure = e;
                log.warn("Get metadata catalogs failed: type={}, error=[{}]",
                        e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
                log.warn("metadata catalog fallback failed");
            }
        }
        if (schemaFailure != null && catalogFailure != null) {
            SQLException failure = new SQLException(
                    "Database schema and catalog metadata reads failed", schemaFailure);
            failure.addSuppressed(catalogFailure);
            throw failure;
        }
        return dbs;
    }

    @Override
    public List<String> getTables(ConnectInfo connectInfo, String catalog, String schemaName)
            throws SQLException {
        // Except for special types implemented separately, the generic logic catalog does not take
        // effect.
        validateMetadataIdentifier(catalog, false);
        validateMetadataIdentifier(schemaName, true);
        return getTables(connectInfo, schemaName);
    }

    protected List<String> getTables(ConnectInfo connectionInfo, String schemaName)
            throws SQLException {
        validateMetadataIdentifier(schemaName, true);
        List<String> tablesAndViews = new ArrayList<>();

        try (Connection connection = getConnection(connectionInfo)) {
            try (ResultSet resultSet = getResultSet(schemaName, connection.getMetaData())) {
                while (resultSet.next()) {
                    checkMetadataRowLimit(tablesAndViews.size() + 1);
                    String name = resultSet.getString("TABLE_NAME");
                    tablesAndViews.add(name);
                }
            }
        } catch (SQLException e) {
            log.error("Get metadata tables and views failed: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
            throw e;
        }
        return tablesAndViews;
    }

    protected ResultSet getResultSet(String schemaName, DatabaseMetaData metaData)
            throws SQLException {
        String schemaPattern = escapeMetadataPattern(metaData, schemaName);
        return metaData.getTables(schemaName, schemaPattern, null, new String[] {"TABLE", "VIEW"});
    }

    public List<DBColumn> getColumns(ConnectInfo connectInfo, String catalog, String schemaName,
            String tableName) throws SQLException {
        validateMetadataIdentifier(catalog, false);
        validateMetadataIdentifier(schemaName, true);
        validateMetadataIdentifier(tableName, true);
        List<DBColumn> dbColumns = new ArrayList<>();
        // 确保连接会自动关闭
        try (Connection connection = getConnection(connectInfo)) {
            DatabaseMetaData metadata = connection.getMetaData();
            String schemaPattern = escapeMetadataPattern(metadata, schemaName);
            String tablePattern = escapeMetadataPattern(metadata, tableName);
            try (ResultSet columns =
                    metadata.getColumns(catalog, schemaPattern, tablePattern, null)) {
                while (columns.next()) {
                    checkMetadataRowLimit(dbColumns.size() + 1);
                    String columnName = columns.getString("COLUMN_NAME");
                    String dataType = columns.getString("TYPE_NAME");
                    String remarks = columns.getString("REMARKS");
                    FieldType fieldType = classifyColumnType(dataType);
                    dbColumns.add(new DBColumn(columnName, dataType, remarks, fieldType));
                }
            }
        }
        return dbColumns;
    }

    protected Statement createMetadataStatement(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.setQueryTimeout(METADATA_QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(MAX_METADATA_ROWS + 1);
            return statement;
        } catch (SQLException | RuntimeException e) {
            try {
                statement.close();
            } catch (SQLException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    protected void checkMetadataRowLimit(int rowCount) throws SQLException {
        if (rowCount > MAX_METADATA_ROWS) {
            throw new MetadataLimitExceededException(
                    "Metadata result row limit exceeded: " + MAX_METADATA_ROWS);
        }
    }

    protected void validateMetadataIdentifier(String identifier, boolean required)
            throws SQLException {
        if (identifier == null || identifier.isBlank()) {
            if (required) {
                throw new SQLException("Database metadata identifier is required");
            }
            return;
        }
        if (!SAFE_METADATA_IDENTIFIER.matcher(identifier).matches()) {
            throw new SQLException("Invalid database metadata identifier");
        }
    }

    protected String escapeMetadataPattern(DatabaseMetaData metadata, String identifier)
            throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            throw new SQLException("Database metadata pattern escape is unavailable");
        }
        return identifier.replace(escape, escape + escape).replace("_", escape + "_").replace("%",
                escape + "%");
    }

    protected static class MetadataLimitExceededException extends SQLException {

        MetadataLimitExceededException(String message) {
            super(message);
        }
    }

    public Connection getConnection(ConnectInfo connectionInfo) throws SQLException {
        final Properties properties = getProperties(connectionInfo);
        return DriverManager.getConnection(connectionInfo.getUrl(), properties);
    }

    public FieldType classifyColumnType(String typeName) {
        switch (typeName.toUpperCase()) {
            case "INT":
            case "INTEGER":
            case "BIGINT":
            case "SMALLINT":
            case "TINYINT":
            case "FLOAT":
            case "DOUBLE":
            case "DECIMAL":
            case "NUMERIC":
                return FieldType.measure;
            case "DATE":
            case "TIME":
            case "TIMESTAMP":
                return FieldType.time;
            default:
                return FieldType.categorical;
        }
    }

    public Properties getProperties(ConnectInfo connectionInfo) {
        final Properties properties = new Properties();
        String url = connectionInfo.getUrl().toLowerCase();

        // 设置通用属性
        String userName = Optional.ofNullable(connectionInfo.getUserName()).orElse("");
        properties.setProperty("user", userName);


        String password = Optional.ofNullable(connectionInfo.getPassword()).orElse("");
        // 针对 Presto 和 Trino ssl=false 的情况，不需要设置密码
        if (url.startsWith("jdbc:presto") || url.startsWith("jdbc:trino")) {
            // 检查是否需要处理 SSL
            if (!url.contains("ssl=false")) {
                properties.setProperty("password", password);
            }
        } else {
            // 针对其他数据库类型
            properties.setProperty("password", password);
        }

        return properties;
    }
}
