package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

/** Strictly parses one model response into a validated {@link BankQueryPlan}. */
public class BankQueryPlanResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final BankQueryPlanValidator validator;

    public BankQueryPlanResponseParser() {
        this(new BankQueryPlanValidator());
    }

    public BankQueryPlanResponseParser(BankQueryPlanValidator validator) {
        this.validator = validator;
    }

    public BankQueryPlan parse(String modelOutput, SemanticIntentHints hints) {
        String json = unwrapJson(modelOutput);
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                        "model response must contain one JSON object");
            }
            BankQueryPlan plan = OBJECT_MAPPER.readValue(json, BankQueryPlan.class);
            BankQueryPlanValidator.ValidationResult validation = validator.validate(plan, hints);
            if (!validation.isValid()) {
                throw new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.VALIDATION_FAILED, validation.summary());
            }
            return plan;
        } catch (BankQueryPlanParseException exception) {
            throw exception;
        } catch (UnrecognizedPropertyException exception) {
            throw new BankQueryPlanParseException(
                    BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "model response contains an unsupported plan property", exception);
        } catch (JsonProcessingException exception) {
            throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model response is not complete strict JSON", exception);
        }
    }

    private String unwrapJson(String modelOutput) {
        String response = StringUtils.trimToEmpty(modelOutput);
        if (response.startsWith("```")) {
            int lineEnd = response.indexOf('\n');
            if (lineEnd < 0 || !response.endsWith("```")) {
                throw new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.MALFORMED_JSON,
                        "unterminated JSON code fence");
            }
            String language = response.substring(3, lineEnd).trim();
            if (StringUtils.isNotBlank(language) && !"json".equalsIgnoreCase(language)) {
                throw new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                        "code fence must declare JSON");
            }
            response = response.substring(lineEnd + 1, response.length() - 3).trim();
        }
        if (StringUtils.isBlank(response)) {
            throw new BankQueryPlanParseException(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model response is empty");
        }
        return response;
    }
}
