package com.tencent.supersonic.headless.chat.s2sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.supersonic.common.util.JsonUtil;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BankNl2SqlDatasetValidationTest {

    @Test
    void validatesFrozenDatasetContract() throws Exception {
        Path datasetDir = findDatasetDirectory();
        int total = 0;
        Map<String, Integer> splitCounts = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (String split : List.of("train", "dev", "test")) {
            for (String line : Files.readAllLines(datasetDir.resolve(split + ".jsonl"))) {
                JsonNode sample = JsonUtil.INSTANCE.getObjectMapper().readTree(line);
                total++;
                splitCounts.merge(split, 1, Integer::sum);
                Assert.assertTrue("duplicate frozen DATA-02 id: " + sample.get("id").asText(),
                        ids.add(sample.get("id").asText()));
                Assert.assertEquals("EXECUTE", sample.get("expectedAction").asText());
                String sql = sample.get("sql").asText();
                String auditableTemplate = sample.get("s2sql").asText();
                Assert.assertFalse("blank SQL for " + sample.get("id").asText(), sql.isBlank());
                Assert.assertEquals("S2SQL template drift for " + sample.get("id").asText(), sql,
                        auditableTemplate);
                Assert.assertTrue("gold SQL is not a SELECT for " + sample.get("id").asText(),
                        CCJSqlParserUtil.parse(sql) instanceof Select);
            }
        }
        Assert.assertEquals(199, total);
        Assert.assertEquals(Map.of("train", 119, "dev", 40, "test", 40), splitCounts);
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

}
