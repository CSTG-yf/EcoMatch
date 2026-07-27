package com.tencent.supersonic.chat.server.processor.execute;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BusinessNumericUtilsTest {

    @Test
    void parsesBoundedNumericText() {
        assertEquals(new BigDecimal("123.45"), BusinessNumericUtils.parse("123.45"));
    }

    @Test
    void rejectsNumericTextBeforeUnboundedDecimalParsing() {
        assertNull(BusinessNumericUtils.parse("0".repeat(10_000) + "1"));
    }
}
