package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFreeSqlPromptComposerTest {

    @Test
    void detectsBankSchemaAndSelectsBankPrompt() {
        String bankSchema =
                "DatabaseType=[h2], Table=[银行指标], Metrics=[<各项存款余额>], Dimensions=[<bank_organization>,<bank_data_date>]";
        assertTrue(BankFreeSqlPromptComposer.isBankSchema(bankSchema));
        assertEquals(BankFreeSqlPromptComposer.BANK_FREE_SQL_INSTRUCTION,
                BankFreeSqlPromptComposer.selectPromptTemplate(bankSchema, "generic"));
    }

    @Test
    void detectsBankByBizNameEvenWhenDisplayNamesAreChinese() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build(),
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build()));
        assertTrue(BankFreeSqlPromptComposer.isBankSchema(schema));
        assertFalse(BankFreeSqlPromptComposer.isBankSchema(
                "DatabaseType=[h2], Table=[银行], Dimensions=[<机构>,<数据日期>]"));
    }

    @Test
    void nonBankKeepsDefaultPrompt() {
        String generic =
                "DatabaseType=[mysql], Table=[访问统计], Metrics=[<pv>], Dimensions=[<sys_imp_date>]";
        assertFalse(BankFreeSqlPromptComposer.isBankSchema(generic));
        assertEquals("generic",
                BankFreeSqlPromptComposer.selectPromptTemplate(generic, "generic"));
    }

    @Test
    void fixedSystemHasNoDynamicPlaceholdersAndForbidsLongTable() {
        String system = BankFreeSqlPromptComposer.FIXED_SYSTEM_PREFIX;
        assertFalse(system.contains("{{exemplar}}"));
        assertFalse(system.contains("{{question}}"));
        assertTrue(system.contains("禁止"));
        assertTrue(system.contains("各项存款余额"));
        assertTrue(system.contains("指标"));
        assertTrue(BankFreeSqlPromptComposer.PROMPT_VERSION.contains("v7"));
    }

    @Test
    void freeFallbackPrefixRejectsUnfilteredBranchesAndRequiresExplicitDatePredicates() {
        String prefix = BankFreeSqlPromptComposer.FREE_FALLBACK_SYSTEM_PREFIX;
        // Branches without their own filter translate into unbounded SELECT * scans that the
        // execution-layer safety policy rejects; the rule must be stated up front so the first
        // candidate already carries per-branch predicates.
        assertTrue(prefix.contains("每个 CTE 与子查询分支都必须自带过滤条件"));
        assertTrue(prefix.contains("执行层安全策略中一律被拒绝"));
        assertTrue(prefix.contains("日期谓词必须显式写在最外层查询与每个涉及数据表的分支内"));
        assertTrue(BankFreeSqlPromptComposer.FREE_FALLBACK_PROMPT_VERSION.contains("v2"));
    }

    @Test
    void legacyDynamicUserRejectsSchemaInUserTurn() {
        assertThrows(IllegalArgumentException.class,
                () -> BankFreeSqlPromptComposer.buildDynamicUserContent("", "存款是多少", "SchemaX",
                        "SideY"));
        String user = BankFreeSqlPromptComposer.buildDynamicUserContent("", "存款是多少", "", "SideY");
        assertTrue(user.contains("存款是多少"));
        assertTrue(user.contains("SideY"));
        assertFalse(user.contains("SchemaX"));
        assertFalse(user.contains("Metrics="));
    }

    @Test
    void questionOnlyUserRejectsCatalogDumps() {
        assertThrows(IllegalArgumentException.class,
                () -> BankFreeSqlPromptComposer.buildQuestionOnlyUserContent("q",
                        "Metrics=[<各项存款余额>], Dimensions=[<bank_organization>]", ""));
    }

    @Test
    void schemaGoesIntoSystemPrefixUserIsQuestionOnly() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetName("银行日指标数据集");
        schema.setDatabaseType("h2");
        schema.setMetrics(List.of(SchemaElement.builder().name("各项存款余额").bizName("ZB001").build()));
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build(),
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build()));
        String stable = BankFreeSqlPromptComposer.buildStableSchemaBlock(schema);
        assertTrue(stable.contains("各项存款余额"));
        assertTrue(stable.contains("机构"));
        assertFalse(stable.contains("Values="));

        String system = BankFreeSqlPromptComposer.composeSystemPrefix(stable);
        assertTrue(system.startsWith(BankFreeSqlPromptComposer.FIXED_SYSTEM_PREFIX));
        assertTrue(system.contains("语义目录"));
        assertTrue(system.contains("各项存款余额"));

        String user = BankFreeSqlPromptComposer.buildQuestionOnlyUserContent("存款是多少",
                "CurrentDate=[2026-08-07]", "Values=[<机构='ORG001'>]");
        assertTrue(user.contains("存款是多少"));
        assertTrue(user.contains("CurrentDate") || user.contains("附加信息"));
        assertTrue(user.contains("ORG001"));
        assertFalse(user.contains("各项存款余额"));
        assertFalse(user.contains("Metrics="));
        assertTrue(BankFreeSqlPromptComposer.prefixVersion(stable)
                .startsWith(BankFreeSqlPromptComposer.PROMPT_VERSION + ":"));
    }

    @Test
    void extractSqlFromJsonAndFence() {
        assertEquals("SELECT 1",
                BankFreeSqlPromptComposer.extractSql("{\"thought\":\"t\",\"sql\":\"SELECT 1\"}"));
        assertEquals("SELECT 2",
                BankFreeSqlPromptComposer.extractSql("here\n```sql\nSELECT 2\n```\n"));
        assertEquals("SELECT 3", BankFreeSqlPromptComposer.extractSql("SELECT 3"));
    }

    @Test
    void detectsInvalidLongTableStyleSql() {
        assertTrue(BankFreeSqlPromptComposer.looksInvalidBankS2Sql(
                "SELECT 指标, 值 FROM t WHERE 指标 = 'ZB013'"));
        assertTrue(BankFreeSqlPromptComposer
                .looksInvalidBankS2Sql("SELECT `ZB005`*100/NULLIF(`ZB002`,0) FROM t"));
        assertTrue(BankFreeSqlPromptComposer.looksInvalidBankS2Sql(
                "SELECT metric_value FROM bank_metric_daily WHERE metric_code='ZB001'"));
        assertFalse(BankFreeSqlPromptComposer.looksInvalidBankS2Sql(
                "SELECT `不良贷款率` FROM `银行日指标数据集` WHERE `数据日期`='2026-03-31' AND `机构`='ORG005'"));
        assertFalse(BankFreeSqlPromptComposer.looksInvalidBankS2Sql(
                "SELECT `各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-06-15' AND `机构`='ORG001'"));
    }
}
