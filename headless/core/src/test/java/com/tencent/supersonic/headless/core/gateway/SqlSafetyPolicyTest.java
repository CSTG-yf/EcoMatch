package com.tencent.supersonic.headless.core.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSafetyPolicyTest {

    private final SqlSafetyPolicy policy = new SqlSafetyPolicy(10000);

    @Test
    void acceptsBoundedReadOnlyQuery() {
        assertDoesNotThrow(
                () -> policy.validate("SELECT customer_id, balance FROM account LIMIT 100"));
    }

    @Test
    void rejectsWriteAndMultipleStatements() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("DELETE FROM account"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT 1; SELECT 2"));
    }

    @Test
    void rejectsDangerousAndUnboundedQueries() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_sleep(10)"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT * FROM account"));
    }
}
