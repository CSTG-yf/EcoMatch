package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;

/** Extension point for delivering newly detected security alerts. */
public interface SecurityAlertNotifier {

    void notify(SecurityAlertDO alert);
}
