package com.tencent.supersonic.auth.api.authorization.pojo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DimensionFilter {

    private Long modelId;
    private List<String> expressions = new ArrayList<>();
    private String description;

    /** ALLOW is the legacy/default value; DENY is supported by policy V2. */
    private PolicyEffect effect = PolicyEffect.ALLOW;

    /** Structured V2 rule; structured fields are validated against the semantic schema. */
    private boolean structured;
}
