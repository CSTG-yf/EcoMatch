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
