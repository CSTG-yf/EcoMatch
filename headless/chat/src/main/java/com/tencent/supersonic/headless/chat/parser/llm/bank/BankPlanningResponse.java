package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The single model response for one bank question.
 *
 * <p>The model declares user-visible requirements and the executable semantic plan in one strict
 * JSON object. They remain separate nested contracts so existing validators can check that the
 * plan preserves the model's own interpretation without a second normal model call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BankPlanningResponse {

    /** Strict schema used by constrained decoding for the one-pass planning response. */
    public static final String JSON_SCHEMA = buildJsonSchema();

    private BankRequestContract requirements;
    private BankQueryPlan plan;

    private static String buildJsonSchema() {
        String planSchema = BankQueryPlan.JSON_SCHEMA;
        String objectType = "\"type\":\"object\"";
        int objectTypeIndex = planSchema.indexOf(objectType);
        if (objectTypeIndex < 0) {
            throw new IllegalStateException("bank query plan schema must declare an object root");
        }
        String nullablePlanSchema = planSchema.substring(0, objectTypeIndex)
                + "\"type\":[\"object\",\"null\"]"
                + planSchema.substring(objectTypeIndex + objectType.length());
        return """
                {"type":"object","additionalProperties":false,
                "required":["requirements","plan"],"properties":{
                "requirements":%s,"plan":%s}}
                """.formatted(BankRequestContract.JSON_SCHEMA, nullablePlanSchema).strip();
    }
}
