package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

/** Strictly parses and cross-validates one complete model planning response. */
public final class BankPlanningResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final BankRequestContractResponseParser requirementsParser =
            new BankRequestContractResponseParser();
    private final BankQueryPlanResponseParser planParser = new BankQueryPlanResponseParser();

    /** Parses only the nested requirement contract so requirement errors win over plan errors. */
    public BankRequestContract parseRequirements(String modelOutput,
            SemanticIntentHints admissionHints) {
        JsonNode root = parseRoot(modelOutput);
        JsonNode requirementsNode = root.get("requirements");
        if (requirementsNode == null || !requirementsNode.isObject()) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "requirements must be one complete BankRequestContract object", null);
        }
        return requirementsParser.parse(requirementsNode.toString(), admissionHints);
    }

    public BankPlanningResponse parse(String modelOutput, SemanticIntentHints admissionHints) {
        JsonNode root = parseRoot(modelOutput);
        BankRequestContract requirements = parseRequirements(modelOutput, admissionHints);
        JsonNode planNode = root.get("plan");
        if (requirements.getAction() == BankRequestContract.Action.CLARIFY) {
            if (planNode != null && !planNode.isNull()) {
                throw failure(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                        "clarification responses must set plan to null", null);
            }
            return new BankPlanningResponse(requirements, null);
        }
        if (planNode == null || !planNode.isObject()) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "executable requirements require one complete BankQueryPlan object", null);
        }
        BankQueryPlan plan =
                planParser.parse(planNode.toString(), requirements.toPlanHints(admissionHints));
        return new BankPlanningResponse(requirements, plan);
    }

    private JsonNode parseRoot(String modelOutput) {
        String response = StringUtils.trimToEmpty(modelOutput);
        if (response.startsWith("```")) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "model response must be one raw JSON object without a code fence", null);
        }
        if (response.isBlank()) {
            throw failure(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model response is empty", null);
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.has("requirements") || !root.has("plan")) {
                throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                        "model response must contain exactly requirements and plan", null);
            }
            return root;
        } catch (BankQueryPlanParseException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw failure(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model response is not complete strict JSON", exception);
        }
    }

    private static BankQueryPlanParseException failure(
            BankQueryPlanParseException.Reason reason, String message, Throwable cause) {
        return cause == null ? new BankQueryPlanParseException(reason, message)
                : new BankQueryPlanParseException(reason, message, cause);
    }
}
