package com.tencent.supersonic.headless.server.service.bank;

import com.tencent.supersonic.headless.server.pojo.bank.BankImportError;
import com.tencent.supersonic.headless.server.pojo.bank.BankWorkbookData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BankWorkbookParser {

    static final String ORGANIZATION_SHEET = "机构信息表";
    static final String INDICATOR_SHEET = "指标清单表";
    static final String DERIVED_RULE_SHEET = "衍生维度说明";
    static final String FACT_SHEET = "指标数据表";
    static final String QUESTION_SHEET = "问题答案清单";

    private static final int MAX_ERRORS = 500;

    private final ThreadLocal<DataFormatter> formatter =
            ThreadLocal.withInitial(() -> new DataFormatter(Locale.ROOT));

    public BankWorkbookData parse(byte[] content, String fileName) {
        BankWorkbookData data = new BankWorkbookData();
        data.setFileName(fileName);
        data.setChecksum(sha256(content));
        if (content.length == 0) {
            addError(data, "workbook", 0, "file", "EMPTY_FILE", "导入文件为空", "");
            return data;
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet organizationSheet = requireSheet(workbook, ORGANIZATION_SHEET, data);
            Sheet indicatorSheet = requireSheet(workbook, INDICATOR_SHEET, data);
            Sheet derivedRuleSheet = requireSheet(workbook, DERIVED_RULE_SHEET, data);
            Sheet factSheet = requireSheet(workbook, FACT_SHEET, data);
            Sheet questionSheet = requireSheet(workbook, QUESTION_SHEET, data);
            if (!data.getErrors().isEmpty()) {
                return data;
            }

            parseOrganizations(organizationSheet, data);
            parseIndicators(indicatorSheet, data);
            parseDerivedRules(derivedRuleSheet, data);
            parseFacts(factSheet, data);
            parseQuestions(questionSheet, data);
        } catch (Exception e) {
            addError(data, "workbook", 0, "file", "INVALID_WORKBOOK",
                    "无法读取 Excel 文件: " + rootMessage(e), fileName);
        }
        return data;
    }

    private void parseOrganizations(Sheet sheet, BankWorkbookData data) {
        String[] headers = {"机构编号", "机构名称"};
        if (!validateHeaders(sheet, headers, data)) {
            return;
        }
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, headers.length)) {
                continue;
            }
            String code = text(row, 0);
            String name = text(row, 1);
            requireValue(data, sheet, i, headers[0], code);
            requireValue(data, sheet, i, headers[1], name);
            if (!code.isEmpty() && !codes.add(code)) {
                addError(data, sheet.getSheetName(), i + 1, headers[0], "DUPLICATE_ORGANIZATION",
                        "机构编号重复", code);
            }
            if (!name.isEmpty() && !names.add(name)) {
                addError(data, sheet.getSheetName(), i + 1, headers[1],
                        "DUPLICATE_ORGANIZATION_NAME", "机构名称重复", name);
            }
            if (!code.isEmpty() && !name.isEmpty()) {
                data.getOrganizations().add(new BankWorkbookData.Organization(code, name));
            }
        }
        if (data.getOrganizations().isEmpty()) {
            addError(data, sheet.getSheetName(), 0, "", "NO_ORGANIZATION", "机构清单不能为空", "");
        }
    }

    private void parseIndicators(Sheet sheet, BankWorkbookData data) {
        String[] headers = {"指标编号", "指标名称", "指标含义", "指标单位"};
        if (!validateHeaders(sheet, headers, data)) {
            return;
        }
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, headers.length)) {
                continue;
            }
            String code = text(row, 0);
            String name = text(row, 1);
            String description = text(row, 2);
            String unit = text(row, 3);
            requireValue(data, sheet, i, headers[0], code);
            requireValue(data, sheet, i, headers[1], name);
            requireValue(data, sheet, i, headers[2], description);
            requireValue(data, sheet, i, headers[3], unit);
            if (!code.isEmpty() && !codes.add(code)) {
                addError(data, sheet.getSheetName(), i + 1, headers[0], "DUPLICATE_INDICATOR",
                        "指标编号重复", code);
            }
            if (!name.isEmpty() && !names.add(name)) {
                addError(data, sheet.getSheetName(), i + 1, headers[1], "DUPLICATE_INDICATOR_NAME",
                        "指标名称重复", name);
            }
            if (!code.isEmpty() && !name.isEmpty()) {
                data.getIndicators()
                        .add(new BankWorkbookData.Indicator(code, name, description, unit));
            }
        }
        if (data.getIndicators().isEmpty()) {
            addError(data, sheet.getSheetName(), 0, "", "NO_INDICATOR", "指标清单不能为空", "");
        }
    }

    private void parseDerivedRules(Sheet sheet, BankWorkbookData data) {
        String[] headers = {"衍生维度", "衍生口径说明"};
        if (!validateHeaders(sheet, headers, data)) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, headers.length)) {
                continue;
            }
            String name = text(row, 0);
            String description = text(row, 1);
            requireValue(data, sheet, i, headers[0], name);
            requireValue(data, sheet, i, headers[1], description);
            if (!name.isEmpty() && !names.add(name)) {
                addError(data, sheet.getSheetName(), i + 1, headers[0], "DUPLICATE_DERIVED_RULE",
                        "衍生维度重复", name);
            }
            if (!name.isEmpty() && !description.isEmpty()) {
                data.getDerivedRules().add(new BankWorkbookData.DerivedRule(name, description));
            }
        }
    }

    private void parseFacts(Sheet sheet, BankWorkbookData data) {
        String[] headers = {"数据日期", "指标编号", "指标名称", "机构编号", "指标值"};
        if (!validateHeaders(sheet, headers, data)) {
            return;
        }
        Map<String, String> indicators = new LinkedHashMap<>();
        data.getIndicators().forEach(item -> indicators.put(item.getCode(), item.getName()));
        Set<String> organizations = new HashSet<>();
        data.getOrganizations().forEach(item -> organizations.add(item.getCode()));
        Set<String> factKeys = new HashSet<>();
        Set<String> dates = new HashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, headers.length)) {
                continue;
            }
            String date = date(row == null ? null : row.getCell(0));
            String indicatorCode = text(row, 1);
            String indicatorName = text(row, 2);
            String organizationCode = text(row, 3);
            String value = text(row, 4);
            requireValue(data, sheet, i, headers[0], date);
            requireValue(data, sheet, i, headers[1], indicatorCode);
            requireValue(data, sheet, i, headers[2], indicatorName);
            requireValue(data, sheet, i, headers[3], organizationCode);
            requireValue(data, sheet, i, headers[4], value);

            if (!date.isEmpty()) {
                try {
                    LocalDate.parse(date);
                    dates.add(date);
                } catch (DateTimeParseException e) {
                    addError(data, sheet.getSheetName(), i + 1, headers[0], "INVALID_DATE",
                            "日期必须为 yyyy-MM-dd", date);
                }
            }
            if (!indicatorCode.isEmpty() && !indicators.containsKey(indicatorCode)) {
                addError(data, sheet.getSheetName(), i + 1, headers[1], "UNKNOWN_INDICATOR",
                        "指标编号不在指标清单中", indicatorCode);
            } else if (!indicatorCode.isEmpty()
                    && !indicators.get(indicatorCode).equals(indicatorName)) {
                addError(data, sheet.getSheetName(), i + 1, headers[2], "INDICATOR_NAME_MISMATCH",
                        "指标名称与指标清单不一致", indicatorName);
            }
            if (!organizationCode.isEmpty() && !organizations.contains(organizationCode)) {
                addError(data, sheet.getSheetName(), i + 1, headers[3], "UNKNOWN_ORGANIZATION",
                        "机构编号不在机构清单中", organizationCode);
            }
            if (!value.isEmpty()) {
                try {
                    new BigDecimal(value.replace(",", ""));
                } catch (NumberFormatException e) {
                    addError(data, sheet.getSheetName(), i + 1, headers[4], "INVALID_NUMBER",
                            "指标值必须为数值", value);
                }
            }
            String key = date + "\u0000" + indicatorCode + "\u0000" + organizationCode;
            if (!date.isEmpty() && !indicatorCode.isEmpty() && !organizationCode.isEmpty()
                    && !factKeys.add(key)) {
                addError(data, sheet.getSheetName(), i + 1, "数据日期+指标编号+机构编号", "DUPLICATE_FACT",
                        "指标事实联合键重复", key.replace('\u0000', '/'));
            }
            data.setFactCount(data.getFactCount() + 1);
        }

        if (!dates.isEmpty()) {
            data.setMinDate(dates.stream().min(String::compareTo).orElse(null));
            data.setMaxDate(dates.stream().max(String::compareTo).orElse(null));
            long expected = (long) dates.size() * indicators.size() * organizations.size();
            if (data.getFactCount() != expected) {
                addError(data, sheet.getSheetName(), 0, "", "INCOMPLETE_FACT_CUBE",
                        "指标数据不是完整的 日期×指标×机构 数据立方体，期望 " + expected + " 行，实际 " + data.getFactCount()
                                + " 行",
                        "");
            }
        }
    }

    private void parseQuestions(Sheet sheet, BankWorkbookData data) {
        String[] headers = {"问题编号", "问题类型", "问题难度", "问题描述", "问题结果"};
        if (!validateHeaders(sheet, headers, data)) {
            return;
        }
        Set<String> questionIds = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, headers.length)) {
                continue;
            }
            for (int column = 0; column < headers.length; column++) {
                requireValue(data, sheet, i, headers[column], text(row, column));
            }
            String id = text(row, 0);
            if (!id.isEmpty() && !questionIds.add(id)) {
                addError(data, sheet.getSheetName(), i + 1, headers[0], "DUPLICATE_QUESTION",
                        "问题编号重复", id);
            }
            data.getQuestionTypeCounts().merge(text(row, 1), 1, Integer::sum);
            data.getDifficultyCounts().merge(text(row, 2), 1, Integer::sum);
            data.setQuestionCount(data.getQuestionCount() + 1);
        }
    }

    private Sheet requireSheet(Workbook workbook, String name, BankWorkbookData data) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            addError(data, name, 0, "", "MISSING_SHEET", "缺少工作表", name);
        }
        return sheet;
    }

    private boolean validateHeaders(Sheet sheet, String[] expected, BankWorkbookData data) {
        Row header = sheet.getRow(0);
        boolean valid = true;
        for (int i = 0; i < expected.length; i++) {
            String actual = text(header, i);
            if (!expected[i].equals(actual)) {
                addError(data, sheet.getSheetName(), 1, expected[i], "INVALID_HEADER",
                        "表头不匹配，期望 " + expected[i], actual);
                valid = false;
            }
        }
        return valid;
    }

    private void requireValue(BankWorkbookData data, Sheet sheet, int zeroBasedRow, String column,
            String value) {
        if (value.isEmpty()) {
            addError(data, sheet.getSheetName(), zeroBasedRow + 1, column, "REQUIRED_VALUE",
                    "必填值为空", "");
        }
    }

    private boolean isBlank(Row row, int columns) {
        if (row == null) {
            return true;
        }
        for (int i = 0; i < columns; i++) {
            if (!text(row, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String text(Row row, int column) {
        return row == null ? "" : text(row.getCell(column));
    }

    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellTypeEnum() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros()
                    .toPlainString();
        }
        return formatter.get().formatCellValue(cell).trim();
    }

    private String date(Cell cell) {
        if (cell != null && cell.getCellTypeEnum() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    .toString();
        }
        return text(cell);
    }

    private void addError(BankWorkbookData data, String sheet, int row, String column, String code,
            String message, String value) {
        if (data.getErrors().size() < MAX_ERRORS) {
            String safeValue = value == null ? "" : value;
            if (safeValue.length() > 200) {
                safeValue = safeValue.substring(0, 200);
            }
            data.getErrors().add(new BankImportError(sheet, row, column, code, message, safeValue));
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String rootMessage(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
