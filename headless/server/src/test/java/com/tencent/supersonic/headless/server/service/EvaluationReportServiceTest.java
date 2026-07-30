package com.tencent.supersonic.headless.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationReportServiceTest {

    @TempDir
    Path reportDirectory;

    @Test
    void loadsOnlyTheFixedEvaluationReports() throws Exception {
        Files.writeString(reportDirectory.resolve(EvaluationReportService.QA01A_FILE),
                "{\"task\":\"QA-01A\",\"status\":\"PASS\"}");
        Files.writeString(reportDirectory.resolve(EvaluationReportService.QA01B_FILE),
                "{\"task\":\"QA-01B\",\"releaseDecision\":\"ALLOW\"}");
        Files.writeString(reportDirectory.resolve(EvaluationReportService.QA02A_FILE),
                "{\"task\":\"QA-02A\",\"status\":\"PASS\"}");
        Files.writeString(reportDirectory.resolve(EvaluationReportService.QA02B_FILE),
                "{\"task\":\"QA-02B\",\"status\":\"PASS\"}");
        Files.writeString(reportDirectory.resolve("arbitrary.json"),
                "{\"task\":\"SECRET\",\"value\":\"must-not-load\"}");

        Map<String, Object> dashboard =
                new EvaluationReportService(new ObjectMapper(), reportDirectory.toString())
                        .dashboard();
        Map<?, ?> reports = (Map<?, ?>) dashboard.get("reports");

        assertEquals("READY", dashboard.get("status"));
        assertEquals(4, dashboard.get("availableReportCount"));
        assertEquals("QA-01A", ((Map<?, ?>) reports.get("qa01a")).get("task"));
        assertEquals("QA-01B", ((Map<?, ?>) reports.get("qa01b")).get("task"));
        assertEquals("QA-02A", ((Map<?, ?>) reports.get("qa02a")).get("task"));
        assertEquals("QA-02B", ((Map<?, ?>) reports.get("qa02b")).get("task"));
        assertEquals(4, reports.size());
    }

    @Test
    void reportsPartialStatusWhenFilesAreNotGenerated() {
        Map<String, Object> dashboard =
                new EvaluationReportService(new ObjectMapper(), reportDirectory.toString())
                        .dashboard();

        assertEquals("PARTIAL", dashboard.get("status"));
        assertEquals(0, dashboard.get("availableReportCount"));
    }

    @Test
    void rejectsAReportWithTheWrongTaskIdentity() throws Exception {
        Files.writeString(reportDirectory.resolve(EvaluationReportService.QA01A_FILE),
                "{\"task\":\"QA-01B\",\"status\":\"PASS\"}");

        EvaluationReportService service =
                new EvaluationReportService(new ObjectMapper(), reportDirectory.toString());

        assertThrows(IllegalStateException.class, service::dashboard);
    }
}
