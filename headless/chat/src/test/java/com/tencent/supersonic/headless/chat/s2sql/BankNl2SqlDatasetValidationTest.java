package com.tencent.supersonic.headless.chat.s2sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlValidator;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class BankNl2SqlDatasetValidationTest {

    @Test
    void validatesAtLeastNinetyPercentOfFrozenDataset() throws IOException {
        Path datasetDir = findDatasetDirectory();
        LLMReq.LLMSchema schema = bankSchema();
        ComplexSqlValidator validator = new ComplexSqlValidator();
        int total = 0;
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (String split : List.of("train", "dev", "test")) {
            for (String line : Files.readAllLines(datasetDir.resolve(split + ".jsonl"))) {
                JsonNode sample = JsonUtil.INSTANCE.getObjectMapper().readTree(line);
                total++;
                var result = validator.validate(sample.get("s2sql").asText(), schema,
                        sample.get("question").asText());
                if (Boolean.TRUE.equals(result.getEvaluation().getIsValidated())) {
                    passed++;
                } else {
                    failures.add(sample.get("id").asText() + ": "
                            + result.getEvaluation().getValidateMsg());
                }
            }
        }
        Assert.assertEquals(96, total);
        Assert.assertTrue("success rate=" + passed / (double) total + ", failures=" + failures,
                passed / (double) total >= 0.90);
        Assert.assertEquals("frozen DATA-02 regressions: " + failures, total, passed);
    }

    private Path findDatasetDirectory() {
        String configured = System.getProperty("bank.nl2sql.dataset.dir");
        if (configured != null) {
            return Path.of(configured);
        }
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("evaluation/bank_nl2sql");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("evaluation/bank_nl2sql directory not found");
    }

    private LLMReq.LLMSchema bankSchema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetName("bank_indicator_dataset");
        List<SchemaElement> metrics = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            String code = String.format("zb%03d", i);
            metrics.add(SchemaElement.builder().name(code).bizName(code).build());
        }
        schema.setMetrics(metrics);
        schema.setDimensions(List.of(
                SchemaElement.builder().name("bank_data_date").bizName("bank_data_date").build(),
                SchemaElement.builder().name("bank_organization").bizName("bank_organization")
                        .build()));
        return schema;
    }
}
