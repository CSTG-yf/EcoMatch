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
import java.util.Properties;

/**
 * Executes identifier-mirrored S2SQL against the synthetic in-memory dataset. The parse ->
 * validate -> convert stages mirror {@code DiagH03CalciteReproTest}; execution then goes through
 * Calcite's own interpreter pipeline ({@code CoreRules.AGGREGATE_REDUCE_FUNCTIONS} ->
 * {@code Bindables.RULES} -> {@code Interpreters.bindable}), so no JDBC/Avatica transport (and
 * none of its optional protobuf dependency) and no janino code generation is required. The
 * case-insensitive identifier handling mirrors the H2 engine used by the runtime evaluation.
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
            // Convert the logical plan into the interpretable BINDABLE convention.
            RelNode bindableRel = hepRun(Bindables.RULES, optimized);

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
