package com.tencent.supersonic.headless.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvaluationReportService {

    static final String QA01A_FILE = "QA-01A_ACCEPTANCE_REPORT.json";
    static final String QA01B_FILE = "QA-01B_ACCEPTANCE_REPORT.json";
    static final String QA02A_FILE = "QA-02A_ACCEPTANCE_REPORT.json";
    static final String QA02B_FILE = "QA-02B_ACCEPTANCE_REPORT.json";
    private static final long MAX_REPORT_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Path reportDirectory;

    public EvaluationReportService(ObjectMapper objectMapper,
            @Value("${s2.evaluation.report-dir:task}") String reportDirectory) {
        this.objectMapper = objectMapper;
        this.reportDirectory = Path.of(reportDirectory).toAbsolutePath().normalize();
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> reports = new LinkedHashMap<>();
        putIfAvailable(reports, "qa01a", QA01A_FILE, "QA-01A");
        putIfAvailable(reports, "qa01b", QA01B_FILE, "QA-01B");
        putIfAvailable(reports, "qa02a", QA02A_FILE, "QA-02A");
        putIfAvailable(reports, "qa02b", QA02B_FILE, "QA-02B");

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("schemaVersion", "1.0");
        dashboard.put("status", reports.size() == 4 ? "READY" : "PARTIAL");
        dashboard.put("availableReportCount", reports.size());
        dashboard.put("reports", reports);
        return dashboard;
    }

    private void putIfAvailable(Map<String, Object> reports, String key, String fileName,
            String expectedTask) {
        Path reportPath = reportDirectory.resolve(fileName).normalize();
        if (!reportPath.startsWith(reportDirectory)
                || !Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            long size = Files.size(reportPath);
            if (size <= 0 || size > MAX_REPORT_BYTES) {
                throw new IllegalStateException("Evaluation report size is invalid");
            }
            try (InputStream input = Files.newInputStream(reportPath)) {
                Map<String, Object> report =
                        objectMapper.readValue(input, new TypeReference<>() {});
                if (!expectedTask.equals(report.get("task"))) {
                    throw new IllegalStateException("Evaluation report task is invalid");
                }
                reports.put(key, report);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Evaluation report cannot be read");
        }
    }
}
