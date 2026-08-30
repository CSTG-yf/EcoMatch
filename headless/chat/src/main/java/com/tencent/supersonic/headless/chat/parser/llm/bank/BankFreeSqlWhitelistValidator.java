package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
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
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AST-level whitelist for the controlled bank free-SQL fallback channel (design v1 §2③b).
 *
 * <p>The validator is deterministic and catalogue-driven: exactly one SELECT/WITH statement,
 * tables restricted to the semantic bank dataset (consistent with
 * {@link BankFreeSqlPromptComposer#isBankSchema}), columns restricted to registry dimensions and
 * metrics (including the Chinese display names), functions restricted to a fixed read-only
 * whitelist (window functions with OVER allowed), and every computed output column aliased with
 * a canonical evaluation-bound name. Any violation produces a Chinese message naming the
 * offending table/column/function/alias and the legal set, so the fallback repair round can fix
 * the statement without exposing any dataset asset.
 */
public final class BankFreeSqlWhitelistValidator {

    /**
     * Canonical output-column contract bound by the offline evaluation
     * (fact_contract_v3 {@code _RESULT_COLUMN_ALIASES} + {@code _FACT_IDENTITY_COLUMNS}).
     * Every computed SELECT column must be aliased with one of these names.
     */
    public static final Set<String> CANONICAL_RESULT_COLUMNS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of("org_code", "org_name", "metric_code", "data_date",
                    "bank_data_date", "comparison_type", "current_value", "baseline_value",
                    "value_difference", "absolute_change", "metric_value", "aggregate_value",
                    "daily_average", "rank_position", "numerator_value", "denominator_value",
                    "ratio_percent", "absolute_gap", "gap_value", "provincial_average",
                    "days_above_province_average", "observation_count", "above_ratio_percent")));

    /** Read-only aggregate / scalar / ranking functions the fallback SQL may call. */
    public static final Set<String> ALLOWED_FUNCTIONS = Collections.unmodifiableSet(new LinkedHashSet<>(
            List.of("sum", "avg", "max", "min", "count", "row_number", "rank", "dense_rank",
                    "lag", "lead", "coalesce", "nullif", "round", "abs")));

    private BankFreeSqlWhitelistValidator() {}

    /** Whitelist inputs derived from the same semantic schema the prompt renders. */
    public record Catalog(Set<String> tables, Set<String> columns) {

        public Catalog {
            tables = Collections.unmodifiableSet(new LinkedHashSet<>(tables));
            columns = Collections.unmodifiableSet(new LinkedHashSet<>(columns));
        }
    }

    /**
     * Builds the catalogue from the live semantic schema. Table set = the semantic dataset name;
     * column set = registry dimensions (bank_organization/机构, bank_data_date/数据日期) plus every
     * schema metric and dimension display/business name.
     */
    public static Catalog catalogFromSchema(LLMReq.LLMSchema schema) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        if (schema != null) {
            if (schema.getDataSetName() != null && !schema.getDataSetName().isBlank()) {
                tables.add(schema.getDataSetName());
            }
            for (SchemaElement metric : safeList(schema.getMetrics())) {
                addIdentifier(columns, metric == null ? null : metric.getName());
                addIdentifier(columns, metric == null ? null : metric.getBizName());
            }
            for (SchemaElement dimension : safeList(schema.getDimensions())) {
                addIdentifier(columns, dimension == null ? null : dimension.getName());
                addIdentifier(columns, dimension == null ? null : dimension.getBizName());
            }
        }
        // Registry identities stay authoritative even if the schema omits a display name.
        BankSemanticRegistry.dimensionAliases().forEach(columns::add);
        BankSemanticRegistry.dimensions().forEach(columns::add);
        BankSemanticRegistry.metrics().values().forEach(metric -> {
            columns.add(metric.code());
            columns.add(metric.name());
            metric.aliases().forEach(columns::add);
        });
        return new Catalog(
                tables.stream().filter(Objects::nonNull).map(BankFreeSqlWhitelistValidator::key)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                columns.stream().filter(Objects::nonNull).map(BankFreeSqlWhitelistValidator::key)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Validates the statement against the AST whitelist. Returns an empty list when the SQL may
     * proceed to the execution gateway; otherwise each entry is a self-contained Chinese
     * violation description naming the offending element and the legal set.
     */
    public static List<String> validate(String sql, Catalog catalog) {
        List<String> violations = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return List.of("SQL 为空：必须输出一条完整的 SELECT/WITH 查询。");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception e) {
            return List.of("SQL 解析失败（" + e.getClass().getSimpleName()
                    + "）：必须是一条语法正确的 SELECT/WITH 语句，不得包含分号分隔的多条语句或注释。");
        }
        if (statements.getStatements().size() != 1) {
            return List.of("只允许一条 SQL 语句：检测到 " + statements.getStatements().size()
                    + " 条语句；请删除多余语句并去掉结尾分号。");
        }
        Statement statement = statements.getStatements().get(0);
        if (!(statement instanceof Select select)) {
            return List.of("只允许只读的 SELECT/WITH 查询，检测到其他语句类型："
                    + statement.getClass().getSimpleName() + "。");
        }
        if (select instanceof SetOperationList) {
            return List.of("不支持 UNION/集合操作：只能输出单条 SELECT 或 WITH...SELECT 主查询。");
        }
        Walker walker = new Walker(catalog);
        Set<Select> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        walker.validateMainSelect(select, visited);
        return List.copyOf(walker.violations);
    }

    /**
     * Dual-output consistency (design v1 §2⑤ first half): the model-declared {@code columns}
     * must exactly match the SQL AST output names (aliases or bare catalog columns), ignoring
     * order but not cardinality. A mismatch fails closed — the caller must not fall back or
     * silently repair.
     */
    public static List<String> validateDeclaredColumns(String sql, Collection<String> declared,
            Catalog catalog) {
        List<String> violations = new ArrayList<>();
        List<String> declaredNames = declared == null ? List.of()
                : declared.stream().filter(Objects::nonNull).filter(name -> !name.isBlank())
                        .toList();
        if (declaredNames.isEmpty()) {
            violations.add("columns 声明为空：必须在 JSON 中逐列声明每个输出列的规范别名。");
            return violations;
        }
        Set<String> declarationLegalNames = Stream
                .concat(CANONICAL_RESULT_COLUMNS.stream(), catalog.columns().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String name : declaredNames) {
            if (!declarationLegalNames.contains(key(name))) {
                violations.add("columns 声明含非法列名 \"" + name + "\"：声明列必须是规范结果列 "
                        + CANONICAL_RESULT_COLUMNS + " 或语义目录列。");
            }
        }
        List<String> actualNames = topLevelOutputNames(sql);
        if (actualNames == null) {
            violations.add("SQL 无法解析出顶层输出列：无法完成 columns 声明与 SQL 别名的一致性校验。");
            return violations;
        }
        Set<String> actualSet = actualNames.stream().map(BankFreeSqlWhitelistValidator::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> declaredSet = declaredNames.stream().map(BankFreeSqlWhitelistValidator::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String name : declaredNames) {
            if (!actualSet.contains(key(name))) {
                violations.add("columns 声明与 SQL 输出不一致：声明了 \"" + name
                        + "\" 但 SQL 顶层输出没有该列（实际输出 " + actualNames + "）。");
            }
        }
        for (String name : actualNames) {
            if (!declaredSet.contains(key(name))) {
                violations.add("columns 声明与 SQL 输出不一致：SQL 输出列 \"" + name
                        + "\" 未在 columns 中声明（声明 " + declaredNames + "）。");
            }
        }
        return violations;
    }

    /** Returns the top-level SELECT output names in order, or null when not parseable. */
    public static List<String> topLevelOutputNames(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() != 1
                    || !(statements.getStatements().get(0) instanceof Select select)) {
                return null;
            }
            List<String> names = new ArrayList<>();
            PlainSelect main = mainPlainSelect(select);
            if (main == null) {
                return null;
            }
            for (SelectItem item : main.getSelectItems()) {
                names.add(outputName(item));
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    private static PlainSelect mainPlainSelect(Select select) {
        Select current = select;
        while (current instanceof ParenthesedSelect parenthesedSelect) {
            current = parenthesedSelect.getSelect();
        }
        return current instanceof PlainSelect plainSelect ? plainSelect : null;
    }

    private static String outputName(SelectItem item) {
        if (item.getAlias() != null && item.getAlias().getName() != null) {
            return item.getAlias().getName();
        }
        if (item.getExpression() instanceof Column column) {
            return column.getColumnName();
        }
        return "";
    }

    /** Single walk context: catalogue plus columns exported by CTEs and derived tables. */
    private static final class Walker {
        private final Catalog catalog;
        private final List<String> violations = new ArrayList<>();
        private final Set<String> tableNames = new LinkedHashSet<>();
        private final Set<String> cteNames = new LinkedHashSet<>();
        private final Set<String> exportedColumns = new LinkedHashSet<>();

        Walker(Catalog catalog) {
            this.catalog = catalog;
            tableNames.addAll(catalog.tables());
        }

        void validateMainSelect(Select select, Set<Select> visited) {
            if (select.getWithItemsList() != null) {
                for (WithItem withItem : select.getWithItemsList()) {
                    if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                        cteNames.add(key(withItem.getAlias().getName()));
                    }
                    if (withItem.getSelect() != null) {
                        exportedColumns.addAll(validateSubSelect(withItem.getSelect(), visited));
                    }
                }
            }
            Select main = select;
            while (main instanceof ParenthesedSelect parenthesedSelect) {
                main = parenthesedSelect.getSelect();
            }
            if (main instanceof PlainSelect plainSelect) {
                validatePlainSelect(plainSelect, visited);
            } else if (main instanceof SetOperationList) {
                violations.add("不支持 UNION/集合操作：只能输出单条 SELECT 或 WITH...SELECT 主查询。");
            } else if (main != null) {
                violations.add("不支持的查询形态：" + main.getClass().getSimpleName()
                        + "；只允许普通 SELECT/WITH 主查询。");
            }
        }

        /** Validates a nested select and returns its exported output names. */
        private Set<String> validateSubSelect(Select select, Set<Select> visited) {
            if (select == null || !visited.add(select)) {
                return Set.of();
            }
            Set<String> exported = new LinkedHashSet<>();
            if (select instanceof PlainSelect plainSelect) {
                exported.addAll(validatePlainSelect(plainSelect, visited));
            } else if (select instanceof ParenthesedSelect parenthesedSelect) {
                exported.addAll(validateSubSelect(parenthesedSelect.getSelect(), visited));
            } else if (select instanceof SetOperationList) {
                violations.add("不支持 UNION/集合操作：子查询只能是单条 SELECT。");
            } else {
                violations.add("不支持的子查询形态：" + (select == null ? "null"
                        : select.getClass().getSimpleName()) + "。");
            }
            return exported;
        }

        /** Validates one PlainSelect; FROM first so derived columns become referenceable. */
        private Set<String> validatePlainSelect(PlainSelect select, Set<Select> visited) {
            Set<String> exported = new LinkedHashSet<>();
            Set<String> localSources = new LinkedHashSet<>(cteNames);
            localSources.addAll(tableNames);
            validateFromItem(select.getFromItem(), localSources, visited, exported);
            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) {
                    validateFromItem(join.getRightItem(), localSources, visited, exported);
                }
            }
            Set<String> referenceable = new LinkedHashSet<>(catalog.columns());
            // Columns exported by WITH bodies / derived tables earlier in the walk stay
            // referenceable (for example the cur/baseline CTE pattern).
            referenceable.addAll(exportedColumns);
            referenceable.addAll(exported);

            if (select.getSelectItems() == null || select.getSelectItems().isEmpty()) {
                violations.add("SELECT 列表不能为空。");
                return exported;
            }
            for (SelectItem item : select.getSelectItems()) {
                Expression expression = item.getExpression();
                if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                    violations.add("禁止 SELECT *：必须逐列写出语义目录列或表达式并使用 AS 规范别名。");
                    continue;
                }
                String aliasName = item.getAlias() == null ? null : item.getAlias().getName();
                if (aliasName != null) {
                    if (!CANONICAL_RESULT_COLUMNS.contains(key(aliasName))) {
                        violations.add("输出别名 \"" + aliasName
                                + "\" 不在规范列名集合内：每个表达式列必须 AS 下列规范名之一 "
                                + CANONICAL_RESULT_COLUMNS + "。");
                    }
                } else if (!(expression instanceof Column)) {
                    violations.add("检测到无别名的表达式列（"
                            + summarizeExpression(expression)
                            + "）：只有直接引用目录列时才允许省略 AS；表达式必须 AS 规范名。");
                }
                visitExpression(expression, referenceable, visited);
            }
            visitExpression(select.getWhere(), referenceable, visited);
            visitExpression(select.getHaving(), referenceable, visited);
            if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressions() != null) {
                for (Object rawExpression : select.getGroupBy().getGroupByExpressions()) {
                    if (rawExpression instanceof Expression expression) {
                        visitExpression(expression, referenceable, visited);
                    }
                }
            }
            if (select.getOrderByElements() != null) {
                select.getOrderByElements().forEach(orderBy -> visitExpression(
                        orderBy.getExpression(), referenceable, visited));
            }
            exported.addAll(select.getSelectItems().stream()
                    .map(BankFreeSqlWhitelistValidator::outputName)
                    .filter(name -> name != null && !name.isBlank())
                    .map(BankFreeSqlWhitelistValidator::key).collect(Collectors.toList()));
            return exported;
        }

        private void validateFromItem(FromItem fromItem, Set<String> localSources,
                Set<Select> visited, Set<String> exported) {
            if (fromItem == null) {
                violations.add("查询缺少 FROM：必须 FROM 语义数据集 " + catalog.tables() + "。");
                return;
            }
            if (fromItem instanceof Table table) {
                String tableName = key(table.getName());
                if (!localSources.contains(tableName)) {
                    violations.add("表 \"" + table.getName() + "\" 不在语义数据集白名单内：只能 FROM "
                            + catalog.tables() + "（或 WITH 定义的 CTE 名称）。");
                }
                return;
            }
            if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
                if (parenthesedFromItem.getFromItem() instanceof ParenthesedSelect parenthesedSelect) {
                    exported.addAll(validateSubSelect(parenthesedSelect, visited));
                    return;
                }
                if (parenthesedFromItem.getFromItem() instanceof Select nestedSelect) {
                    exported.addAll(validateSubSelect(nestedSelect, visited));
                    return;
                }
                Set<String> nestedExported = new LinkedHashSet<>();
                validateFromItem(parenthesedFromItem.getFromItem(), localSources, visited,
                        nestedExported);
                exported.addAll(nestedExported);
                return;
            }
            if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
                exported.addAll(validateSubSelect(parenthesedSelect, visited));
                return;
            }
            violations.add("不支持的 FROM 形式：" + fromItem.getClass().getSimpleName()
                    + "；只允许语义数据集表、CTE 或括号子查询。");
        }

        private void visitExpression(Expression expression, Set<String> referenceable,
                Set<Select> visited) {
            if (expression == null) {
                return;
            }
            expression.accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(Column column) {
                    String columnName = key(column.getColumnName());
                    if (!referenceable.contains(columnName)) {
                        violations.add("列 \"" + column.getColumnName()
                                + "\" 不在语义目录内：只能引用目录维度（机构/bank_organization、数据日期/"
                                        + "bank_data_date）与目录指标列，或 WITH/子查询已定义的输出列。");
                    }
                    if (column.getTable() != null && column.getTable().getName() != null) {
                        String qualifier = key(column.getTable().getName());
                        if (!cteNames.contains(qualifier) && !tableNames.contains(qualifier)) {
                            violations.add("列限定名 \"" + column.getTable().getName() + "."
                                    + column.getColumnName() + "\" 的表前缀不在语义数据集或 CTE 内。");
                        }
                    }
                    super.visit(column);
                }

                @Override
                public void visit(Function function) {
                    String name = normalizeFunction(function);
                    if (name.isBlank()) {
                        violations.add("检测到无法识别的函数调用：函数白名单为 " + ALLOWED_FUNCTIONS
                                + "（窗口函数 OVER 允许）。");
                    } else if (!ALLOWED_FUNCTIONS.contains(name)) {
                        violations.add("函数 " + name + " 不在白名单内：允许的函数为 " + ALLOWED_FUNCTIONS
                                + "（窗口函数 OVER 允许）。");
                    }
                    super.visit(function);
                }

                @Override
                public void visit(ParenthesedSelect parenthesedSelect) {
                    validateSubSelect(parenthesedSelect, visited);
                }

                @Override
                public void visit(AllColumns allColumns) {
                    // COUNT(*) and similar aggregate arguments are legitimate.
                }

                @Override
                public void visit(AllTableColumns allTableColumns) {
                    violations.add("禁止 t.* 形式的列展开：必须逐列写出目录列。");
                }
            });
        }

        private static String normalizeFunction(Function function) {
            if (function.getMultipartName() == null || function.getMultipartName().isEmpty()) {
                return function.getName() == null ? "" : key(function.getName());
            }
            if (function.getMultipartName().size() != 1) {
                return "";
            }
            return key(function.getMultipartName().get(0));
        }

        private static String summarizeExpression(Expression expression) {
            String text = String.valueOf(expression);
            return text.length() > 40 ? text.substring(0, 40) + "…" : text;
        }
    }

    private static void addIdentifier(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** Normalizes quoted identifiers for case-insensitive ASCII comparison. */
    static String key(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        while (normalized.length() >= 2
                && ((normalized.charAt(0) == '`' && normalized.charAt(normalized.length() - 1) == '`')
                        || (normalized.charAt(0) == '"' && normalized.charAt(normalized.length() - 1) == '"')
                        || (normalized.charAt(0) == '[' && normalized.charAt(normalized.length() - 1) == ']'))) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
