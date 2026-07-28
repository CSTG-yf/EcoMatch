package com.tencent.supersonic.headless.core.pojo;

import javax.sql.DataSource;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.config.ExecutorConfig;
import com.tencent.supersonic.headless.core.gateway.QueryRejectedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** duckDb connection session object */
@Component
public class DuckDbSource {
    protected DataSource duckDbDataSource;

    protected JdbcTemplate duckDbJdbcTemplate;

    protected HikariConfig hikariConfig;

    private final ExecutorConfig executorConfig;

    public DuckDbSource(ExecutorConfig executorConfig) {
        this.executorConfig = executorConfig;
        if (executorConfig.getDuckEnable()) {
            validateConfig();
            hikariConfig = getHikariConfig();
            duckDbDataSource = getDuckDbDataSource(hikariConfig);
            duckDbJdbcTemplate = getDuckDbTemplate(duckDbDataSource);
        }
    }

    public HikariConfig getHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.duckdb.DuckDBDriver");
        config.setMaximumPoolSize(executorConfig.getDuckDbMaximumPoolSize());
        config.setMaxLifetime(executorConfig.getDuckDbMaxLifetime());
        config.setJdbcUrl("jdbc:duckdb:");
        return config;
    }

    public DataSource getDuckDbDataSource(HikariConfig config) {
        HikariDataSource ds = new HikariDataSource(config);
        return ds;
    }

    public JdbcTemplate getDuckDbTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate();
        jdbcTemplate.setDataSource(dataSource);
        jdbcTemplate.setQueryTimeout(executorConfig.getQueryTimeoutSeconds());
        jdbcTemplate.setMaxRows(maxRowsWithOverflowProbe(executorConfig.getResultLimit()));
        jdbcTemplate.setFetchSize(Math.min(500, executorConfig.getResultLimit()));
        init(jdbcTemplate);
        return jdbcTemplate;
    }

    protected void init(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                String.format("SET memory_limit = '%sGB';", executorConfig.getMemoryLimit()));
        jdbcTemplate.execute(String.format("SET temp_directory='%s';",
                escapeSqlLiteral(executorConfig.getDuckDbTemp())));
        jdbcTemplate.execute(String.format("SET threads TO %s;", executorConfig.getThreads()));
        jdbcTemplate.execute("SET enable_object_cache = true;");
    }

    public JdbcTemplate getDuckDbJdbcTemplate() {
        return duckDbJdbcTemplate;
    }

    public void setDuckDbJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.duckDbJdbcTemplate = jdbcTemplate;
    }

    public void execute(String sql) {
        duckDbJdbcTemplate.execute(sql);
    }

    public void query(String sql, SemanticQueryResp queryResultWithColumns) {
        duckDbJdbcTemplate.query(sql, rs -> {
            if (null == rs) {
                return queryResultWithColumns;
            }
            ResultSetMetaData metaData = rs.getMetaData();
            List<QueryColumn> queryColumns = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String key = metaData.getColumnLabel(i);
                queryColumns.add(new QueryColumn(key, metaData.getColumnTypeName(i)));
            }
            queryResultWithColumns.setColumns(queryColumns);
            List<Map<String, Object>> resultList = buildResult(rs, executorConfig.getResultLimit());
            queryResultWithColumns.setResultList(resultList);
            return queryResultWithColumns;
        });
    }

    public static List<Map<String, Object>> buildResult(ResultSet resultSet, int resultLimit)
            throws SQLException {
        if (resultLimit <= 0) {
            throw new IllegalArgumentException("Result limit must be greater than zero");
        }
        List<Map<String, Object>> list = new ArrayList<>();
        ResultSetMetaData rsMeta = resultSet.getMetaData();
        int columnCount = rsMeta.getColumnCount();
        while (resultSet.next()) {
            if (list.size() >= resultLimit) {
                throw new QueryRejectedException("Query result row limit exceeded: " + resultLimit);
            }
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String column = rsMeta.getColumnName(i);
                switch (rsMeta.getColumnType(i)) {
                    case java.sql.Types.BOOLEAN:
                        row.put(column, resultSet.getBoolean(i));
                        break;
                    case java.sql.Types.INTEGER:
                        row.put(column, resultSet.getInt(i));
                        break;
                    case java.sql.Types.BIGINT:
                        row.put(column, resultSet.getLong(i));
                        break;
                    case java.sql.Types.DOUBLE:
                        row.put(column, resultSet.getDouble(i));
                        break;
                    case java.sql.Types.VARCHAR:
                        row.put(column, resultSet.getString(i));
                        break;
                    case java.sql.Types.NUMERIC:
                        row.put(column, resultSet.getBigDecimal(i));
                        break;
                    case java.sql.Types.TINYINT:
                        row.put(column, (int) resultSet.getByte(i));
                        break;
                    case java.sql.Types.SMALLINT:
                        row.put(column, resultSet.getShort(i));
                        break;
                    case java.sql.Types.REAL:
                        row.put(column, resultSet.getFloat(i));
                        break;
                    case java.sql.Types.DATE:
                        row.put(column, resultSet.getDate(i));
                        break;
                    case java.sql.Types.TIME:
                        row.put(column, resultSet.getTime(i));
                        break;
                    case java.sql.Types.TIMESTAMP:
                        row.put(column, resultSet.getTimestamp(i));
                        break;
                    case java.sql.Types.JAVA_OBJECT:
                        row.put(column, resultSet.getObject(i));
                        break;
                    default:
                        throw new SQLException(
                                "Unsupported DuckDB result type: " + rsMeta.getColumnType(i));
                }
            }
            list.add(row);
        }
        return list;
    }

    private void validateConfig() {
        requirePositive(executorConfig.getDuckDbMaximumPoolSize(), "maximumPoolSize");
        requirePositive(executorConfig.getDuckDbMaxLifetime(), "maxLifetime");
        requirePositive(executorConfig.getMemoryLimit(), "memoryLimit");
        requirePositive(executorConfig.getThreads(), "threads");
        requirePositive(executorConfig.getQueryTimeoutSeconds(), "query-timeout-seconds");
        requirePositive(executorConfig.getResultLimit(), "result-limit");
        if (executorConfig.getDuckDbTemp() == null || executorConfig.getDuckDbTemp().isBlank()) {
            throw new IllegalArgumentException("s2.accelerator.duckDb.temp must not be blank");
        }
    }

    private static int maxRowsWithOverflowProbe(int resultLimit) {
        return resultLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : resultLimit + 1;
    }

    private static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void requirePositive(Integer value, String property) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("DuckDB " + property + " must be greater than zero");
        }
    }
}
