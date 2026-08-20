package com.tencent.supersonic.auth.api.authorization.response;

import com.tencent.supersonic.auth.api.authorization.pojo.AuthRes;
import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.pojo.ResourcePermission;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class AuthorizedResourceResp {

    private List<AuthRes> authResList = new ArrayList<>();

    private List<DimensionFilter> filters = new ArrayList<>();

    private List<ResourcePermission> resourcePermissions = new ArrayList<>();

    private Set<Integer> matchedGroupIds = new LinkedHashSet<>();

    private long policyVersion;
}
