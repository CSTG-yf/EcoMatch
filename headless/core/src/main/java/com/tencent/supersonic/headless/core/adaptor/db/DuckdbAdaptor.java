package com.tencent.supersonic.headless.core.adaptor.db;

import com.google.common.collect.Lists;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.enums.FieldType;
import com.tencent.supersonic.headless.core.pojo.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

@Slf4j
public class DuckdbAdaptor extends DefaultDbAdaptor {

    protected ResultSet getResultSet(String schemaName, DatabaseMetaData metaData)
            throws SQLException {
        validateMetadataIdentifier(schemaName, true);
        return metaData.getTables(schemaName, null, null, new String[] {"TABLE", "VIEW"});
    }

    public List<DBColumn> getColumns(ConnectInfo connectInfo, String catalog, String schemaName,
            String tableName) throws SQLException {
        validateMetadataIdentifier(catalog, false);
        validateMetadataIdentifier(schemaName, true);
        validateMetadataIdentifier(tableName, true);
        List<DBColumn> dbColumns = Lists.newArrayList();
        try (Connection connection = getConnection(connectInfo)) {
            DatabaseMetaData metadata = connection.getMetaData();
            String schemaPattern = escapeMetadataPattern(metadata, schemaName);
            String tablePattern = escapeMetadataPattern(metadata, tableName);
            try (ResultSet columns = metadata.getColumns(null, schemaPattern, tablePattern, null)) {
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

    @Override
    public String rewriteSql(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.replaceAll("`", "");
    }

    @Override
    public Properties getProperties(ConnectInfo connectionInfo) {
        return new Properties();
    }

}
