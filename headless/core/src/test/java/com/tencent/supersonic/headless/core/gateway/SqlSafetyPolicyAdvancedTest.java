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
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT account_seq.NEXTVAL FROM dual"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT \"account_seq\".\"NEXTVAL\" FROM dual"));
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("SELECT NEXT VALUE FOR account_seq"));
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
        assertDangerousFunctionRejected("SELECT 1 FROM (\"pg_ls_dir\"('/tmp')) files LIMIT 1");
        assertDangerousFunctionRejected(
                "SELECT 1 FROM (safe_table s JOIN other_table o " + "ON \"sleep\"(1) = 0) LIMIT 1");
        assertDangerousFunctionRejected("SELECT 1 LIMIT sleep(1)");
        assertDangerousFunctionRejected("SELECT DISTINCT ON (\"pg_sleep\"(1)) 1");
        assertDangerousFunctionRejected("SELECT TOP (\"sleep\"(1)) 1");
        assertDangerousFunctionRejected(
                "SELECT 1 FROM dual START WITH \"sleep\"(1) = 0 CONNECT BY 1 = 0");
        assertDangerousFunctionRejected("SELECT row_number() OVER w FROM bank_account "
                + "WINDOW w AS (PARTITION BY \"sleep\"(1))");
        assertDangerousFunctionRejected("SELECT * FROM bank_account "
                + "PIVOT (\"sleep\"(balance) FOR branch_id IN (1)) p LIMIT 1");
        assertDangerousFunctionRejected(
                "SELECT * FROM bank_account " + "LATERAL VIEW \"sleep\"(balance) t AS x LIMIT 1");
    }

    @Test
    void rejectsDangerousFunctionsInsideValuesSelects() {
        assertDangerousFunctionRejected("VALUES (\"pg_read_file\"('/etc/passwd'))");
        assertDangerousFunctionRejected("WITH leaked AS (VALUES (\"pg_read_file\"('/etc/passwd'))) "
                + "SELECT * FROM leaked LIMIT 1");
        assertDangerousFunctionRejected(
                "SELECT 1 WHERE EXISTS (VALUES (\"pg_read_file\"('/etc/passwd')))");
    }

    @Test
    void rejectsUnboundedTableStatementsButAllowsConstantValues() {
        assertThrows(SqlPolicyViolationException.class,
                () -> policy.validate("TABLE bank_account"));
        assertDoesNotThrow(() -> policy.validate("VALUES (1, 'safe'), (2, 'constant')"));
    }

    @Test
    void rejectsDuckDbFileInspectionFunctions() {
        assertDangerousFunctionRejected("SELECT read_text('/etc/passwd')");
        assertDangerousFunctionRejected(
                "SELECT * FROM read_parquet('/var/lib/bank/accounts.parquet') LIMIT 1");
        assertDangerousFunctionRejected(
                "SELECT * FROM read_csv_auto('C:/bank/customers.csv') LIMIT 1");
        assertDangerousFunctionRejected("SELECT * FROM glob('/var/lib/bank/*') LIMIT 1");
    }

    @Test
    void rejectsCrossDialectExternalDataFunctions() {
        assertDangerousFunctionRejected("SELECT * FROM CSVREAD('C:/bank/customers.csv') LIMIT 1");
        assertDangerousFunctionRejected("SELECT readfile('/etc/passwd')");
        assertDangerousFunctionRejected(
                "SELECT * FROM dblink('bank_remote', 'SELECT secret FROM customer') "
                        + "AS remote(secret varchar) LIMIT 1");
        assertDangerousFunctionRejected(
                "SELECT * FROM read_xlsx('C:/bank/customers.xlsx') LIMIT 1");
    }

    @Test
    void rejectsConfiguredDatabaseSpecificFunctions() {
        SqlSafetyPolicy configured =
                new SqlSafetyPolicy(10_000, "bank_audit_write, utility.remote_call");

        SqlPolicyViolationException direct = assertThrows(SqlPolicyViolationException.class,
                () -> configured.validate("SELECT \"bank_audit_write\"('secret')"));
        SqlPolicyViolationException qualified = assertThrows(SqlPolicyViolationException.class,
                () -> configured.validate("SELECT utility.\"remote_call\"('endpoint')"));

        assertTrue(direct.getMessage().contains("bank_audit_write"));
        assertTrue(qualified.getMessage().contains("remote_call"));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlSafetyPolicy(10_000, "unsafe-function()"));
    }

    @Test
    void rejectsExcessivelyNestedSqlBeforePhysicalExecution() {
        String nested = "SELECT 1";
        for (int depth = 0; depth < 20; depth++) {
            nested = "SELECT 1 FROM (" + nested + ") nested_" + depth;
        }
        String sql = nested;

        assertThrows(SqlPolicyViolationException.class, () -> policy.validate(sql));
    }

    @Test
    void rejectsInvalidParserTimeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new SqlSafetyPolicy(10_000, "", 16, 0));
    }

    private void assertDangerousFunctionRejected(String sql) {
        SqlPolicyViolationException violation =
                assertThrows(SqlPolicyViolationException.class, () -> policy.validate(sql));
        assertTrue(violation.getMessage().contains("Dangerous SQL function"));
    }
}
