package com.tencent.supersonic.headless.server.security;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.config.ParameterConfig;
import com.tencent.supersonic.common.pojo.Parameter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service("MaskingParameterConfig")
public class MaskingParameterConfig extends ParameterConfig {

    private static final String MODULE_NAME = "数据安全策略";

    public static final Parameter RAW_USERS = new Parameter("s2.security.masking.raw-users", "",
            "原值访问用户", "可查看敏感字段原值的用户名，多个值使用英文逗号分隔", "string", MODULE_NAME);

    public static final Parameter RAW_ROLES = new Parameter("s2.security.masking.raw-roles", "",
            "原值访问角色", "可查看敏感字段原值的角色，多个值使用英文逗号分隔", "string", MODULE_NAME);

    public static final Parameter FIELD_STRATEGIES = new Parameter(
            "s2.security.masking.field-strategies", "", "字段脱敏策略",
            "格式为 field=FULL,account_no=LAST4；支持 FULL、LAST4、FIRST_LAST", "longText", MODULE_NAME);

    @Override
    public List<Parameter> getSysParameters() {
        return Lists.newArrayList(RAW_USERS, RAW_ROLES, FIELD_STRATEGIES);
    }

    public String rawUsers() {
        return getParameterValue(RAW_USERS);
    }

    public String rawRoles() {
        return getParameterValue(RAW_ROLES);
    }

    public String fieldStrategies() {
        return getParameterValue(FIELD_STRATEGIES);
    }
}
