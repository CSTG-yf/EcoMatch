package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.calcite.ViewExpanderImpl;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompiledQuery;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompilationRoute;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.tools.Frameworks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic repro (offline) for the online evaluation failure of the
 * single-org + multi-metric(+derived 存贷比) + single-day + 前三/后四 ranking question
 * (dev split VAL-H-03 class; r4 jar passed, r5 workspace failed with
 * candidateRejectionState=PLAN_EXCEPTION and a CalciteContextException logged at
 * c.t.s.h.c.t.p.calcite.SemanticNode:425).
 *
 * <p>This test compiles the canonical plan shape through the CURRENT workspace
 * {@code BankQueryPlanCompiler + BankS2SqlTemplateFactory}, compares the output with the
 * r4-passing S2SQL text captured from the official evaluation artifact, scans the suspect
 * variants (limit 2N vs null, derived operand order, registry ratio scales), and runs every
 * compiled SQL through the same Calcite pipeline the runtime uses at parse time
 * ({@code Configuration.getParserConfig} + {@code SqlValidator} + {@code SqlToRelConverter}
 * + HepPlanner, mirroring {@code SemanticNode.optimize}). No src/main code is modified.
 */
class DiagH03CalciteReproTest {

    private static final String DATASET = "银行业智能问数数据集";
    private static final String DATE_FIELD = "数据日期";
    private static final String MIRROR_TABLE = "BANK_METRIC_DATASET";
    private static final String MIRROR_DATE = "BANK_DATA_DATE";

    /** Exact S2SQL of the r4 (passing) run for this question shape, from the official artifact. */
    private static final String R4_BASELINE_SQL = """
            WITH bank_metric_0 AS (
              SELECT 'ZB001' AS metric_code, bank_organization, SUM(zb001) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_1 AS (
              SELECT 'ZB002' AS metric_code, bank_organization, SUM(zb002) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_2 AS (
              SELECT 'ZB011' AS metric_code, bank_organization, SUM(zb011) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_3 AS (
              SELECT 'ZB012' AS metric_code, bank_organization, SUM(zb012) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_4 AS (
              SELECT 'ZB013' AS metric_code, bank_organization, SUM(zb013) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_5 AS (
              SELECT 'ZB015' AS metric_code, bank_organization, SUM(zb015) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_6 AS (
              SELECT 'ZB016' AS metric_code, bank_organization, SUM(zb016) AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_metric_7 AS (
              SELECT 'DERIVED_ZB002_DIV_ZB001' AS metric_code, bank_organization,
                     SUM(ZB002) / NULLIF(SUM(ZB001), 0) * 100.0 AS metric_value
              FROM 银行业智能问数数据集
              WHERE 数据日期 >= '2025-11-30' AND 数据日期 <= '2025-11-30'
              GROUP BY bank_organization
            ),
            bank_ranked AS (
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_0
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_1
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_2
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value ASC, bank_organization ASC) AS rank_position
            FROM bank_metric_3
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value ASC, bank_organization ASC) AS rank_position
            FROM bank_metric_4
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_5
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_6
            WHERE metric_value IS NOT NULL
            UNION ALL
            SELECT metric_code, bank_organization, metric_value,
                   ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS rank_position
            FROM bank_metric_7
            WHERE metric_value IS NOT NULL
            )
            SELECT metric_code, bank_organization, metric_value, rank_position
            FROM bank_ranked
            WHERE bank_organization = 'ORG006'
            ORDER BY metric_code ASC, bank_organization ASC
            """;

    private static final List<String> DIRECT_METRICS =
            List.of("ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015", "ZB016");

    private final BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();

    @Test
    void canonicalPlanCompilesToExactlyTheR4PassingSql() {
        CompiledQuery compiled = compiler.compile(canonicalPlan("ZB002", "ZB001", null),
                canonicalHints("DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比"), schema());

        assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        String sql = compiled.getS2sql();
        assertEquals(normalize(R4_BASELINE_SQL), normalize(sql),
                "workspace compiler output drifted from the r4-passing SQL");
        assertFalse(sql.contains(" LIMIT "), "plan.limit must not leak into the derived ranking SQL");
    }

