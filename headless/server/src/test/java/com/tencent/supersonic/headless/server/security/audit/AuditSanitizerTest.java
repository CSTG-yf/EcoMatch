package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSanitizerTest {

    private final AuditSanitizer sanitizer = new AuditSanitizer(new ObjectMapper());

    @Test
    void masksSecretsAndFinancialIdentifiers() {
        String raw = "password=secret token:abc123 手机13812345678 身份证320101199001011234 "
                + "账号622200001234 邮箱alice@example.com";

        String sanitized = sanitizer.sanitizeQuestion(raw);

        assertFalse(sanitized.contains("secret"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("13812345678"));
        assertFalse(sanitized.contains("320101199001011234"));
        assertFalse(sanitized.contains("622200001234"));
        assertFalse(sanitized.contains("alice@example.com"));
        assertTrue(sanitized.contains("138****5678"));
        assertTrue(sanitized.contains("320101********1234"));
        assertTrue(sanitized.contains("6222****1234"));
    }

    @Test
    void completelyMasksBearerAuthorizationCredentials() {
        String header = "Authorization: Bearer eyJhbGciOiJIUzI1Ni.secret.signature";
        String json = "{\"authorization\":\"Bearer json-secret-token\"}";
        String quotedAssignment = "Authorization = 'Bearer assignment-secret'";
        String mixedCase = "aUtHoRiZaTiOn: bEaReR mixed-case-secret";

        String sanitizedHeader = sanitizer.sanitizeQuestion(header);
        String sanitizedJson = sanitizer.sanitizeQuestion(json);

        assertEquals("Authorization: ***", sanitizedHeader);
        assertEquals("{\"authorization\":\"***\"}", sanitizedJson);
        assertEquals("Authorization = '***'", sanitizer.sanitizeQuestion(quotedAssignment));
        assertEquals("aUtHoRiZaTiOn: ***", sanitizer.sanitizeQuestion(mixedCase));
        assertFalse(sanitizedHeader.contains("eyJhbGciOiJIUzI1Ni"));
        assertFalse(sanitizedHeader.contains("signature"));
        assertFalse(sanitizedJson.contains("json-secret-token"));
    }

    @Test
    void completelyMasksQuotedJsonSecretFields() {
        String raw = "{\"password\":\"p@ss word\",\"token\":\"token.value,with-delimiter\","
                + "\"access_token\":\"access-secret\","
                + "\"cookie\":\"SESSION=abc123; theme=dark\"}";

        String sanitized = sanitizer.sanitizeQuestion(raw);

        assertEquals("{\"password\":\"***\",\"token\":\"***\",\"access_token\":\"***\","
                + "\"cookie\":\"***\"}", sanitized);
        assertFalse(sanitized.contains("p@ss word"));
        assertFalse(sanitized.contains("token.value"));
        assertFalse(sanitized.contains("access-secret"));
        assertFalse(sanitized.contains("SESSION=abc123"));
        assertFalse(sanitized.contains("theme=dark"));
    }

    @Test
    void masksChineseSecretFieldNamesWithoutMojibake() {
        String raw = "密码：bank-secret 口令='phrase with spaces' 令牌是cn-token";

        String sanitized = sanitizer.sanitizeQuestion(raw);

        assertEquals("密码：*** 口令='***' 令牌是***", sanitized);
        assertFalse(sanitized.contains("bank-secret"));
        assertFalse(sanitized.contains("phrase with spaces"));
        assertFalse(sanitized.contains("cn-token"));
    }

    @Test
    void storesOnlySqlTypeAndIrreversibleDigest() {
        String sql = "-- generated\n SELECT account_no FROM account WHERE customer_id = 7";

        assertEquals("SELECT", sanitizer.detectSqlType(sql));
        assertEquals(64, sanitizer.digest(sql).length());
        assertNotEquals(sql, sanitizer.digest(sql));
        assertEquals("DML", sanitizer.detectSqlType("update account set balance = 0"));
        assertEquals("DDL", sanitizer.detectSqlType("drop table account"));
    }

    @Test
    void dropsUnapprovedMetadataAndSanitizesApprovedValues() {
        String json = sanitizer.safeMetadataJson(Map.of("rowCount", 3, "maskedFields",
                List.of("account_no"), "sql", "select secret", "token", "raw-token", "stage",
                "账号622200001234", "recommendedChart", "BAR", "selectedChart", "TABLE"));

        assertTrue(json.contains("rowCount"));
        assertTrue(json.contains("maskedFields"));
        assertTrue(json.contains("\"recommendedChart\":\"BAR\""));
        assertTrue(json.contains("\"selectedChart\":\"TABLE\""));
        assertFalse(json.contains("select secret"));
        assertFalse(json.contains("raw-token"));
        assertFalse(json.contains("622200001234"));
    }
}
