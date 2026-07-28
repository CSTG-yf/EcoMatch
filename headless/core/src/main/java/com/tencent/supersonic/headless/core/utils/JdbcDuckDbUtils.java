package com.tencent.supersonic.headless.core.utils;

import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.pojo.DuckDbSource;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** tools functions to duckDb query */
public class JdbcDuckDbUtils {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public static void attachMysql(DuckDbSource duckDbSource, String host, Integer port,
            String user, String password, String database) throws Exception {
        requireConnectionValue(host, "host");
        requireConnectionValue(user, "user");
        requireConnectionValue(password, "password");
        requireConnectionValue(database, "database");
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("MySQL port must be between 1 and 65535");
        }
        duckDbSource.execute("INSTALL mysql");
        duckDbSource.execute("load mysql");
        String attachSql =
                "ATTACH 'host=%s port=%s user=%s password=%s database=%s' AS mysqldb (TYPE mysql);";
        duckDbSource.execute(String.format(attachSql, escapeLiteral(host), port,
                escapeLiteral(user), escapeLiteral(password), escapeLiteral(database)));
        duckDbSource.execute("SET mysql_experimental_filter_pushdown = true;");
    }

    public static List<String> getParquetColumns(DuckDbSource duckDbSource, String parquetPath)
            throws Exception {
        SemanticQueryResp queryResultWithColumns = new SemanticQueryResp();
        duckDbSource.query(String.format("SELECT distinct name FROM parquet_schema('%s')",
                escapeRequiredLiteral(parquetPath, "parquetPath")), queryResultWithColumns);
        if (!queryResultWithColumns.getResultList().isEmpty()) {
            return queryResultWithColumns.getResultList().stream()
                    .filter(l -> l.containsKey("name") && Objects.nonNull(l.get("name")))
                    .map(l -> (String) l.get("name")).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    public static List<String> getParquetPartition(DuckDbSource duckDbSource, String parquetPath,
            String partitionName) throws Exception {
        SemanticQueryResp queryResultWithColumns = new SemanticQueryResp();
        duckDbSource.query(String.format("SELECT distinct %s as partition FROM read_parquet('%s')",
                requireIdentifier(partitionName, "partitionName"),
                escapeRequiredLiteral(parquetPath, "parquetPath")), queryResultWithColumns);
        if (!queryResultWithColumns.getResultList().isEmpty()) {
            return queryResultWithColumns.getResultList().stream()
                    .filter(l -> l.containsKey("partition") && Objects.nonNull(l.get("partition")))
                    .map(l -> (String) l.get("partition")).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    public static boolean createDatabase(DuckDbSource duckDbSource, String db) throws Exception {
        duckDbSource.execute("CREATE SCHEMA IF NOT EXISTS " + requireIdentifier(db, "database"));
        return true;
    }

    public static boolean createView(DuckDbSource duckDbSource, String view, String sql)
            throws Exception {
        requireSingleSelect(sql);
        duckDbSource.execute(String.format("CREATE OR REPLACE VIEW %s AS %s;",
                requireIdentifier(view, "view"), sql));
        return true;
    }

    static String requireIdentifier(String value, String name) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains an unsafe identifier");
        }
        return value;
    }

    static String escapeRequiredLiteral(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return escapeLiteral(value);
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void requireConnectionValue(String value, String name) {
        if (value == null || value.isBlank() || containsUnsafeConnectionCharacter(value)) {
            throw new IllegalArgumentException("MySQL " + name + " is invalid");
        }
    }

    private static void requireSingleSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("View query must be a single SELECT statement");
        }
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select)) {
                throw new IllegalArgumentException("View query must be a single SELECT statement");
            }
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("View query must be a valid SELECT statement");
        }
    }

    private static boolean containsUnsafeConnectionCharacter(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.isWhitespace(character));
    }
}
