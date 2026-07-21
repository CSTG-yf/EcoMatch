package com.tencent.supersonic.headless.server.pojo.bank;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BankSemanticImportReport {

    private boolean success;

    private boolean dryRun;

    private String fileName;

    private String checksum;

    private Long modelId;

    private Long dataSetId;

    private String minDate;

    private String maxDate;

    private int organizationCount;

    private int indicatorCount;

    private int derivedRuleCount;

    private int factCount;

    private int questionCount;

    private Map<String, Integer> questionTypeCounts = new LinkedHashMap<>();

    private Map<String, Integer> difficultyCounts = new LinkedHashMap<>();

    private Map<String, Integer> created = new LinkedHashMap<>();

    private Map<String, Integer> updated = new LinkedHashMap<>();

    private Map<String, Integer> skipped = new LinkedHashMap<>();

    private List<BankImportError> errors = new ArrayList<>();

    public void increment(Map<String, Integer> counters, String type) {
        counters.merge(type, 1, Integer::sum);
    }
}
