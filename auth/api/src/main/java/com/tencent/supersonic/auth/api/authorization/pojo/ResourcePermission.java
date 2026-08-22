package com.tencent.supersonic.auth.api.authorization.pojo;

import lombok.Data;

/** Explicit field-level decision. modelId is populated when the policy is resolved. */
@Data
public class ResourcePermission {

    private Long modelId;
    private String resourceType;
    private String resourceName;
    private ColumnAccessMode accessMode = ColumnAccessMode.MASKED;
    private String maskingStrategy;
}
