package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface SecurityAlertMapper extends BaseMapper<SecurityAlertDO> {

    @Update("UPDATE s2_security_alert SET status = #{targetStatus}, "
            + "version = #{nextVersion}, updated_at = #{updatedAt}, "
            + "updated_by = #{updatedBy} WHERE id = #{id} AND version = #{currentVersion}")
    int transitionStatus(@Param("id") Long id, @Param("currentVersion") Integer currentVersion,
            @Param("nextVersion") Integer nextVersion, @Param("targetStatus") String targetStatus,
            @Param("updatedAt") Date updatedAt, @Param("updatedBy") String updatedBy);

    @Update("UPDATE s2_security_alert SET occurrence_count = #{occurrenceCount}, "
            + "last_seen = #{lastSeen}, evidence_ids = #{evidenceIds}, trace_id = #{traceId}, "
            + "version = #{nextVersion}, updated_at = #{updatedAt}, updated_by = #{updatedBy} "
            + "WHERE id = #{id} AND version = #{currentVersion}")
    int updateEvidenceCas(@Param("id") Long id, @Param("currentVersion") Integer currentVersion,
            @Param("nextVersion") Integer nextVersion,
            @Param("occurrenceCount") Long occurrenceCount, @Param("lastSeen") Date lastSeen,
            @Param("evidenceIds") String evidenceIds, @Param("traceId") String traceId,
            @Param("updatedAt") Date updatedAt, @Param("updatedBy") String updatedBy);
}
