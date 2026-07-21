package com.tencent.supersonic.headless.server.service.bank;

import com.tencent.supersonic.headless.server.pojo.bank.BankWorkbookData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BankWorkbookParserTest {

    private final BankWorkbookParser parser = new BankWorkbookParser();

    @Test
    void shouldParseCompetitionWorkbookStructure() throws Exception {
        BankWorkbookData data = parser.parse(workbook(false), "bank.xlsx");

        assertTrue(data.getErrors().isEmpty(), data.getErrors().toString());
        assertEquals(1, data.getOrganizations().size());
        assertEquals(1, data.getIndicators().size());
        assertEquals(1, data.getDerivedRules().size());
        assertEquals(1, data.getFactCount());
        assertEquals(1, data.getQuestionCount());
        assertEquals("2026-04-30", data.getMinDate());
        assertEquals("2026-04-30", data.getMaxDate());
        assertEquals(1, data.getQuestionTypeCounts().get("训练集"));
        assertFalse(data.getChecksum().isEmpty());
    }

    @Test
    void shouldLocateDuplicateFactRow() throws Exception {
        BankWorkbookData data = parser.parse(workbook(true), "bank.xlsx");

        assertTrue(data.getErrors().stream()
                .anyMatch(error -> "DUPLICATE_FACT".equals(error.getCode()) && error.getRow() == 3
                        && BankWorkbookParser.FACT_SHEET.equals(error.getSheet())));
    }

    @Test
    void shouldValidateProvidedCompetitionDatasetWhenConfigured() throws Exception {
        String dataSetPath = System.getProperty("bank.dataset.path");
        assumeTrue(dataSetPath != null && Files.isRegularFile(Path.of(dataSetPath)));

        BankWorkbookData data = parser.parse(Files.readAllBytes(Path.of(dataSetPath)),
                Path.of(dataSetPath).getFileName().toString());

        assertTrue(data.getErrors().isEmpty(), data.getErrors().toString());
        assertEquals(13, data.getOrganizations().size());
        assertEquals(21, data.getIndicators().size());
        assertEquals(10, data.getDerivedRules().size());
        assertEquals(132678, data.getFactCount());
        assertEquals(200, data.getQuestionCount());
        assertEquals("2024-12-31", data.getMinDate());
        assertEquals("2026-04-30", data.getMaxDate());
    }

    private byte[] workbook(boolean duplicateFact) throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet organizations = workbook.createSheet(BankWorkbookParser.ORGANIZATION_SHEET);
            row(organizations, 0, "机构编号", "机构名称");
            row(organizations, 1, "ORG001", "第一农商行");

            Sheet indicators = workbook.createSheet(BankWorkbookParser.INDICATOR_SHEET);
            row(indicators, 0, "指标编号", "指标名称", "指标含义", "指标单位");
            row(indicators, 1, "ZB001", "各项存款余额", "各项存款的期末余额", "亿元");

            Sheet rules = workbook.createSheet(BankWorkbookParser.DERIVED_RULE_SHEET);
            row(rules, 0, "衍生维度", "衍生口径说明");
            row(rules, 1, "较年初", "当日值减去年初值");

            Sheet facts = workbook.createSheet(BankWorkbookParser.FACT_SHEET);
            row(facts, 0, "数据日期", "指标编号", "指标名称", "机构编号", "指标值");
            row(facts, 1, "2026-04-30", "ZB001", "各项存款余额", "ORG001", 100.25);
            if (duplicateFact) {
                row(facts, 2, "2026-04-30", "ZB001", "各项存款余额", "ORG001", 101.25);
            }

            Sheet questions = workbook.createSheet(BankWorkbookParser.QUESTION_SHEET);
            row(questions, 0, "问题编号", "问题类型", "问题难度", "问题描述", "问题结果");
            row(questions, 1, "TRAIN-S-01", "训练集", "简单", "第一农商行存款余额是多少", "100.25亿元");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number) {
                row.createCell(i).setCellValue(((Number) values[i]).doubleValue());
            } else {
                row.createCell(i).setCellValue(String.valueOf(values[i]));
            }
        }
    }
}
