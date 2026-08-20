package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Optional generic JSON webhook notifier. A blank URL keeps notifications station-only. */
@Component
@Slf4j
public class WebhookSecurityAlertNotifier implements SecurityAlertNotifier {

    private final RestClient restClient;
    private final String webhookUrl;

    public WebhookSecurityAlertNotifier(
            @Value("${s2.security.audit.alert-webhook:}") String webhookUrl) {
        this.restClient = RestClient.builder().build();
        this.webhookUrl = StringUtils.trimToEmpty(webhookUrl);
    }

    @Override
    public void notify(SecurityAlertDO alert) {
        if (alert == null || StringUtils.isBlank(webhookUrl)) {
            return;
        }
        if (!webhookUrl.startsWith("https://") && !webhookUrl.startsWith("http://")) {
            log.warn("Ignoring security alert webhook with unsupported scheme");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", alert.getAlertId());
        payload.put("ruleCode", alert.getRuleCode());
        payload.put("severity", alert.getSeverity());
        payload.put("status", alert.getStatus());
        payload.put("title", alert.getTitle());
        payload.put("description", alert.getDescription());
        payload.put("traceId", alert.getTraceId());
        payload.put("userName", alert.getUserName());
        payload.put("organizationId", alert.getOrganizationId());
        payload.put("resourceType", alert.getResourceType());
        payload.put("resourceId", alert.getResourceId());
        payload.put("occurrenceCount", alert.getOccurrenceCount());
        try {
            restClient.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // Alert persistence must not fail because an external notification endpoint is down.
            log.warn("Security alert webhook delivery failed: type={}",
                    e.getClass().getSimpleName());
        }
    }
}
