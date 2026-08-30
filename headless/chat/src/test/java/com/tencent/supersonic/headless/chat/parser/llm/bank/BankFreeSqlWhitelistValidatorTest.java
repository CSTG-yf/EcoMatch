package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic AST-whitelist tests for the controlled free-SQL fallback channel. Positive cases
 * prove canonical aliasing, whitelisted functions (including window OVER), CTE reuse and bare
 * catalog columns stay legal; every negative case must name the offending element and the legal
 * set in Chinese so the repair round can act without any dataset asset leaking.
 */
class BankFreeSqlWhitelistValidatorTest {

    private static final BankFreeSqlWhitelistValidator.Catalog CATALOG =
            BankFreeSqlWhitelistValidator.catalogFromSchema(schema());

    private static final String SUMMARY_SQL =
            "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                    + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                    + "GROUP BY bank_organization";

    @Test
    void canonicalAliasSummaryWithWhitelistedAggregatePasses() {
        assertTrue(BankFreeSqlWhitelistValidator.validate(SUMMARY_SQL, CATALOG).isEmpty());
    }

    @Test
    void windowFunctionWithCanonicalAliasPasses() {
        String sql = "SELECT bank_organization AS org_code, "
                + "ROW_NUMBER() OVER (ORDER BY SUM(ZB001) DESC) AS rank_position "
                + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                + "GROUP BY bank_organization";
        assertTrue(BankFreeSqlWhitelistValidator.validate(sql, CATALOG).isEmpty());
    }

    @Test
    void cteExportedColumnsStayReferenceable() {
        String sql = "WITH cur AS (SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' GROUP BY bank_organization) "
                + "SELECT org_code, metric_value FROM cur";
        assertTrue(BankFreeSqlWhitelistValidator.validate(sql, CATALOG).isEmpty());
    }

    @Test
    void foreignTableIsRejectedWithTheLegalTableSet() {
        String sql = "SELECT ZB001 AS metric_value FROM other_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("other_dataset"));
        assertTrue(violations.get(0).contains("不在语义数据集白名单内"));
        assertTrue(violations.get(0).contains("bank_dataset"));
    }

    @Test
    void foreignColumnIsRejectedWithTheCatalogNotice() {
        String sql = "SELECT secret_col AS metric_value FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("secret_col"));
        assertTrue(violations.get(0).contains("不在语义目录内"));
    }

    @Test
    void nonWhitelistedFunctionIsRejectedWithTheFunctionWhitelist() {
        String sql = "SELECT MEDIAN(ZB001) AS metric_value FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("median"));
        assertTrue(violations.get(0).contains("不在白名单内"));
        assertTrue(violations.get(0).contains("sum"));
    }

    @Test
    void expressionWithoutAliasIsRejected() {
        String sql = "SELECT SUM(ZB001) FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("无别名的表达式列"));
    }

    @Test
    void nonCanonicalAliasIsRejectedWithTheCanonicalSet() {
        String sql = "SELECT ZB001 AS raw_output FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("raw_output"));
        assertTrue(violations.get(0).contains("不在规范列名集合内"));
    }

    @Test
    void multipleStatementsAreRejected() {
        String sql = "SELECT ZB001 AS metric_value FROM bank_dataset; DROP TABLE bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("只允许一条 SQL 语句"));
    }

    @Test
    void selectStarIsRejected() {
        String sql = "SELECT * FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertTrue(violations.stream().anyMatch(v -> v.contains("禁止 SELECT *")));
    }

    @Test
    void unionIsRejected() {
        String sql = "SELECT ZB001 AS metric_value FROM bank_dataset "
                + "UNION SELECT ZB002 AS metric_value FROM bank_dataset";
        List<String> violations = BankFreeSqlWhitelistValidator.validate(sql, CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("不支持 UNION"));
    }

    @Test
    void nonSelectStatementIsRejected() {
        List<String> violations = BankFreeSqlWhitelistValidator.validate(
                "UPDATE bank_dataset SET ZB001 = 1", CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("只允许只读的 SELECT/WITH 查询"));
    }

    @Test
    void unparsableSqlIsRejected() {
        List<String> violations = BankFreeSqlWhitelistValidator.validate(
                "SELEC FORM WERE", CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("SQL 解析失败"));
    }

    @Test
    void blankSqlIsRejected() {
        List<String> violations = BankFreeSqlWhitelistValidator.validate("  ", CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("SQL 为空"));
    }

    @Test
    void declaredColumnsMatchingSqlOutputPass() {
        assertTrue(BankFreeSqlWhitelistValidator
                .validateDeclaredColumns(SUMMARY_SQL, List.of("org_code", "metric_value"),
                        CATALOG)
                .isEmpty());
    }

    @Test
    void topLevelOutputNamesFollowSqlOrder() {
        assertEquals(List.of("org_code", "metric_value"),
                BankFreeSqlWhitelistValidator.topLevelOutputNames(SUMMARY_SQL));
    }

    @Test
    void missingDeclarationIsReportedPerColumn() {
        List<String> violations = BankFreeSqlWhitelistValidator
                .validateDeclaredColumns(SUMMARY_SQL, List.of("org_code"), CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("metric_value"));
        assertTrue(violations.get(0).contains("未在 columns 中声明"));
    }

    @Test
    void extraDeclarationIsReportedPerColumn() {
        List<String> violations = BankFreeSqlWhitelistValidator.validateDeclaredColumns(
                SUMMARY_SQL, List.of("org_code", "metric_value", "rank_position"), CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("rank_position"));
        assertTrue(violations.get(0).contains("但 SQL 顶层输出没有该列"));
    }

    @Test
    void emptyDeclarationIsRejected() {
        List<String> violations = BankFreeSqlWhitelistValidator
                .validateDeclaredColumns(SUMMARY_SQL, List.of(), CATALOG);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("columns 声明为空"));
    }

    @Test
    void illegalDeclaredNameIsRejectedWithTheLegalSets() {
        List<String> violations = BankFreeSqlWhitelistValidator.validateDeclaredColumns(
                SUMMARY_SQL, List.of("org_code", "bogus_col"), CATALOG);
        assertEquals(3, violations.size());
        assertTrue(violations.get(0).contains("bogus_col"));
        assertTrue(violations.get(0).contains("声明含非法列名"));
        assertTrue(violations.get(1).contains("bogus_col"));
        assertTrue(violations.get(1).contains("但 SQL 顶层输出没有该列"));
        assertTrue(violations.get(2).contains("metric_value"));
        assertTrue(violations.get(2).contains("未在 columns 中声明"));
    }

    private static LLMReq.LLMSchema schema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("bank_dataset");
        schema.setMetrics(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("ZB001").defaultAgg("SUM")
                        .build(),
                SchemaElement.builder().name("各项贷款余额").bizName("ZB002").defaultAgg("SUM")
                        .build()));
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build(),
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build()));
        return schema;
    }
}
