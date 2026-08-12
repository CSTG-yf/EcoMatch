package com.tencent.supersonic.headless.api.pojo.request;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Internal, sanitized context used to repair one bank plan within the same user question. */
@Data
@NoArgsConstructor
public class BankPlanRepairContext implements Serializable {
    private String toolResultJson;
    private String previousPlanJson;
    private String previousRequirementsJson;

    public static BankPlanRepairContext of(String toolResultJson, String previousPlanJson) {
        return of(toolResultJson, previousPlanJson, null);
    }

    public static BankPlanRepairContext of(String toolResultJson, String previousPlanJson,
            String previousRequirementsJson) {
        BankPlanRepairContext context = new BankPlanRepairContext();
        context.setToolResultJson(toolResultJson);
        context.setPreviousPlanJson(previousPlanJson);
        context.setPreviousRequirementsJson(previousRequirementsJson);
        return context;
    }
}
