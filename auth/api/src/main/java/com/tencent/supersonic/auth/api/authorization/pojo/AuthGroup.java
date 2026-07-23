package com.tencent.supersonic.auth.api.authorization.pojo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AuthGroup {

    private Long modelId;
    private String name;
    private Integer groupId;
    private List<AuthRule> authRules;
    /** row permission expression */
    private List<String> dimensionFilters;
    /** row permission expression description information */
    private String dimensionFilterDescription;

    private List<String> authorizedUsers;
    /** authorization Department Id */
    private List<String> authorizedDepartmentIds;

    /** RBAC role names, such as branch_manager or risk_auditor. */
    private List<String> authorizedRoles;

    /** ABAC conditions that must all match the user's attributes. */
    private Map<String, String> attributeConditions;
}
