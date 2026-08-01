package com.tencent.supersonic.headless.server.gateway;

import com.tencent.supersonic.headless.core.gateway.SqlPolicyViolationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedQueryEnvironmentConfigTest {

    @Test
    void explicitEnvironmentContractFailsWhenConfigurationIsMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(Map.of()));
    }

    @Test
    void acceptsTwoCatalogReadOnlyFederationContract() {
        FederatedQueryTargetEnvironmentIT.Be07Config config =
                FederatedQueryTargetEnvironmentIT.Be07Config.from(validEnvironment());

        assertEquals("core_a.bank", config.sourceA());
        assertEquals("core_b.crm", config.sourceB());
        assertEquals(10_000, config.maxResultRows());
    }

    @Test
    void rejectsSameCatalogAndMissingSourceReference() {
        Map<String, String> sameCatalog = validEnvironment();
        sameCatalog.put("BE07_SOURCE_B", "core_a.crm");
        assertThrows(IllegalArgumentException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(sameCatalog));

        Map<String, String> missingSource = validEnvironment();
        missingSource.put("BE07_FEDERATED_SQL", "SELECT a.customer_id FROM core_a.bank.accounts a");
        assertThrows(IllegalArgumentException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(missingSource));
    }

    @Test
    void rejectsMutatingOrUnboundedSql() {
        Map<String, String> mutating = validEnvironment();
        mutating.put("BE07_FEDERATED_SQL", "DELETE FROM core_a.bank.accounts");
        assertThrows(SqlPolicyViolationException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(mutating));

        Map<String, String> invalidLimit = validEnvironment();
        invalidLimit.put("BE07_MAX_RESULT_ROWS", "0");
        assertThrows(IllegalArgumentException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(invalidLimit));

        Map<String, String> contradictoryLimit = validEnvironment();
        contradictoryLimit.put("BE07_MAX_RESULT_ROWS", "10");
        contradictoryLimit.put("BE07_EXPECTED_MIN_ROWS", "11");
        assertThrows(IllegalArgumentException.class,
                () -> FederatedQueryTargetEnvironmentIT.Be07Config.from(contradictoryLimit));
    }

    private Map<String, String> validEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("BE07_JDBC_URL", "jdbc:trino://localhost:8443/system/runtime?SSL=true");
        env.put("BE07_SOURCE_A", "core_a.bank");
        env.put("BE07_SOURCE_B", "core_b.crm");
        env.put("BE07_FEDERATED_SQL",
                "SELECT a.customer_id, c.segment "
                        + "FROM core_a.bank.accounts a JOIN core_b.crm.customers c "
                        + "ON a.customer_id = c.customer_id");
        env.put("BE07_VALIDATION_SQL",
                "SELECT count(a.customer_id) "
                        + "FROM core_a.bank.accounts a JOIN core_b.crm.customers c "
                        + "ON a.customer_id = c.customer_id WHERE a.customer_id IS NULL");
        env.put("BE07_DENIED_JDBC_USER", "restricted-user");
        env.put("BE07_DENIED_SQL", "SELECT customer_id FROM core_a.bank.accounts");
        env.put("BE07_DENIED_ERROR_CONTAINS", "access denied|permission");
        env.put("BE07_RESOURCE_LIMIT_SQL", "SELECT a.customer_id "
                + "FROM core_a.bank.accounts a CROSS JOIN core_b.crm.customers c");
        env.put("BE07_RESOURCE_LIMIT_ERROR_CONTAINS", "resource|memory|time limit");
        env.put("BE07_EXPECTED_COLUMN_COUNT", "2");
        return env;
    }
}
