package com.tencent.supersonic.headless.chat.s2sql;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlErrorClassifier;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlFeature;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlValidationResult;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlValidator;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class ComplexSqlValidatorTest {

    private final ComplexSqlValidator validator = new ComplexSqlValidator();
    private LLMReq.LLMSchema schema;

    @BeforeEach
    void setUp() {
        schema = new LLMReq.LLMSchema();
        schema.setDataSetName("bank_indicator_dataset");
        schema.setMetrics(List.of(SchemaElement.builder().name("各项存款余额").bizName("zb001")
                .alias(List.of("存款")).build()));
        schema.setDimensions(
                List.of(SchemaElement.builder().name("数据日期").bizName("bank_data_date").build(),
                        SchemaElement.builder().name("机构").bizName("bank_organization").build()));
    }

    @Test
    void validatesTopNAndPeriodComparison() {
        String topN = "SELECT bank_organization, zb001 FROM bank_indicator_dataset "
                + "WHERE bank_data_date = '2026-04-30' ORDER BY zb001 DESC LIMIT 3";
        ComplexSqlValidationResult topNResult =
                validator.validate(topN, schema, "2026年4月存款最高的前3家农商行");
        Assert.assertTrue(topNResult.getEvaluation().getIsValidated());
        Assert.assertTrue(topNResult.getFeatures().contains(ComplexSqlFeature.TOP_N));

        String yoy = "WITH periods AS (SELECT bank_data_date, zb001 FROM bank_indicator_dataset "
                + "WHERE bank_data_date IN ('2025-04-30', '2026-04-30')) "
                + "SELECT MAX(zb001) / NULLIF(MIN(zb001), 0) - 1 FROM periods";
        ComplexSqlValidationResult yoyResult = validator.validate(yoy, schema, "2026年4月存款同比变化");
        Assert.assertTrue(yoyResult.getEvaluation().getIsValidated());
        Assert.assertTrue(yoyResult.getFeatures().contains(ComplexSqlFeature.YOY));
        Assert.assertTrue(yoyResult.getFeatures().contains(ComplexSqlFeature.NESTED_QUERY));
    }

    @Test
    void rejectsMissingTopNLimitAndUnsafeJoin() {
        String missingLimit = "SELECT bank_organization, zb001 FROM bank_indicator_dataset "
                + "WHERE bank_data_date = '2026-04-30' ORDER BY zb001 DESC";
        ComplexSqlValidationResult topNResult =
                validator.validate(missingLimit, schema, "存款最高的前3家机构");
        Assert.assertFalse(topNResult.getEvaluation().getIsValidated());
        Assert.assertEquals(SqlErrorType.DEFINITION_ERROR,
                topNResult.getEvaluation().getErrorType());

        String joinWithoutCondition = "SELECT zb001 FROM bank_indicator_dataset a "
                + "JOIN bank_metric_dataset b WHERE a.bank_data_date = '2026-04-30'";
        ComplexSqlValidationResult joinResult =
                validator.validate(joinWithoutCondition, schema, "查询存款");
        Assert.assertFalse(joinResult.getEvaluation().getIsValidated());
        Assert.assertEquals(SqlErrorType.JOIN_ERROR, joinResult.getEvaluation().getErrorType());
    }

    @Test
    void doesNotTreatAMinimumRegulatoryRequirementAsARankingIntent() {
        String threshold =
                "WITH bank_values AS (SELECT bank_organization, SUM(zb001) AS metric_value "
                        + "FROM bank_indicator_dataset WHERE bank_data_date >= '2026-03-31' "
                        + "AND bank_data_date <= '2026-03-31' GROUP BY bank_organization) "
                        + "SELECT bank_organization, metric_value, CASE WHEN metric_value >= 10.5 "
                        + "THEN 1 ELSE 0 END AS meets_condition FROM bank_values "
                        + "WHERE bank_organization = 'ORG008' ORDER BY bank_organization ASC";

        ComplexSqlValidationResult result =
                validator.validate(threshold, schema, "2026年一季度末，江苏省H市农商行的资本充足率满足10.5%的最低要求吗？");

        Assert.assertTrue(result.getEvaluation().getIsValidated());
        Assert.assertFalse(result.getFeatures().contains(ComplexSqlFeature.TOP_N));
    }

    @Test
    void recognizesTimeFiltersInMultilineCteQueries() {
        String ratio = "WITH bank_multi_ratio AS (\nSELECT 'ZB005' AS metric_code, "
                + "SUM(zb005) AS numerator_value, SUM(zb002) AS denominator_value\n"
                + "FROM bank_indicator_dataset\nWHERE bank_organization = 'ORG007' "
                + "AND bank_data_date >= '2026-03-31' AND bank_data_date <= '2026-03-31'\n)\n"
                + "SELECT metric_code, numerator_value, denominator_value FROM bank_multi_ratio";

        ComplexSqlValidationResult result =
                validator.validate(ratio, schema, "2026年3月末，江苏省G市农商行的个人贷款占各项贷款的比例？");

        Assert.assertTrue(result.getEvaluation().getIsValidated());
    }

    @Test
    void acceptsCrossJoinForIndependentSnapshots() {
        String crossJoin = "WITH current_snapshot AS (SELECT SUM(zb001) AS current_value "
                + "FROM bank_indicator_dataset WHERE bank_data_date = '2026-04-30'), "
                + "baseline_snapshot AS (SELECT SUM(zb001) AS baseline_value "
                + "FROM bank_indicator_dataset WHERE bank_data_date = '2025-04-30') "
                + "SELECT current_value, baseline_value FROM current_snapshot CROSS JOIN baseline_snapshot";

        ComplexSqlValidationResult result =
                validator.validate(crossJoin, schema, "synthetic snapshot comparison");

        Assert.assertTrue(result.getEvaluation().getIsValidated());
    }

    @Test
    void classifiesExecutionFailures() {
        Assert.assertEquals(SqlErrorType.SYNTAX_ERROR,
                ComplexSqlErrorClassifier.classifyExecutionError("syntax error near FROM"));
        Assert.assertEquals(SqlErrorType.MAPPING_ERROR,
                ComplexSqlErrorClassifier.classifyExecutionError("unknown column ZB999"));
        Assert.assertEquals(SqlErrorType.JOIN_ERROR,
                ComplexSqlErrorClassifier.classifyExecutionError("ambiguous column in join"));
        Assert.assertEquals(SqlErrorType.FILTER_ERROR,
                ComplexSqlErrorClassifier.classifyExecutionError("invalid date in where filter"));
        Assert.assertEquals(SqlErrorType.EXECUTION_ERROR,
                ComplexSqlErrorClassifier.classifyExecutionError("connection timeout"));
    }
}
