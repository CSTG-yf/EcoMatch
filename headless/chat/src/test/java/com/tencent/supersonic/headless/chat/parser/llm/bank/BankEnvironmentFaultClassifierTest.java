package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankEnvironmentFaultClassifierTest {

    @Test
    void flagsAuthKeyAndQuotaOutages() {
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault("ENVIRONMENT_FAULT", null));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(null,
                "Error 401: Invalid API key provided"));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(null,
                "AuthError: invalid_api_key for https://api.example.com/v1"));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(null,
                "429 Too Many Requests: rate limit exceeded, insufficient_quota"));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(null,
                "Provider returned http status 503: Service Unavailable"));
    }

    @Test
    void flagsTransportFailuresInCauseChains() {
        RuntimeException wrapped = new RuntimeException(
                "chat model call failed",
                new SocketTimeoutException("Read timed out after 300000 ms"));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(wrapped));
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(
                new IOException(new java.net.ConnectException("Connection refused"))));
    }

    @Test
    void keepsSemanticAndDatabaseErrorsRepairable() {
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault("JDBC_GRAMMAR",
                "Column \"enabled\" not found in table s2_auth_groups"));
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault("RESULT_CONTRACT_MISMATCH",
                "result shape deviates from plan contract"));
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault((String) null, null));

        RuntimeException semantic = new RuntimeException(
                "CalciteContextException: No match found for function rank_over");
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault(semantic),
                "Calcite issues stay repairable through plan regeneration");
    }

    @Test
    void plainNumbersWithoutHttpCueDoNotTriggerTheStatusRule() {
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault(null,
                "limit must be 429 but was 401 rows"));
    }
}
