package com.tencent.supersonic.headless.server.pojo.bank;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BankWorkbookData {

    private String fileName;

    private String checksum;

    private List<Organization> organizations = new ArrayList<>();

    private List<Indicator> indicators = new ArrayList<>();

    private List<DerivedRule> derivedRules = new ArrayList<>();

    private int factCount;

    private int questionCount;

    private String minDate;

    private String maxDate;

    private Map<String, Integer> questionTypeCounts = new LinkedHashMap<>();

    private Map<String, Integer> difficultyCounts = new LinkedHashMap<>();

    private List<BankImportError> errors = new ArrayList<>();

    @Data
    @AllArgsConstructor
    public static class Organization {
        private String code;
        private String name;
    }

    @Data
    @AllArgsConstructor
    public static class Indicator {
        private String code;
        private String name;
        private String description;
        private String unit;
    }

    @Data
    @AllArgsConstructor
    public static class DerivedRule {
        private String name;
        private String description;
    }
}
