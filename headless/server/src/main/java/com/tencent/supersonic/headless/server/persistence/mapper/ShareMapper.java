package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.ShareDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ShareMapper extends BaseMapper<ShareDO> {

    @Update("UPDATE s2_share SET access_count = access_count + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND expires_at > #{now} "
            + "AND (max_access_count IS NULL OR access_count < max_access_count)")
    int incrementAccess(Long id, Date now);
}
