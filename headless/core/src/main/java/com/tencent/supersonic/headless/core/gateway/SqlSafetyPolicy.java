package com.tencent.supersonic.headless.core.gateway;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.WindowElement;
import net.sf.jsqlparser.expression.WindowOffset;
import net.sf.jsqlparser.expression.WindowRange;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.Values;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Validates executable SQL before it reaches a physical data source. */
public class SqlSafetyPolicy {

    private static final int DEFAULT_MAX_SELECT_DEPTH = 16;
    private static final Set<String> DEFAULT_DANGEROUS_FUNCTIONS = Set.of("benchmark", "csv_scan",
            "csvread", "csvwrite", "dblink", "dblink_connect", "dblink_exec", "file_read",
            "file_write", "get_lock", "glob", "json_scan", "load_file", "lo_export", "lo_get",
            "lo_import", "nextval", "opendatasource", "openquery", "openrowset", "parquet_scan",
            "pg_advisory_lock", "pg_advisory_unlock", "pg_advisory_unlock_all",
            "pg_advisory_xact_lock", "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_sleep",
            "pg_stat_file", "pg_try_advisory_lock", "pg_try_advisory_xact_lock", "pg_write_file",
            "read_blob", "read_csv", "read_csv_auto", "read_json", "read_json_auto", "read_ndjson",
            "read_ndjson_auto", "read_parquet", "read_text", "read_xlsx", "read_xml", "readfile",
            "release_lock", "set_config", "setval", "sleep", "sys_eval", "sys_exec", "writefile");
    private static final Pattern LOCK_OR_FILE_WRITE = Pattern.compile(
            "(?is)\\b(for\\s+update|lock\\s+in\\s+share\\s+mode|into\\s+(out|dump)file)\\b");
    private static final Pattern FUNCTION_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*");

    private final int maxSqlLength;
    private final int maxSelectDepth;
    private final Set<String> dangerousFunctions;

    public SqlSafetyPolicy(int maxSqlLength) {
        this(maxSqlLength, "", DEFAULT_MAX_SELECT_DEPTH);
    }

    public SqlSafetyPolicy(int maxSqlLength, String additionalDangerousFunctions) {
        this(maxSqlLength, additionalDangerousFunctions, DEFAULT_MAX_SELECT_DEPTH);
    }

    public SqlSafetyPolicy(int maxSqlLength, String additionalDangerousFunctions,
            int maxSelectDepth) {
        if (maxSelectDepth <= 0) {
            throw new IllegalArgumentException(
                    "s2.query-gateway.max-select-depth must be greater than zero");
        }
        this.maxSqlLength = maxSqlLength;
        this.maxSelectDepth = maxSelectDepth;
        Set<String> configured = new LinkedHashSet<>(DEFAULT_DANGEROUS_FUNCTIONS);
        String additions = additionalDangerousFunctions == null ? "" : additionalDangerousFunctions;
        for (String rawFunction : additions.split(",")) {
            String function = rawFunction.trim();
            if (function.isEmpty()) {
                continue;
            }
            if (!FUNCTION_IDENTIFIER.matcher(function).matches()) {
                throw new IllegalArgumentException(
                        "Invalid denied SQL function identifier: " + function);
            }
            configured.add(normalizeFunctionIdentifier(function));
        }
        this.dangerousFunctions = Collections.unmodifiableSet(configured);
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
        for (String function : dangerousFunctions) {
            if (Pattern.compile("(?is)\\b" + Pattern.quote(function) + "\\s*\\(")
                    .matcher(normalized).find()) {
                throw new SqlPolicyViolationException(
                        "Dangerous SQL function is forbidden: " + function);
            }
        }
        validateSelectTree((Select) statement);
    }

    private void validateSelectTree(Select statement) {
        Set<Select> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        validateSelect(statement, Collections.emptySet(), visited, 0);
    }

