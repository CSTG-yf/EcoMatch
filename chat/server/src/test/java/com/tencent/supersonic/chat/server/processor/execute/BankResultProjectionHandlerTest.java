package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankResultProjector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankResultProjectionHandlerTest {

    @Test
    void shouldApplyBankLongFormContractAfterSemanticExecution() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG008", "江苏省H市农商行"))
                .selectedOrganizationCodes(List.of("ORG008"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("zb010")
                        .metricCode("ZB010").build()))
                .build();
        parseInfo.getProperties().put(BankResultProjector.CONTRACT_PROPERTY,
                JsonUtil.objectToMap(contract));
        QueryResult result = new QueryResult();
        result.setChatContext(parseInfo);
        result.setQueryResults(List.of(row("zb010", new BigDecimal("60.28"))));

        boolean applied = new BankResultProjectionHandler().apply(result);

        assertTrue(applied);
        assertEquals(List.of("org_code", "org_name", "metric_code", "metric_value"),
                result.getQueryColumns().stream().map(column -> column.getBizName()).toList());
        assertEquals(List.of(row("org_code", "ORG008", "org_name", "江苏省H市农商行", "metric_code",
                "ZB010", "metric_value", new BigDecimal("60.28"))), result.getQueryResults());
        assertTrue(result.getTextResult().contains("| metric_value |"));
    }

    @Test
    void shouldApplyPersistedRatioContractAfterTemplateExecution() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG004", "江苏省D市农商行"))
                .selectedOrganizationCodes(List.of("ORG004")).build();
        parseInfo.getProperties().put(BankResultProjector.CONTRACT_PROPERTY,
                JsonUtil.objectToMap(contract));
        QueryResult result = new QueryResult();
        result.setChatContext(parseInfo);
        result.setQueryResults(
                List.of(row("numerator_value", new BigDecimal("25.75"), "denominator_value",
                        new BigDecimal("48.50"), "ratio_percent", new BigDecimal("53.0928"))));

        boolean applied = new BankResultProjectionHandler().apply(result);

        assertTrue(applied);
        assertEquals(
                List.of("org_code", "org_name", "numerator_value", "denominator_value",
                        "ratio_percent"),
                result.getQueryColumns().stream().map(column -> column.getBizName()).toList());
        assertEquals(List.of(row("org_code", "ORG004", "org_name", "江苏省D市农商行", "numerator_value",
                new BigDecimal("25.75"), "denominator_value", new BigDecimal("48.50"),
                "ratio_percent", new BigDecimal("53.0928"))), result.getQueryResults());
        assertTrue(result.getTextResult().contains("| ratio_percent |"));
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }
}
