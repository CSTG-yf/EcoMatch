package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankNl2SqlErrorTest {

    @Test
    void exposesValidationFailureAsTerminalUserMessage() {
        BankNl2SqlError error = BankNl2SqlError.afterSingleRepair(
                new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.VALIDATION_FAILED, "invalid range"));

        String parserError = error.toParserErrorMessage();

        assertTrue(BankNl2SqlError.isTerminalParserError(parserError));
        assertEquals("未能可靠识别该银行指标查询，请明确机构、指标和时间范围后重试。",
                BankNl2SqlError.toUserMessage(parserError));
    }

    @Test
    void exposesModelFailureAsTerminalUserMessage() {
        BankNl2SqlError error = BankNl2SqlError.modelFailure(new RuntimeException("unavailable"));

        String parserError = error.toParserErrorMessage();

        assertTrue(BankNl2SqlError.isTerminalParserError(parserError));
        assertEquals("银行指标查询服务暂时不可用，请稍后重试。",
                BankNl2SqlError.toUserMessage(parserError));
    }
}
