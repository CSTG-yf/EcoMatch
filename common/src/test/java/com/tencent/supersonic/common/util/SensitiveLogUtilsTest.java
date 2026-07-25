package com.tencent.supersonic.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveLogUtilsTest {

    @Test
    void returnsDeterministicMetadataWithoutRawContent() {
        String secret = "SELECT * FROM account WHERE customer_id_card='320101199001011234'";

        String first = SensitiveLogUtils.summarize(secret);
        String second = SensitiveLogUtils.summarize(secret);

        assertEquals(first, second);
        assertTrue(first.startsWith("sha256="));
        assertTrue(first.endsWith(",chars=" + secret.length()));
        assertFalse(first.contains("320101199001011234"));
        assertFalse(first.contains("SELECT"));
    }
}
