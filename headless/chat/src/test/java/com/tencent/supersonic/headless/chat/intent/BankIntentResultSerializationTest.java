package com.tencent.supersonic.headless.chat.intent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BankIntentResultSerializationTest {

    @Test
    void shouldRoundTripCompleteBankIntentResult() {
        BankIntentResult result = new BankIntentResult();
        result.setOriginalText("查询江苏省D市农商行的各项存款余额");
        result.setNormalizedText("查询江苏省D市农商行的各项存款余额");
        result.setScene(BankBusinessScene.OPERATION_ANALYSIS);
        result.setIntent(BankIntentType.TREND);
        result.setConfidence(0.96D);
        result.getIntentCandidates().add(BankIntentResult.IntentCandidate.builder()
                .intent(BankIntentType.TREND).confidence(0.96D).reason("逐季变化").build());
        result.getMetrics().add(BankIntentResult.MetricCandidate.builder().code("ZB001")
                .name("各项存款余额").matchedText("各项存款余额").confidence(0.99D).reason("指标词典匹配").build());
        result.getOrganizations().add(BankIntentResult.OrganizationSlot.builder().code("ORG004")
                .name("江苏省D市农商行").matchedText("D市农商行").confidence(0.98D).build());
        result.setTime(BankIntentResult.TimeSlot.builder().expression("2025Q1末到2026Q1末")
                .startDate(LocalDate.of(2025, 3, 31)).endDate(LocalDate.of(2026, 3, 31))
                .granularity("QUARTER").build());
        result.getFilters().add(BankIntentResult.FilterSlot.builder().field("机构").operator("=")
                .value("ORG004").sourceText("江苏省D市农商行").build());
        result.getClarifications().add(BankIntentResult.Clarification.builder().type("TIME")
                .question("请确认时间范围").options(List.of("2025Q1", "2026Q1")).reason("时间范围明确").build());
        result.getReasons().add("银行意图解析完成");

        BankIntentResult restored = assertDoesNotThrow(() -> roundTrip(result));

        assertEquals(result.getIntent(), restored.getIntent());
        assertEquals("ZB001", restored.getMetrics().get(0).getCode());
        assertEquals("ORG004", restored.getOrganizations().get(0).getCode());
        assertEquals(LocalDate.of(2026, 3, 31), restored.getTime().getEndDate());
        assertEquals("TIME", restored.getClarifications().get(0).getType());
    }

    private BankIntentResult roundTrip(BankIntentResult value)
            throws IOException, ClassNotFoundException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
            try (ObjectInputStream input =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (BankIntentResult) input.readObject();
            }
        }
    }
}
