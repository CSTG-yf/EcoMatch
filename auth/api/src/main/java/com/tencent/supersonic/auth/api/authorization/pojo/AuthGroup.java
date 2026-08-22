package com.tencent.supersonic.auth.api.authorization.pojo;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Date;

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

    /** V2 policy metadata. All fields are optional for backward compatibility. */
    private String policyCode;
    private Boolean enabled = true;
    private Integer priority = 0;
    private PolicyEffect effect = PolicyEffect.ALLOW;
    private Long policyVersion = 1L;
    private Date validFrom;
    private Date validTo;
    private OrgScopeType orgScope = OrgScopeType.CURRENT;
    private List<RowFilterRule> rowFilterRules = new ArrayList<>();
    private List<ResourcePermission> resourcePermissions = new ArrayList<>();
}
