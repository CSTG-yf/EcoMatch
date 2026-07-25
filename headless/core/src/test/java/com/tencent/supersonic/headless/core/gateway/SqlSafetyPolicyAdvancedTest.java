package com.tencent.supersonic.headless.core.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSafetyPolicyAdvancedTest {

    private final SqlSafetyPolicy policy = new SqlSafetyPolicy(10000);

    @Test
    void rejectsDangerousFunctionSeparatedByComment() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_sleep/**/(10)"));
    }

    @Test
    void rejectsUnboundedSelectAllInUnionBranch() {
        assertThrows(SqlPolicyViolationException.class, () -> policy.validate(
                "SELECT * FROM account WHERE branch_id = 1 UNION ALL SELECT * FROM account"));
    }

    @Test
    void rejectsUnboundedSelectAllInsideCte() {
        assertThrows(SqlPolicyViolationException.class, () -> policy.validate(
                "WITH raw AS (SELECT * FROM account) SELECT account_id FROM raw LIMIT 10"));
    }

    @Test
    void rejectsUnboundedSelectAllInsideNestedFilter() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT account_id FROM account WHERE account_id IN "
                        + "(SELECT * FROM blocked_account)"));
    }

    @Test
    void acceptsComplexReadOnlyQueryWhenEverySelectAllBranchIsBounded() {
        assertDoesNotThrow(() -> policy
                .validate("WITH recent AS (SELECT * FROM account WHERE data_date >= '2026-01-01') "
                        + "SELECT account_id FROM recent UNION ALL "
                        + "SELECT account_id FROM archive_account LIMIT 100"));
    }

    @Test
    void acceptsUnboundedProjectionOverBoundedDerivedResult() {
        assertDoesNotThrow(() -> policy
                .validate("WITH recent AS (SELECT * FROM account WHERE data_date >= '2026-01-01') "
                        + "SELECT * FROM recent"));
        assertDoesNotThrow(() -> policy
                .validate("SELECT * FROM (SELECT * FROM account WHERE branch_id = 1) filtered"));
    }

    @Test
    void rejectsDerivedResultJoinedWithUnboundedBaseTable() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate(
                        "WITH recent AS (SELECT * FROM account WHERE data_date >= '2026-01-01') "
                                + "SELECT * FROM recent JOIN customer "
                                + "ON recent.customer_id = customer.id"));
    }

    @Test
    void rejectsSelectIntoAndRowLockingVariants() {
        assertThrows(SqlPolicyViolationException.class, () -> policy.validate(
                "SELECT account_id INTO copied_account FROM account WHERE account_id = 1"));
        assertThrows(SqlPolicyViolationException.class, () -> policy
                .validate("SELECT account_id FROM account WHERE account_id = 1 FOR SHARE"));
    }

    @Test
    void rejectsStateChangingFunctionsInsideSelect() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT nextval('account_seq')"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT setval('account_seq', 1)"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_advisory_lock(1)"));
    }

    @Test
    void rejectsPostgresServerFileInspectionFunctions() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_read_binary_file('/etc/passwd')"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_ls_dir('/var/lib/postgresql')"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_stat_file('/etc/passwd')"));
    }

    @Test
    void rejectsQuotedQualifiedAndNonProjectionDangerousFunctions() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT \"pg_read_file\"('/etc/passwd')"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT pg_catalog.\"pg_read_file\"('/etc/passwd')"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT 1 WHERE pg_sleep(1) IS NULL"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT 1 FROM pg_ls_dir('/tmp') LIMIT 1"));
        assertDangerousFunctionRejected("SELECT 1 LIMIT sleep(1)");
        assertDangerousFunctionRejected("SELECT DISTINCT ON (\"pg_sleep\"(1)) 1");
        assertDangerousFunctionRejected("SELECT TOP (\"sleep\"(1)) 1");
        assertDangerousFunctionRejected(
                "SELECT 1 FROM dual START WITH \"sleep\"(1) = 0 CONNECT BY 1 = 0");
        assertDangerousFunctionRejected("SELECT row_number() OVER w FROM bank_account "
                + "WINDOW w AS (PARTITION BY \"sleep\"(1))");
    }

    private void assertDangerousFunctionRejected(String sql) {
        SqlPolicyViolationException violation =
                assertThrows(SqlPolicyViolationException.class, () -> policy.validate(sql));
        assertTrue(violation.getMessage().contains("Dangerous SQL function"));
    }
}
