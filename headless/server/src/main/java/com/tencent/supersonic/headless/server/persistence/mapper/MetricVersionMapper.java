package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricVersionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricVersionMapper extends BaseMapper<MetricVersionDO> {
}
