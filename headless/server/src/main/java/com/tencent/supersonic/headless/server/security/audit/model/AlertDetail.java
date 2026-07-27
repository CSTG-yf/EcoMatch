package com.tencent.supersonic.headless.server.security.audit.model;

import com.tencent.supersonic.headless.server.persistence.dataobject.AlertActionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;

import java.util.List;

public record AlertDetail(SecurityAlertDO alert, List<AuditEventDO> evidence,
        List<AlertActionDO> actions) {}
