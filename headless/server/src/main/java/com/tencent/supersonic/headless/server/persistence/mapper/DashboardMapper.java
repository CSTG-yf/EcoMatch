package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.DashboardDO;
import org.apache.ibatis.annotations.Update;

public interface DashboardMapper extends BaseMapper<DashboardDO> {

    @Update("UPDATE s2_dashboard SET name = #{name}, description = #{description}, "
            + "status = #{status}, access_scope = #{accessScope}, "
            + "organization_id = #{organizationId}, config = #{config}, "
            + "published_at = #{publishedAt}, disabled_at = #{disabledAt}, "
            + "updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version}")
    int updateWithVersion(DashboardDO dashboard);
}
