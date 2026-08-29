package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.calcite.ViewExpanderImpl;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import org.apache.calcite.DataContext;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.interpreter.Interpreters;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Executes identifier-mirrored S2SQL against the synthetic in-memory dataset. The parse ->
 * validate -> convert stages mirror {@code DiagH03CalciteReproTest}; execution then goes through
 * Calcite's own interpreter pipeline ({@code CoreRules.AGGREGATE_REDUCE_FUNCTIONS} ->
 * {@code Bindables.RULES} -> {@code Interpreters.bindable}), so no JDBC/Avatica transport (and
 * none of its optional protobuf dependency) and no janino code generation is required. The
 * case-insensitive identifier handling mirrors the H2 engine used by the runtime evaluation.
 *
 * <p>Window functions: the configured converter keeps {@code ROW_NUMBER() OVER (ORDER BY ...)}
 * inline in a {@code LogicalProject} as a {@link org.apache.calcite.rex.RexOver} and the
 * interpreter cannot translate it. The ranked ranking template only uses that exact window
 * shape, so projects containing it are materialized here in Java ({@code ROW_NUMBER} = 1-based
 * position after a stable sort by the window's order keys, per key direction) and replaced by a
 * {@code LogicalValues} before the bindable conversion. Every other operator (aggregates,
 * filters, unions, sorts) still executes through Calcite itself.
 */
public final class BankDiffExecutor implements AutoCloseable {

    private final CalciteSchema rootSchema;

    public BankDiffExecutor(BankDiffDataset dataset) {
        rootSchema = CalciteSchema.createRootSchema(true, false);
        rootSchema.add(BankDiffDataset.MIRROR_TABLE, new InMemoryMirrorTable(dataset));
    }

    /** Runs one mirrored SQL statement and returns its rows position-ordered. */
    public List<Object[]> execute(String mirrorSql) {
        try {
            EngineType engineType = EngineType.H2;
            SqlNode parsed = SqlParser.create(mirrorSql, Configuration.getParserConfig(engineType))
                    .parseStmt();

            CalciteCatalogReader catalogReader = new CalciteCatalogReader(rootSchema,
                    List.of(rootSchema.getName()), Configuration.typeFactory,
                    lenientConnectionConfig());
            SqlValidator validator = SqlValidatorUtil.newValidator(
                    new ChainedSqlOperatorTable(
                            List.of(SqlStdOperatorTable.instance(), catalogReader)),
                    catalogReader, Configuration.typeFactory,
                    Configuration.getValidatorConfig(engineType)
                            .withLenientOperatorLookup(true));
            SqlNode validated = validator.validate(parsed);

            // Logical rel through the DiagH03 pipeline (no Volcano conversion needed: the
            // interpreter consumes the logical nodes directly once BINDABLE-converted).
            RelOptCluster cluster = RelOptCluster.create(new HepPlanner(new HepProgramBuilder()
                    .build()), new RexBuilder(Configuration.typeFactory));
            SqlToRelConverter converter = new SqlToRelConverter(new ViewExpanderImpl(), validator,
                    catalogReader, cluster,
                    org.apache.calcite.tools.Frameworks.newConfigBuilder().build()
                            .getConvertletTable(),
                    Configuration.getConverterConfig());
            RelRoot root = converter.convertQuery(validated, false, true);

            // Interpreter preparation: split calc nodes and reduce AVG-style aggregates into
            // SUM/COUNT. Filter/project pushdown into the scan is deliberately omitted — the
            // interpreter applies plain BindableFilter/BindableProject nodes reliably, while
            // pushed-down scan filters misbind non-scan columns.
            RelNode optimized = hepRun(List.of(
                    CoreRules.CALC_SPLIT,
                    CoreRules.AGGREGATE_REDUCE_FUNCTIONS), root.rel);
            // The interpreter rejects inline ROW_NUMBER() OVER project expressions (see class
            // javadoc), so materialize exactly those projects before bindable conversion.
            RelNode materialized = materializeRowNumberProjects(optimized, dataContext());
            // Convert the logical plan into the interpretable BINDABLE convention.
            RelNode bindableRel = hepRun(Bindables.RULES, materialized);

            Bindable bindable = Interpreters.bindable(bindableRel);
            Enumerable<Object[]> enumerable = (Enumerable<Object[]>) bindable.bind(dataContext());
            List<Object[]> rows = new java.util.ArrayList<>();
            for (Object row : enumerable) {
                rows.add((Object[]) row);
            }
            return rows;
        } catch (SqlParseException | RuntimeException exception) {
            throw new AssertionError("SQL execution failed: " + exception.getMessage() + "\nSQL:\n"
                    + mirrorSql, exception);
        }
    }

    private static RelNode hepRun(Collection<RelOptRule> rules, RelNode rel) {
        HepProgramBuilder builder = new HepProgramBuilder();
        rules.forEach(builder::addRuleInstance);
        HepPlanner planner = new HepPlanner(builder.build());
        planner.setRoot(rel);
        return planner.findBestExp();
    }

    /**
     * Replaces every project whose expressions contain a window function with precomputed rows.
     * The walk is post-order so nested over-projects (the ranked template's UNION ALL branches)
     * materialize independently before their parent operators are converted.
     */
    private RelNode materializeRowNumberProjects(RelNode rel, DataContext context) {
        List<RelNode> inputs = rel.getInputs();
        List<RelNode> newInputs = new java.util.ArrayList<>(inputs.size());
        boolean changed = false;
        for (RelNode input : inputs) {
            RelNode replacement = materializeRowNumberProjects(input, context);
            newInputs.add(replacement);
            changed |= replacement != input;
        }
        if (changed) {
            rel = rel.copy(rel.getTraitSet(), newInputs);
        }
        if (rel instanceof org.apache.calcite.rel.logical.LogicalProject project
                && project.containsOver()) {
            return materializeRowNumberProject(project, context);
        }
        return rel;
    }

    /**
     * Materializes one project containing {@code ROW_NUMBER() OVER (ORDER BY key ...)} over the
     * interpreted input subtree. Row numbers are 1-based positions after a stable sort by the
     * window's order keys (each key in its declared direction, BigDecimal/String natural
     * ordering), exactly the semantics of {@code ROW_NUMBER} in the executed SQL: no partition
     * keys are accepted and ties fall through to the next order key.
     */
    private RelNode materializeRowNumberProject(
            org.apache.calcite.rel.logical.LogicalProject project, DataContext context) {
        List<org.apache.calcite.rex.RexNode> exprs = project.getProjects();
        Map<Integer, long[]> ranksByExpr = new java.util.LinkedHashMap<>();
        List<Object[]> inputRows = executeBindable(project.getInput(), context);
        for (int i = 0; i < exprs.size(); i++) {
            if (!(exprs.get(i) instanceof org.apache.calcite.rex.RexOver over)) {
                continue;
            }
            if (over.getKind() != org.apache.calcite.sql.SqlKind.ROW_NUMBER
                    || !over.getWindow().partitionKeys.isEmpty()) {
                throw new AssertionError("unsupported window expression: " + over);
            }
            ranksByExpr.put(i, rowNumbers(over, inputRows));
        }
        RexBuilder rexBuilder = project.getCluster().getRexBuilder();
        List<com.google.common.collect.ImmutableList<org.apache.calcite.rex.RexLiteral>> tuples =
                new java.util.ArrayList<>();
        for (int r = 0; r < inputRows.size(); r++) {
            Object[] inputRow = inputRows.get(r);
            com.google.common.collect.ImmutableList.Builder<org.apache.calcite.rex.RexLiteral> tuple =
                    com.google.common.collect.ImmutableList.builder();
            for (int i = 0; i < exprs.size(); i++) {
                long[] ranks = ranksByExpr.get(i);
                Object value = ranks != null ? ranks[r] : evalSimple(exprs.get(i), inputRow);
                tuple.add((org.apache.calcite.rex.RexLiteral) rexBuilder.makeLiteral(value,
                        project.getRowType().getFieldList().get(i).getType()));
            }
            tuples.add(tuple.build());
        }
        return org.apache.calcite.rel.logical.LogicalValues.create(project.getCluster(),
                project.getRowType(), com.google.common.collect.ImmutableList.copyOf(tuples));
    }

    private static long[] rowNumbers(org.apache.calcite.rex.RexOver over, List<Object[]> rows) {
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (left, right) -> {
            for (org.apache.calcite.rex.RexFieldCollation key : over.getWindow().orderKeys) {
                int index = collationIndex(key);
                int cmp = compareCells(rows.get(left)[index], rows.get(right)[index],
                        key.getDirection()
                                == org.apache.calcite.rel.RelFieldCollation.Direction.DESCENDING);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        });
        long[] ranks = new long[rows.size()];
        for (int position = 0; position < order.length; position++) {
            ranks[order[position]] = position + 1L;
        }
        return ranks;
    }

    private static int collationIndex(org.apache.calcite.rex.RexFieldCollation key) {
        org.apache.calcite.rex.RexNode operand = key.left;
        if (operand instanceof org.apache.calcite.rex.RexInputRef ref) {
            return ref.getIndex();
        }
        if (operand instanceof org.apache.calcite.rex.RexFieldAccess access) {
            return access.getField().getIndex();
        }
        throw new AssertionError("unsupported window order key: " + operand);
    }

    private static int compareCells(Object left, Object right, boolean descending) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return descending ? 1 : -1;
        }
        if (right == null) {
            return descending ? -1 : 1;
        }
        @SuppressWarnings("unchecked")
        int cmp = ((java.lang.Comparable<Object>) left).compareTo(right);
        return descending ? -cmp : cmp;
    }

    private static Object evalSimple(org.apache.calcite.rex.RexNode node, Object[] row) {
        if (node instanceof org.apache.calcite.rex.RexInputRef ref) {
            return row[ref.getIndex()];
        }
        if (node instanceof org.apache.calcite.rex.RexLiteral literal) {
            return literal.getValue();
        }
        throw new AssertionError("unsupported project expression over window: " + node);
    }

    private List<Object[]> executeBindable(RelNode rel, DataContext context) {
        RelNode bindable = hepRun(Bindables.RULES, rel);
        Bindable bind = Interpreters.bindable(bindable);
        List<Object[]> rows = new java.util.ArrayList<>();
        for (Object row : (Enumerable<Object[]>) bind.bind(context)) {
            rows.add((Object[]) row);
        }
        return rows;
    }

    @Override
    public void close() {
        // No external resources: everything lives inside the JVM.
    }

    @SuppressWarnings("UnnecessaryAnonymousClass")
    private DataContext dataContext() {
        return new DataContext() {
            @Override
            public SchemaPlus getRootSchema() {
                return rootSchema.plus();
            }

            @Override
            public org.apache.calcite.adapter.java.JavaTypeFactory getTypeFactory() {
                return Configuration.typeFactory instanceof
                        org.apache.calcite.adapter.java.JavaTypeFactory
                        ? (org.apache.calcite.adapter.java.JavaTypeFactory) Configuration.typeFactory
                        : new org.apache.calcite.jdbc.JavaTypeFactoryImpl(
                                org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
            }

            @Override
            public QueryProvider getQueryProvider() {
                return null;
            }

            @Override
            public Object get(String name) {
                return null;
            }
        };
    }

    private static CalciteConnectionConfig lenientConnectionConfig() {
        Properties props = new Properties();
        props.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), Boolean.FALSE.toString());
        props.put(CalciteConnectionProperty.UNQUOTED_CASING.camelName(),
                Casing.UNCHANGED.toString());
        props.put(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.TO_LOWER.toString());
        return new CalciteConnectionConfigImpl(props);
    }

    /**
     * Wide mirror table identical to the {@code DiagH03CalciteReproTest.wideMirrorTable} shape:
     * zb001..zb019 DECIMAL(20,6) plus bank_organization / bank_data_date / org_name VARCHAR.
     * Scannable so the bindable convention can scan it without any JDBC transport.
     */
    private static final class InMemoryMirrorTable extends AbstractTable
            implements org.apache.calcite.schema.ScannableTable {

        private final List<Object[]> rows;

        InMemoryMirrorTable(BankDiffDataset dataset) {
            List<Object[]> materialized = new java.util.ArrayList<>(dataset.rows().size());
            for (BankDiffDataset.Row row : dataset.rows()) {
                // Cell order must match the declared row type exactly:
                // zb001..zb019, bank_organization, BANK_DATA_DATE, org_name.
                Object[] cells = new Object[BankDiffDataset.METRIC_COUNT + 3];
                for (int metric = 0; metric < BankDiffDataset.METRIC_COUNT; metric++) {
                    cells[metric] = row.metrics().get(metric);
                }
                cells[BankDiffDataset.METRIC_COUNT] = row.organization();
                cells[BankDiffDataset.METRIC_COUNT + 1] = row.date().toString();
                cells[BankDiffDataset.METRIC_COUNT + 2] = row.organization();
                materialized.add(cells);
            }
            this.rows = List.copyOf(materialized);
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            RelDataTypeFactory.Builder builder = typeFactory.builder();
            for (int metric = 1; metric <= BankDiffDataset.METRIC_COUNT; metric++) {
                builder.add(String.format(Locale.ROOT, "zb%03d", metric),
                        typeFactory.createTypeWithNullability(
                                typeFactory.createSqlType(SqlTypeName.DECIMAL, 20, 6), true));
            }
            builder.add("bank_organization", typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(SqlTypeName.VARCHAR, 32), true));
            builder.add(BankDiffDataset.MIRROR_DATE, typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(SqlTypeName.VARCHAR, 32), true));
            builder.add("org_name", typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(SqlTypeName.VARCHAR, 255), true));
            return builder.build();
        }

        @Override
        public Enumerable<Object[]> scan(DataContext root) {
            return org.apache.calcite.linq4j.Linq4j.asEnumerable(rows);
        }
    }
}
