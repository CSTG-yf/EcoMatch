package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NL2SQLParserTest {

    @Test
    void forwardsBankRoutingAttemptTelemetry() {
        ParseResp source = new ParseResp("safe");
        source.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(true, false,
                ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY, false,
                ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                SqlErrorType.JOIN_ERROR));
        ChatParseResp target = new ChatParseResp(1L);

        NL2SQLParser.copyParseResponse(source, target);

        ParseResp.BankRoutingAttemptTelemetry telemetry = target.getBankRoutingAttemptTelemetry();
        assertTrue(telemetry.isBankConstrainedPlanEnabled());
        assertFalse(telemetry.isBankDatasetQualified());
        assertEquals(ParseResp.BankRoutingSqlGenType.ONE_PASS_SELF_CONSISTENCY,
                telemetry.getSelectedSqlGenType());
        assertFalse(telemetry.isLlmCandidateCreated());
        assertEquals(ParseResp.BankCandidateRejectionState.VALIDATION_REJECTED,
                telemetry.getCandidateRejectionState());
        assertEquals(SqlErrorType.JOIN_ERROR, telemetry.getCandidateValidationErrorType());
    }

    @Test
    void preservesMissingBankRoutingAttemptTelemetry() {
        ParseResp source = new ParseResp("safe");
        ChatParseResp target = new ChatParseResp(1L);
        target.setBankRoutingAttemptTelemetry(new ParseResp.BankRoutingAttemptTelemetry(true, true,
                ParseResp.BankRoutingSqlGenType.BANK_CONSTRAINED_PLAN, true));

        NL2SQLParser.copyParseResponse(source, target);

        assertNull(target.getBankRoutingAttemptTelemetry());
    }
}
