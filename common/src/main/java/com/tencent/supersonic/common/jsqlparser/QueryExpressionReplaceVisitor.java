package com.tencent.supersonic.common.jsqlparser;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces semantic fields (columns) with physical expressions by walking the full expression AST
 * with the standard JSqlParser {@link ExpressionDeParser}. Instead of a static whitelist of
 * expression types (Function/Column/BinaryExpression/Parenthesis/InExpression), the deparser
 * recursively visits every node type supported by JSqlParser 4.9, so fields nested in BETWEEN,
 * CASE WHEN, NOT IN, function arguments, analytic/window expressions, etc. are all rewritten.
 */
public class QueryExpressionReplaceVisitor extends ExpressionVisitorAdapter {

    private final Map<String, String> fieldExprMap;
    private final Set<String> aliasFields;
    private final Set<String> cteNames;
    private String lastColumnName;

    public QueryExpressionReplaceVisitor(Map<String, String> fieldExprMap) {
        this(fieldExprMap, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * @param aliasFields select output aliases of the query being rewritten; columns whose name is
     *        in this set are treated as references to SELECT output columns and are left untouched
     *        when rewriting WHERE/HAVING/GROUP BY/ORDER BY/JOIN ON expressions
     * @param cteNames names of CTEs (WITH items) and derived tables visible to the query; columns
     *        qualified with one of these names are output columns and are never rewritten
     */
    public QueryExpressionReplaceVisitor(Map<String, String> fieldExprMap,
            Set<String> aliasFields, Set<String> cteNames) {
        this.fieldExprMap = fieldExprMap;
        this.aliasFields = aliasFields;
        this.cteNames = cteNames;
    }

    @Override
    public void visit(SelectItem selectExpressionItem) {
        Expression expression = selectExpressionItem.getExpression();
        String toReplace = "";
        if (expression instanceof Function) {
            Function leftFunc = (Function) expression;
            if (Objects.nonNull(leftFunc.getParameters())
                    && !leftFunc.getParameters().isEmpty()
                    && leftFunc.getParameters().get(0) instanceof Column) {
                Column column = (Column) leftFunc.getParameters().get(0);
                if (!isCteQualified(column)) {
                    lastColumnName = column.getColumnName();
                    toReplace = getReplaceExpr(leftFunc, fieldExprMap);
                }
            }
        } else if (expression instanceof Column) {
            Column column = (Column) expression;
            if (!isCteQualified(column)) {
                lastColumnName = column.getColumnName();
                toReplace = getReplaceExpr(column, fieldExprMap);
            }
        }

        Expression replaced = replace(expression, fieldExprMap, Collections.emptySet(), cteNames);
        if (Objects.nonNull(replaced)) {
            selectExpressionItem.setExpression(replaced);
        }

        if (!toReplace.isEmpty()) {
            Expression toReplaceExpr = getExpression(toReplace);
            if (Objects.nonNull(toReplaceExpr)) {
                selectExpressionItem.setExpression(toReplaceExpr);
                if (Objects.isNull(selectExpressionItem.getAlias())) {
                    selectExpressionItem.setAlias(new Alias(lastColumnName, true));
                }
            }
        }
    }

    private boolean isCteQualified(Column column) {
        return Objects.nonNull(column.getTable())
                && cteNames.contains(column.getTable().getName());
    }

    /**
     * Rewrites every semantic field inside the given expression by walking the whole expression
     * tree with {@link ExpressionDeParser} and re-parsing the produced SQL fragment.
     */
    public static Expression replace(Expression expression, Map<String, String> fieldExprMap) {
        return replace(expression, fieldExprMap, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Same as {@link #replace(Expression, Map)} but skips columns that reference SELECT output
     * aliases ({@code aliasFields}) or output columns of CTEs/derived tables ({@code cteNames}).
     */
    public static Expression replace(Expression expression, Map<String, String> fieldExprMap,
            Set<String> aliasFields, Set<String> cteNames) {
        if (Objects.isNull(expression)) {
            return null;
        }
        FieldExprReplaceDeParser deParser =
                new FieldExprReplaceDeParser(fieldExprMap, aliasFields, cteNames);
        expression.accept(deParser);
        String rewritten = deParser.getBuffer().toString();
        if (rewritten.equals(expression.toString())) {
            return expression;
        }
        Expression parsed = getExpression(rewritten);
        return Objects.nonNull(parsed) ? parsed : expression;
    }

    public static Expression getExpression(String expr) {
        if (expr.isEmpty()) {
            return null;
        }
        try {
            return CCJSqlParserUtil.parseExpression(expr);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getReplaceExpr(Column column, Map<String, String> fieldExprMap) {
        return fieldExprMap.containsKey(column.getColumnName())
                ? fieldExprMap.get(column.getColumnName())
                : "";
    }

    public static String getReplaceExpr(Function function, Map<String, String> fieldExprMap) {
        Column column = (Column) function.getParameters().getExpressions().get(0);
        String expr = getReplaceExpr(column, fieldExprMap);
        // if metric expr itself has agg function then replace original function in the SQL
        if (StringUtils.isBlank(expr)) {
            return expr;
        } else if (!SqlSelectFunctionHelper.getAggregateFunctions(expr).isEmpty()) {
            return expr;
        } else {
            String col = getReplaceExpr(column, fieldExprMap);
            column.setColumnName(col);
            return function.toString();
        }
    }

    /**
     * Complete deparser-based expression walker. {@code visit(Column)} emits the mapped physical
     * expression (keeping the table qualifier when the mapping is a plain column name) and
     * {@code visit(Function)} keeps the existing mapping rule: a mapped first argument replaces the
     * whole function when the mapping itself contains an aggregate, otherwise the first argument is
     * renamed in place. Sub-queries are emitted verbatim - their inner PlainSelect bodies are
     * rewritten separately by SqlReplaceHelper.
     */
    private static class FieldExprReplaceDeParser extends ExpressionDeParser {

        private final Map<String, String> fieldExprMap;
        private final Set<String> aliasFields;
        private final Set<String> cteNames;

        FieldExprReplaceDeParser(Map<String, String> fieldExprMap, Set<String> aliasFields,
                Set<String> cteNames) {
            this.fieldExprMap = fieldExprMap;
            this.aliasFields = aliasFields;
            this.cteNames = cteNames;
        }

        @Override
        public void visit(Column column) {
            if (isCteQualified(column)) {
                super.visit(column);
                return;
            }
            String expr = getReplaceExpr(column, fieldExprMap);
            if (StringUtils.isNotBlank(expr) && !aliasFields.contains(column.getColumnName())) {
                if (Objects.nonNull(column.getTable()) && isPlainColumn(expr)) {
                    buffer.append(getTableName(column.getTable())).append('.');
                }
                buffer.append(expr);
            } else {
                super.visit(column);
            }
        }

        @Override
        public void visit(Function function) {
            if (Objects.nonNull(function.getParameters())
                    && !function.getParameters().isEmpty()
                    && function.getParameters().get(0) instanceof Column) {
                Column column = (Column) function.getParameters().get(0);
                if (!isCteQualified(column) && !aliasFields.contains(column.getColumnName())) {
                    String expr = getReplaceExpr(function, fieldExprMap);
                    if (StringUtils.isNotBlank(expr)) {
                        buffer.append(expr);
                        return;
                    }
                }
            }
            super.visit(function);
        }

        @Override
        public void visit(Select selectBody) {
            // sub-query bodies are rewritten separately by SqlReplaceHelper, emit them verbatim
            buffer.append(selectBody.toString());
        }

        private boolean isCteQualified(Column column) {
            return Objects.nonNull(column.getTable())
                    && cteNames.contains(column.getTable().getName());
        }

        private boolean isPlainColumn(String expr) {
            Expression parsed = getExpression(expr);
            return parsed instanceof Column && Objects.isNull(((Column) parsed).getTable());
        }

        private String getTableName(Table table) {
            return Objects.nonNull(table.getAlias()) ? table.getAlias().getName()
                    : table.getFullyQualifiedName();
        }
    }
}
