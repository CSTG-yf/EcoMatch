package com.tencent.supersonic.headless.server.security.audit;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Getter
@Component
public class AuditProperties {

    private final boolean enabled;
    private final ZoneId zoneId;
    private final int maximumPageSize;
    private final int maximumTraceEvents;

    public AuditProperties(@Value("${s2.security.audit.enabled:true}") boolean enabled,
            @Value("${s2.security.audit.zone-id:Asia/Shanghai}") String zoneId,
            @Value("${s2.security.audit.maximum-page-size:100}") int maximumPageSize,
            @Value("${s2.security.audit.maximum-trace-events:500}") int maximumTraceEvents) {
        this.enabled = enabled;
        this.zoneId = ZoneId.of(zoneId);
        this.maximumPageSize = Math.max(1, maximumPageSize);
        this.maximumTraceEvents = Math.max(1, maximumTraceEvents);
    }
}
