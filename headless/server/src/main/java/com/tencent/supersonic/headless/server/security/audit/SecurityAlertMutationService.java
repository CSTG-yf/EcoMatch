package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SecurityAlertMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** Executes each alert upsert mutation in an isolated transaction. */
@Service
public class SecurityAlertMutationService {

    private final SecurityAlertMapper securityAlertMapper;

    public SecurityAlertMutationService(SecurityAlertMapper securityAlertMapper) {
        this.securityAlertMapper = securityAlertMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int insert(SecurityAlertDO alert) {
        return securityAlertMapper.insert(alert);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int updateEvidence(Long id, Integer currentVersion, Integer nextVersion,
            Long occurrenceCount, Date lastSeen, String evidenceIds, String traceId,
            Date updatedAt) {
        return securityAlertMapper.updateEvidenceCas(id, currentVersion, nextVersion,
                occurrenceCount, lastSeen, evidenceIds, traceId, updatedAt, "system");
    }
}
