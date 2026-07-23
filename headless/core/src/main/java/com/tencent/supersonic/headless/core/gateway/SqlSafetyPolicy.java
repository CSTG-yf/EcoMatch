package com.tencent.supersonic.headless.core.gateway;

import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates executable SQL before it reaches a physical data source. */
public class SqlSafetyPolicy {

    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of("benchmark", "load_file",
            "pg_read_file", "pg_sleep", "sleep", "sys_eval", "sys_exec");
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

        String normalized = statement.toString().toLowerCase(Locale.ROOT);
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
        validateSelectAllQueries((Select) statement);
    }

    private void validateSelectAllQueries(Select statement) {
        Set<String> cteNames = statement.getWithItemsList() == null ? Collections.emptySet()
                : statement.getWithItemsList().stream()
                        .filter(withItem -> withItem.getAlias() != null)
                        .map(withItem -> withItem.getAlias().getName().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        for (Select select : SqlSelectHelper.getAllSelect(statement)) {
            if (!(select instanceof PlainSelect)) {
                continue;
            }
            PlainSelect plainSelect = (PlainSelect) select;
            boolean selectsAll =
                    plainSelect.getSelectItems().stream().map(item -> item.getExpression())
                            .anyMatch(expression -> expression instanceof AllColumns
                                    || expression instanceof AllTableColumns);
            boolean bounded = plainSelect.getWhere() != null || plainSelect.getLimit() != null
                    || plainSelect.getFetch() != null;
            if (selectsAll && !bounded && !readsOnlyDerivedSources(plainSelect, cteNames)) {
                throw new SqlPolicyViolationException(
                        "Every SELECT * query branch must include WHERE, LIMIT, or FETCH");
            }
        }
    }

    private boolean readsOnlyDerivedSources(PlainSelect select, Set<String> cteNames) {
        if (!isDerivedSource(select.getFromItem(), cteNames)) {
            return false;
        }
        if (select.getJoins() == null) {
            return true;
        }
        for (Join join : select.getJoins()) {
            if (!isDerivedSource(join.getRightItem(), cteNames)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDerivedSource(FromItem source, Set<String> cteNames) {
        if (source instanceof ParenthesedSelect) {
            return true;
        }
        if (source instanceof Table) {
            String tableName = ((Table) source).getName();
            return tableName != null && cteNames.contains(tableName.toLowerCase(Locale.ROOT));
        }
        return false;
    }
}