    @Test
    void variantScanLimitNullVsTwoN() {
        // v58 prompt contract: ranking 前3+后4 => plan.limit = 7. The template must ignore it.
        CompiledQuery withLimit = compiler.compile(canonicalPlan("ZB002", "ZB001", 7),
                canonicalHints("DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比"), schema());
        CompiledQuery withoutLimit = compiler.compile(canonicalPlan("ZB002", "ZB001", null),
                canonicalHints("DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比"), schema());
        assertEquals(normalize(withoutLimit.getS2sql()), normalize(withLimit.getS2sql()),
                "limit must not change the compiled SQL");
        validateAndOptimize(withLimit.getS2sql(), "limit=7 variant");
        validateAndOptimize(withoutLimit.getS2sql(), "limit=null variant");
    }

    @Test
    void variantScanDerivedOperandsAndRegistryScales() {
        record Variant(String num, String den, String code, String name, String expectedScale,
                       String extraOperandMetric) {}
        List<Variant> variants = List.of(
                new Variant("ZB002", "ZB001", "DERIVED_ZB002_DIV_ZB001", "存贷比", "100.0", null),
                new Variant("ZB011", "ZB018", "DERIVED_ZB011_DIV_ZB018", "人均利润", "1.0", "ZB018"),
                new Variant("ZB001", "ZB019", "DERIVED_ZB001_DIV_ZB019", "网点平均存款规模", "10000.0",
                        "ZB019"),
                // inverted 存贷比 operands: still valid SQL, documents the default scale path
                new Variant("ZB001", "ZB002", "DERIVED_ZB001_DIV_ZB002", "存贷比倒挂", "100.0", null));
        for (Variant variant : variants) {
            CompiledQuery compiled = compiler.compile(
                    canonicalPlan(variant.num(), variant.den(), null, variant.extraOperandMetric(),
                            variant.name()),
                    canonicalHints(variant.code(), variant.num(), variant.den(), variant.name(),
                            variant.extraOperandMetric()),
                    schemaWith(variant.extraOperandMetric()));
            String sql = compiled.getS2sql();
            assertTrue(sql.contains("SUM(" + variant.num() + ") / NULLIF(SUM(" + variant.den()
                            + "), 0) * " + variant.expectedScale() + " AS metric_value"),
                    () -> "unexpected ratio scale rendering for " + variant.code() + ": " + sql);
            validateAndOptimize(sql, variant.code());
        }
    }

