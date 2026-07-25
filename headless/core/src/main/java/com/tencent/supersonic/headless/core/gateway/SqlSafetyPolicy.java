package com.tencent.supersonic.headless.core.gateway;

import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
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
import net.sf.jsqlparser.statement.select.TableFunction;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Validates executable SQL before it reaches a physical data source. */
public class SqlSafetyPolicy {

    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of("benchmark", "dblink_exec",
            "get_lock", "load_file", "lo_export", "lo_import", "nextval", "pg_advisory_lock",
            "pg_advisory_unlock", "pg_advisory_unlock_all", "pg_advisory_xact_lock", "pg_read_file",
            "pg_read_binary_file", "pg_ls_dir", "pg_sleep", "pg_stat_file", "pg_try_advisory_lock",
            "pg_try_advisory_xact_lock", "pg_write_file", "release_lock", "set_config", "setval",
            "sleep", "sys_eval", "sys_exec");
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
            validateReadOnlySelectFeatures(plainSelect);
            validateDangerousFunctions(plainSelect);
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

    private void validateDangerousFunctions(PlainSelect select) {
        ExpressionVisitorAdapter visitor = new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                String functionName = normalizeFunctionName(function);
                if (DANGEROUS_FUNCTIONS.contains(functionName)) {
                    throw new SqlPolicyViolationException(
                            "Dangerous SQL function is forbidden: " + functionName);
                }
                super.visit(function);
            }
        };
        select.getSelectItems().forEach(item -> visit(item.getExpression(), visitor));
        visit(select.getWhere(), visitor);
        visit(select.getHaving(), visitor);
        visit(select.getQualify(), visitor);
        if (select.getJoins() != null) {
            select.getJoins().stream()
                    .flatMap(join -> Stream.ofNullable(join.getOnExpressions())
                            .flatMap(java.util.Collection::stream))
                    .forEach(expression -> visit(expression, visitor));
        }
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressions() != null) {
            select.getGroupBy().getGroupByExpressions()
                    .forEach(expression -> visitIfExpression(expression, visitor));
        }
        if (select.getOrderByElements() != null) {
            select.getOrderByElements().forEach(orderBy -> visit(orderBy.getExpression(), visitor));
        }
        if (select.getLimit() != null) {
            visit(select.getLimit().getOffset(), visitor);
            visit(select.getLimit().getRowCount(), visitor);
            if (select.getLimit().getByExpressions() != null) {
                select.getLimit().getByExpressions()
                        .forEach(expression -> visitIfExpression(expression, visitor));
            }
        }
        if (select.getLimitBy() != null) {
            visit(select.getLimitBy().getOffset(), visitor);
            visit(select.getLimitBy().getRowCount(), visitor);
        }
        if (select.getOffset() != null) {
            visit(select.getOffset().getOffset(), visitor);
        }
        if (select.getFetch() != null) {
            visit(select.getFetch().getExpression(), visitor);
        }
        visitTableFunction(select.getFromItem(), visitor);
        if (select.getJoins() != null) {
            select.getJoins().forEach(join -> visitTableFunction(join.getRightItem(), visitor));
        }
    }

    private void visit(Expression expression, ExpressionVisitorAdapter visitor) {
        if (expression != null) {
            expression.accept(visitor);
        }
    }

    private void visitIfExpression(Object value, ExpressionVisitorAdapter visitor) {
        if (value instanceof Expression expression) {
            visit(expression, visitor);
        }
    }

    private void visitTableFunction(FromItem fromItem, ExpressionVisitorAdapter visitor) {
        if (fromItem instanceof TableFunction tableFunction) {
            tableFunction.getFunction().accept(visitor);
        }
    }

    private String normalizeFunctionName(Function function) {
        String name = function.getMultipartName() == null || function.getMultipartName().isEmpty()
                ? function.getName()
                : function.getMultipartName().get(function.getMultipartName().size() - 1);
        String normalized = name == null ? ""
                : name.replace("\"", "").replace("`", "").replace("[", "").replace("]", "")
                        .toLowerCase(Locale.ROOT);
        int qualifier = normalized.lastIndexOf('.');
        return qualifier < 0 ? normalized : normalized.substring(qualifier + 1);
    }

    private void validateReadOnlySelectFeatures(PlainSelect select) {
        if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty())
                || select.getIntoTempTable() != null) {
            throw new SqlPolicyViolationException("SELECT INTO is forbidden");
        }
        if (select.getForMode() != null || select.getForClause() != null) {
            throw new SqlPolicyViolationException("Row-locking SELECT clauses are forbidden");
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
