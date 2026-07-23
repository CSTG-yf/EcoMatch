package com.tencent.supersonic.headless.core.gateway;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates executable SQL before it reaches a physical data source. */
public class SqlSafetyPolicy {

    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of("benchmark", "load_file",
            "pg_read_file", "pg_sleep", "sleep", "sys_eval", "sys_exec");
    private static final Pattern SELECT_ALL = Pattern.compile(
            "(?is)\\bselect\\s+(?:[a-zA-Z_][\\w$]*\\.)?\\*\\s+from\\b");
    private static final Pattern BOUNDED_QUERY =
            Pattern.compile("(?is)\\b(where|limit|fetch\\s+first|fetch\\s+next)\\b");
    private static final Pattern LOCK_OR_FILE_WRITE = Pattern.compile(
            "(?is)\\b(for\\s+update|lock\\s+in\\s+share\\s+mode|into\\s+(out|dump)file)\\b");

    private final int maxSqlLength;

    public SqlSafetyPolicy(int maxSqlLength) {
        this.maxSqlLength = maxSqlLength;
    }

    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlPolicyViolationException("SQL must not be empty");
        }
        if (sql.length() > maxSqlLength) {
            throw new SqlPolicyViolationException(
                    "SQL length exceeds the configured maximum of " + maxSqlLength);
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception e) {
            throw new SqlPolicyViolationException("SQL syntax validation failed", e);
        }
        if (statements.getStatements().size() != 1) {
            throw new SqlPolicyViolationException("Only one SQL statement is allowed");
        }
        Statement statement = statements.getStatements().get(0);
        if (!(statement instanceof Select)) {
            throw new SqlPolicyViolationException("Only read-only SELECT statements are allowed");
        }

        String normalized = sql.toLowerCase(Locale.ROOT);
        if (LOCK_OR_FILE_WRITE.matcher(normalized).find()) {
            throw new SqlPolicyViolationException("Locking and file-writing clauses are forbidden");
        }
        for (String function : DANGEROUS_FUNCTIONS) {
            if (Pattern.compile("(?is)\\b" + Pattern.quote(function) + "\\s*\\(")
                    .matcher(normalized).find()) {
                throw new SqlPolicyViolationException(
                        "Dangerous SQL function is forbidden: " + function);
            }
        }
        if (SELECT_ALL.matcher(normalized).find()
                && !BOUNDED_QUERY.matcher(normalized).find()) {
            throw new SqlPolicyViolationException(
                    "Unbounded SELECT * queries are forbidden");
        }
    }
}
