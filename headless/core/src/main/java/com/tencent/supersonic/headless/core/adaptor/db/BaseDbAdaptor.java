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

@Slf4j
public abstract class BaseDbAdaptor implements DbAdaptor {

    protected static final int MAX_METADATA_ROWS = 10_000;
    protected static final int METADATA_QUERY_TIMEOUT_SECONDS = 30;

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
        return getDBs(connectionInfo);
    }

    protected List<String> getDBs(ConnectInfo connectionInfo) throws SQLException {
        List<String> dbs = Lists.newArrayList();
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
                log.warn("Get metadata catalogs failed: type={}, error=[{}]",
                        e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
                log.warn("get meta catalogs failed, try to get schemas");
            }
        }
        return dbs;
    }

    @Override
    public List<String> getTables(ConnectInfo connectInfo, String catalog, String schemaName)
            throws SQLException {
        // Except for special types implemented separately, the generic logic catalog does not take
        // effect.
        return getTables(connectInfo, schemaName);
    }

    protected List<String> getTables(ConnectInfo connectionInfo, String schemaName)
            throws SQLException {
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
        return metaData.getTables(schemaName, schemaName, null, new String[] {"TABLE", "VIEW"});
    }



    public List<DBColumn> getColumns(ConnectInfo connectInfo, String catalog, String schemaName,
            String tableName) throws SQLException {
        List<DBColumn> dbColumns = new ArrayList<>();
        // 确保连接会自动关闭
        try (Connection connection = getConnection(connectInfo);
                ResultSet columns =
                        connection.getMetaData().getColumns(catalog, schemaName, tableName, null)) {
            while (columns.next()) {
                checkMetadataRowLimit(dbColumns.size() + 1);
                String columnName = columns.getString("COLUMN_NAME");
                String dataType = columns.getString("TYPE_NAME");
                String remarks = columns.getString("REMARKS");
                FieldType fieldType = classifyColumnType(dataType);
                dbColumns.add(new DBColumn(columnName, dataType, remarks, fieldType));
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
