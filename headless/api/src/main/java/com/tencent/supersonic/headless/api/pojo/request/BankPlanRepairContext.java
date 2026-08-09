package com.tencent.supersonic.headless.api.pojo.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Internal, sanitized context used to repair one bank plan within the same user question. */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class BankPlanRepairContext implements Serializable {
    private String toolResultJson;
    private String previousPlanJson;
}
