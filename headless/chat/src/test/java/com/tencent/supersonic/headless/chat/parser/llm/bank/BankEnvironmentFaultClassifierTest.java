package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void transientTransportFaultsIsolateTheReRollableSubset() {
        RuntimeException wrappedTimeout = new RuntimeException(
                "chat model call failed", new SocketTimeoutException("Read timed out after 300s"));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault(wrappedTimeout));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null,
                "Connection reset by peer"));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null,
                "Connection refused: api.example.com"));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null,
                "Provider returned http status 502: Bad Gateway"));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null,
                "Provider returned http status 503: Service Unavailable"));
        assertTrue(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null,
                "Provider returned http status 504"));
        assertEquals("timeout",
                BankEnvironmentFaultClassifier.transientTransportCategory(wrappedTimeout));
        // Transient transport faults stay inside the terminal environment bucket.
        assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(wrappedTimeout));
    }

    @Test
    void hardProviderFaultsAreNeverTransientAndStayTerminalEnvironmentFaults() {
        String[] hardFaults = {
                "Error 401: Invalid API key provided",
                "429 Too Many Requests: rate limit exceeded, insufficient_quota",
                "Provider returned http status 500: Internal Server Error",
                "Provider returned http status 403: permission denied"
        };
        for (String fault : hardFaults) {
            assertFalse(BankEnvironmentFaultClassifier.isTransientTransportFault(null, fault),
                    fault);
            assertTrue(BankEnvironmentFaultClassifier.isEnvironmentFault(null, fault), fault);
        }
    }

    @Test
    void ordinaryModelExceptionsAreNeitherEnvironmentFaultsNorTransient() {
        RuntimeException normal = new RuntimeException("model returned a non-executable plan shape");
        assertFalse(BankEnvironmentFaultClassifier.isEnvironmentFault(normal));
        assertFalse(BankEnvironmentFaultClassifier.isTransientTransportFault(normal));
        assertFalse(BankEnvironmentFaultClassifier.isTransientTransportFault((String) null, null));
    }
}