    private void validateSelect(Select select, Set<String> inheritedCteNames, Set<Select> visited,
            int depth) {
        if (select == null || !visited.add(select)) {
            return;
        }
        if (depth > maxSelectDepth) {
            throw new SqlPolicyViolationException(
                    "SQL SELECT nesting exceeds the configured maximum of " + maxSelectDepth);
        }
        Set<String> cteNames = new LinkedHashSet<>(inheritedCteNames);
        if (select.getWithItemsList() != null) {
            select.getWithItemsList().stream().filter(withItem -> withItem.getAlias() != null)
                    .map(withItem -> withItem.getAlias().getName().toLowerCase(Locale.ROOT))
                    .forEach(cteNames::add);
            select.getWithItemsList().forEach(
                    withItem -> validateSelect(withItem.getSelect(), cteNames, visited, depth + 1));
        }

        ExpressionVisitorAdapter visitor = dangerousFunctionVisitor(cteNames, visited, depth);
        if (select.getForClause() != null) {
            throw new SqlPolicyViolationException("Row-locking SELECT clauses are forbidden");
        }
        if (select instanceof PlainSelect plainSelect) {
            validateReadOnlySelectFeatures(plainSelect);
            validateDangerousFunctions(plainSelect, visitor);
            validateSelectAllBranch(plainSelect, cteNames);
            validateNestedSelect(plainSelect.getFromItem(), cteNames, visited, depth);
            if (plainSelect.getJoins() != null) {
                plainSelect.getJoins().forEach(join -> validateNestedSelect(join.getRightItem(),
                        cteNames, visited, depth));
            }
        } else if (select instanceof Values values) {
            visit(values.getExpressions(), visitor);
        } else if (select instanceof SetOperationList setOperationList) {
            if (setOperationList.getSelects() != null) {
                setOperationList.getSelects()
                        .forEach(child -> validateSelect(child, cteNames, visited, depth + 1));
            }
        } else if (select instanceof ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect.getSelect(), cteNames, visited, depth);
        } else if (select instanceof TableStatement) {
            throw new SqlPolicyViolationException(
                    "TABLE statements are forbidden; use a bounded SELECT");
        } else {
            throw new SqlPolicyViolationException(
                    "Unsupported SELECT form: " + select.getClass().getSimpleName());
        }
        validateSelectModifiers(select, visitor);
    }

    private ExpressionVisitorAdapter dangerousFunctionVisitor(Set<String> cteNames,
            Set<Select> visited, int depth) {
        return new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                String functionName = normalizeFunctionName(function);
                if (dangerousFunctions.contains(functionName)) {
                    throw new SqlPolicyViolationException(
                            "Dangerous SQL function is forbidden: " + functionName);
                }
                super.visit(function);
            }

            @Override
            public void visit(NextValExpression nextValExpression) {
                throw new SqlPolicyViolationException(
                        "Sequence state-changing expressions are forbidden");
            }

            @Override
            public void visit(Column column) {
                String columnName = normalizeQuotedIdentifier(column.getColumnName());
                if ("nextval".equals(columnName) && column.getTable() != null
                        && column.getTable().getName() != null) {
                    throw new SqlPolicyViolationException(
                            "Sequence state-changing expressions are forbidden");
                }
                super.visit(column);
            }

            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                validateSelect(parenthesedSelect, cteNames, visited, depth + 1);
            }

            @Override
            public void visit(Select nestedSelect) {
                validateSelect(nestedSelect, cteNames, visited, depth + 1);
            }
        };
    }

    private void validateDangerousFunctions(PlainSelect select, ExpressionVisitorAdapter visitor) {
        select.getSelectItems().forEach(item -> visit(item.getExpression(), visitor));
        visit(select.getWhere(), visitor);
        visit(select.getHaving(), visitor);
        visit(select.getQualify(), visitor);
        visitJoinExpressionsAndSources(select.getJoins(), visitor);
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressions() != null) {
            select.getGroupBy().getGroupByExpressions()
                    .forEach(expression -> visitIfExpression(expression, visitor));
        }
        if (select.getDistinct() != null && select.getDistinct().getOnSelectItems() != null) {
            select.getDistinct().getOnSelectItems()
                    .forEach(item -> visit(item.getExpression(), visitor));
        }
        if (select.getTop() != null) {
            visit(select.getTop().getExpression(), visitor);
        }
        if (select.getOracleHierarchical() != null) {
            visit(select.getOracleHierarchical().getStartExpression(), visitor);
            visit(select.getOracleHierarchical().getConnectExpression(), visitor);
        }
        if (select.getWindowDefinitions() != null) {
            select.getWindowDefinitions().forEach(window -> visitWindowDefinition(window, visitor));
        }
        if (select.getLateralViews() != null) {
            select.getLateralViews().stream().map(lateralView -> lateralView.getGeneratorFunction())
                    .forEach(function -> visit(function, visitor));
        }
        visitFromItemExpressions(select.getFromItem(), visitor);
    }

    private void validateSelectModifiers(Select select, ExpressionVisitorAdapter visitor) {
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
    }

    private void validateSelectAllBranch(PlainSelect select, Set<String> cteNames) {
        boolean selectsAll = select.getSelectItems().stream().map(item -> item.getExpression())
                .anyMatch(expression -> expression instanceof AllColumns
                        || expression instanceof AllTableColumns);
        boolean bounded =
                select.getWhere() != null || select.getLimit() != null || select.getFetch() != null;
        if (selectsAll && !bounded && !readsOnlyDerivedSources(select, cteNames)) {
            throw new SqlPolicyViolationException(
                    "Every SELECT * query branch must include WHERE, LIMIT, or FETCH");
        }
    }

    private void validateNestedSelect(FromItem source, Set<String> cteNames, Set<Select> visited,
            int depth) {
        if (source instanceof ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect, cteNames, visited, depth + 1);
        } else if (source instanceof ParenthesedFromItem parenthesedFromItem) {
            validateNestedSelect(parenthesedFromItem.getFromItem(), cteNames, visited, depth);
            if (parenthesedFromItem.getJoins() != null) {
                parenthesedFromItem.getJoins()
                        .forEach(join -> validateNestedSelect(join.getRightItem(), cteNames,
                                visited, depth));
            }
        }
    }

    private void visitWindowDefinition(WindowDefinition window, ExpressionVisitorAdapter visitor) {
        visit(window.getPartitionExpressionList(), visitor);
        if (window.getOrderByElements() != null) {
            window.getOrderByElements().forEach(orderBy -> visit(orderBy.getExpression(), visitor));
        }
        WindowElement element = window.getWindowElement();
        if (element == null) {
            return;
        }
        visitWindowOffset(element.getOffset(), visitor);
        WindowRange range = element.getRange();
        if (range != null) {
            visitWindowOffset(range.getStart(), visitor);
            visitWindowOffset(range.getEnd(), visitor);
        }
    }

    private void visitWindowOffset(WindowOffset offset, ExpressionVisitorAdapter visitor) {
        if (offset != null) {
            visit(offset.getExpression(), visitor);
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

    private void visitFromItemExpressions(FromItem fromItem, ExpressionVisitorAdapter visitor) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof TableFunction tableFunction) {
            tableFunction.getFunction().accept(visitor);
        } else if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            visitFromItemExpressions(parenthesedFromItem.getFromItem(), visitor);
            visitJoinExpressionsAndSources(parenthesedFromItem.getJoins(), visitor);
        }
        if (fromItem.getPivot() != null) {
            fromItem.getPivot().accept(visitor);
        }
        if (fromItem.getUnPivot() != null) {
            fromItem.getUnPivot().accept(visitor);
        }
    }

    private void visitJoinExpressionsAndSources(List<Join> joins,
            ExpressionVisitorAdapter visitor) {
        if (joins == null) {
            return;
        }
        joins.stream()
                .flatMap(join -> Stream.ofNullable(join.getOnExpressions())
                        .flatMap(java.util.Collection::stream))
                .forEach(expression -> visit(expression, visitor));
        joins.forEach(join -> visitFromItemExpressions(join.getRightItem(), visitor));
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

    private String normalizeFunctionIdentifier(String function) {
        String normalized = function.toLowerCase(Locale.ROOT);
        int qualifier = normalized.lastIndexOf('.');
        return qualifier < 0 ? normalized : normalized.substring(qualifier + 1);
    }

    private String normalizeQuotedIdentifier(String identifier) {
        return identifier == null ? ""
                : identifier.replace("\"", "").replace("`", "").replace("[", "").replace("]", "")
                        .toLowerCase(Locale.ROOT);
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
