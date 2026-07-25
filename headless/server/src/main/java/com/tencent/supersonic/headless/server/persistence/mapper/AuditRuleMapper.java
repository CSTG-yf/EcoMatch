package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuditRuleMapper extends BaseMapper<AuditRuleDO> {

    @Update("UPDATE s2_audit_rule SET rule_name = #{rule.ruleName}, "
            + "rule_type = #{rule.ruleType}, threshold_value = #{rule.thresholdValue}, "
            + "window_seconds = #{rule.windowSeconds}, "
            + "work_hours_start = #{rule.workHoursStart}, work_hours_end = #{rule.workHoursEnd}, "
            + "severity = #{rule.severity}, enabled = #{rule.enabled}, "
            + "config_json = #{rule.configJson}, updated_at = #{rule.updatedAt}, "
            + "updated_by = #{rule.updatedBy}, version = version + 1 "
            + "WHERE id = #{rule.id} AND version = #{expectedVersion}")
    int compareAndSet(@Param("rule") AuditRuleDO rule,
            @Param("expectedVersion") Integer expectedVersion);
}