    @Test
    void derivedFreePlanStillCompilesAndValidates() {
        BankQueryPlan plan = canonicalPlan("ZB002", "ZB001", null);
        plan.setDerivedMetrics(List.of());
        CompiledQuery compiled = compiler.compile(plan,
                SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                        .allowedMetrics(Set.of("ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015",
                                "ZB016"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                        .requiredMetrics(Set.of("ZB001", "ZB002", "ZB011", "ZB012", "ZB013",
                                "ZB015", "ZB016"))
                        .requiredOrganizationCodes(Set.of("ORG006"))
                        .requiredStartDate(java.time.LocalDate.of(2025, 11, 30))
                        .requiredEndDate(java.time.LocalDate.of(2025, 11, 30))
                        .maxLimit(100).build(),
                schema());
        // Without derived metrics the same shape routes to the GENERIC_DIRECT family which is
        // rendered through the struct S2SQL renderer, not an S2SQL template string.
        assertEquals(CompilationRoute.STRUCT, compiled.getRoute(),
                "derived-free ranking must not silently fall into another S2SQL template");
    }

    /**
     * Same Calcite pipeline the runtime uses at parse time: parse → SqlValidator.validate →
     * SqlToRelConverter → HepPlanner.findBestExp (mirror of SemanticNode.optimize minus the
     * semantic-layer FilterToGroupScanRule). The Chinese dataset/date identifiers are mapped to
     * an ASCII mirror table because the real runtime never feeds raw S2SQL to Calcite — it feeds
     * the generated ontology subquery — so only the SQL shape matters here.
     */
    private void validateAndOptimize(String sql, String label) {
        String mirror = sql.replace(DATASET, MIRROR_TABLE).replace(DATE_FIELD, MIRROR_DATE);
        try {
            EngineType engineType = EngineType.H2;
            SqlNode parsed = SqlParser.create(mirror, Configuration.getParserConfig(engineType))
                    .parseStmt();

            CalciteConnectionConfig connConfig = lenientConnectionConfig();
            CalciteSchema rootSchema = CalciteSchema.createRootSchema(true, false);
            rootSchema.add(MIRROR_TABLE, wideMirrorTable());
            CalciteCatalogReader catalogReader = new CalciteCatalogReader(rootSchema,
                    Collections.singletonList(rootSchema.getName()), Configuration.typeFactory,
                    connConfig);
            SqlValidator validator = SqlValidatorUtil.newValidator(
                    new ChainedSqlOperatorTable(List.of(SqlStdOperatorTable.instance())),
                    catalogReader, Configuration.typeFactory,
                    Configuration.getValidatorConfig(engineType)
                            .withLenientOperatorLookup(true));
            SqlNode validated = validator.validate(parsed);

            HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();
            RelOptClusterHolder holder = new RelOptClusterHolder(hepProgramBuilder);
            Frameworks.newConfigBuilder().parserConfig(Configuration.getParserConfig(engineType))
                    .defaultSchema(rootSchema.plus()).build();
            SqlToRelConverter converter = new SqlToRelConverter(new ViewExpanderImpl(), validator,
                    catalogReader, holder.cluster,
                    Frameworks.newConfigBuilder().build().getConvertletTable(),
                    Configuration.getConverterConfig());
            RelNode rel = converter.convertQuery(validated, false, true).rel;
            HepPlanner planner = new HepPlanner(hepProgramBuilder.build());
            planner.setRoot(rel);
            RelNode best = planner.findBestExp();
            assertTrue(best != null, label + ": optimizer produced no plan");
            System.out.println("[DiagH03] " + label + ": Calcite validate+optimize OK (rel="
                    + best.explain().trim().replace("\n", " | ") + ")");
        } catch (Exception e) {
            System.out.println("[DiagH03] " + label + ": FAILED SQL MIRROR=\n" + mirror);
            System.out.println("[DiagH03] " + label + ": exception type="
                    + e.getClass().getName() + " message=" + e.getMessage());
            throw new AssertionError(label + ": Calcite rejected the compiled S2SQL", e);
        }
    }

    /** Tiny holder so the same cluster is shared by converter and planner construction. */
    private static final class RelOptClusterHolder {
        final org.apache.calcite.plan.RelOptCluster cluster;

        RelOptClusterHolder(HepProgramBuilder builder) {
            this.cluster = org.apache.calcite.plan.RelOptCluster.create(
                    new HepPlanner(builder.build()),
                    new RexBuilder(Configuration.typeFactory));
        }
    }

    private static CalciteConnectionConfig lenientConnectionConfig() {
        Properties props = new Properties();
        // H2 (the evaluation engine) resolves identifiers case-insensitively; the compiled SQL
        // legitimately mixes SUM(zb001) and SUM(ZB002) spellings.
        props.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), Boolean.FALSE.toString());
        props.put(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
        props.put(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.TO_LOWER.toString());
        return new CalciteConnectionConfigImpl(props);
    }

    private static AbstractTable wideMirrorTable() {
        return new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory typeFactory) {
                RelDataTypeFactory.Builder builder = typeFactory.builder();
                for (int i = 1; i <= 19; i++) {
                    String column = String.format(Locale.ROOT, "zb%03d", i);
                    builder.add(column, typeFactory.createTypeWithNullability(
                            typeFactory.createSqlType(SqlTypeName.DECIMAL, 20, 6), true));
                }
                builder.add("bank_organization", typeFactory.createTypeWithNullability(
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, 32), true));
                builder.add("bank_data_date", typeFactory.createTypeWithNullability(
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, 32), true));
                builder.add("org_name", typeFactory.createTypeWithNullability(
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, 255), true));
                return builder.build();
            }
        };
    }

    private BankQueryPlan canonicalPlan(String numerator, String denominator, Integer limit) {
        return canonicalPlan(numerator, denominator, limit, null, "存贷比");
    }

    private BankQueryPlan canonicalPlan(String numerator, String denominator, Integer limit,
            String extraOperandMetric, String derivedName) {
        List<BankQueryPlan.OrderBy> orderBy = List.of(BankQueryPlan.OrderBy.builder()
                .field("ZB001").direction(BankQueryPlan.SortDirection.DESC).build());
        List<String> metrics = new ArrayList<>(DIRECT_METRICS);
        if (extraOperandMetric != null && !metrics.contains(extraOperandMetric)) {
            metrics.add(extraOperandMetric);
        }
        List<String> outputColumns = new ArrayList<>(List.of("bank_organization"));
        outputColumns.addAll(metrics);
        List<BankQueryPlan.Metric> planMetrics = metrics.stream().map(this::metric).toList();
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .action(BankQueryPlan.PlanAction.EXECUTE).intent(BankIntentType.RANKING)
                .metrics(planMetrics)
                .derivedMetrics(List.of(BankQueryPlan.DerivedMetric.builder()
                        .metricCode("DERIVED_" + numerator + "_DIV_" + denominator)
                        .numerator(numerator).denominator(denominator).name(derivedName).build()))
                .dimensions(List.of("bank_organization"))
                .organizations(List.of(BankQueryPlan.Organization.builder().code("ORG006").build()))
                .time(BankQueryPlan.TimeRange.builder()
                        .startDate(java.time.LocalDate.of(2025, 11, 30))
                        .endDate(java.time.LocalDate.of(2025, 11, 30))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(orderBy).limit(limit)
                .output(BankQueryPlan.Output.builder().columns(outputColumns)
                        .orderSensitive(true).build())
                .build();
    }

    private SemanticIntentHints canonicalHints(String code, String numerator, String denominator,
            String name) {
        return canonicalHints(code, numerator, denominator, name, null);
    }

    private SemanticIntentHints canonicalHints(String code, String numerator, String denominator,
            String name, String extraOperandMetric) {
        java.util.LinkedHashSet<String> metrics = new java.util.LinkedHashSet<>(
                List.of("ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015", "ZB016"));
        if (extraOperandMetric != null) {
            metrics.add(extraOperandMetric);
        }
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(metrics)
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(metrics)
                .requiredOrganizationCodes(Set.of("ORG006"))
                .requiredStartDate(java.time.LocalDate.of(2025, 11, 30))
                .requiredEndDate(java.time.LocalDate.of(2025, 11, 30))
                .requiredDerivedMetrics(List.of(new SemanticIntentHints.DerivedMetricSpec(code,
                        numerator, denominator, name)))
                .maxLimit(100).build();
    }

    private LLMReq.LLMSchema schema() {
        return schemaWith(null);
    }

    private LLMReq.LLMSchema schemaWith(String extraMetric) {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(97L);
        schema.setDataSetName(DATASET);
        // bizNames lowercase, mirroring the runtime dataset schema (r4 SQL renders SUM(zb001)).
        List<SchemaElement> metrics = new ArrayList<>(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("zb001").defaultAgg("SUM").build(),
                SchemaElement.builder().name("各项贷款余额").bizName("zb002").defaultAgg("SUM").build(),
                SchemaElement.builder().name("净利润").bizName("zb011").defaultAgg("SUM").build(),
                SchemaElement.builder().name("成本收入比").bizName("zb012").defaultAgg("SUM").build(),
                SchemaElement.builder().name("不良贷款率").bizName("zb013").defaultAgg("SUM").build(),
                SchemaElement.builder().name("拨备覆盖率").bizName("zb015").defaultAgg("SUM").build(),
                SchemaElement.builder().name("资本充足率").bizName("zb016").defaultAgg("SUM").build()));
        if (extraMetric != null) {
            metrics.add(SchemaElement.builder().name(extraMetric + "名称")
                    .bizName(extraMetric.toLowerCase(Locale.ROOT)).defaultAgg("SUM").build());
        }
        schema.setMetrics(metrics);
        schema.setDimensions(
                List.of(SchemaElement.builder().name("机构").bizName("bank_organization").build()));
        schema.setPartitionTime(
                SchemaElement.builder().name(DATE_FIELD).bizName("bank_data_date").build());
        return schema;
    }

    private BankQueryPlan.Metric metric(String bizName) {
        return BankQueryPlan.Metric.builder().bizName(bizName)
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
