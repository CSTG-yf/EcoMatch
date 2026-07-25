package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AlertActionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AlertActionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SecurityAlertMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertActionType;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDetail;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDispositionRequest;
import com.tencent.supersonic.headless.server.security.audit.model.AlertStatus;
import com.tencent.supersonic.headless.server.security.audit.model.PageResult;
import com.tencent.supersonic.headless.server.security.audit.model.SecurityAlertQuery;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityAlertService {

    private static final int MAXIMUM_EVIDENCE_IDS = 50;
    private static final int MAXIMUM_CAS_ATTEMPTS = 5;
    private static final Map<AlertStatus, EnumSet<AlertStatus>> ALLOWED_TRANSITIONS =
            Map.of(AlertStatus.NEW,
                    EnumSet.of(AlertStatus.ACKNOWLEDGED, AlertStatus.CLOSED, AlertStatus.DISMISSED),
                    AlertStatus.ACKNOWLEDGED,
                    EnumSet.of(AlertStatus.RESOLVED, AlertStatus.CLOSED, AlertStatus.DISMISSED),
                    AlertStatus.RESOLVED, EnumSet.of(AlertStatus.CLOSED));

    private final SecurityAlertMapper securityAlertMapper;
    private final AlertActionMapper alertActionMapper;
    private final AuditEventService auditEventService;
    private final AuditProperties properties;
    private final AuditSanitizer sanitizer;
    private final SecurityAlertMutationService mutationService;

    public SecurityAlertService(SecurityAlertMapper securityAlertMapper,
            AlertActionMapper alertActionMapper, AuditEventService auditEventService,
            AuditProperties properties, AuditSanitizer sanitizer,
            SecurityAlertMutationService mutationService) {
        this.securityAlertMapper = securityAlertMapper;
        this.alertActionMapper = alertActionMapper;
        this.auditEventService = auditEventService;
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.mutationService = mutationService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SecurityAlertDO upsert(AuditRuleDO rule, AuditEventDO event, String fingerprint,
            String title, String description, long occurrenceCount) {
        SecurityAlertDO existing = findByFingerprint(fingerprint);
        if (existing != null) {
            return updateEvidence(existing, event, occurrenceCount);
        }
        Date now = event.getEventTime() == null ? new Date() : event.getEventTime();
        SecurityAlertDO alert = new SecurityAlertDO();
        alert.setAlertId(UUID.randomUUID().toString().replace("-", ""));
        alert.setFingerprint(fingerprint);
        alert.setRuleId(rule.getId());
        alert.setRuleCode(rule.getRuleCode());
        alert.setTraceId(event.getTraceId());
        alert.setUserName(event.getUserName());
        alert.setOrganizationId(event.getOrganizationId());
        alert.setResourceType(event.getResourceType());
        alert.setResourceId(event.getResourceId());
        alert.setSeverity(rule.getSeverity());
        alert.setStatus(AlertStatus.NEW.name());
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setEvidenceIds(event.getEventId());
        alert.setOccurrenceCount(Math.max(1, occurrenceCount));
        alert.setFirstSeen(now);
        alert.setLastSeen(now);
        alert.setVersion(0);
        alert.setCreatedAt(new Date());
        alert.setCreatedBy("system");
        alert.setUpdatedAt(new Date());
        alert.setUpdatedBy("system");
        try {
            mutationService.insert(alert);
            return alert;
        } catch (DuplicateKeyException concurrentInsert) {
            existing = findByFingerprint(fingerprint);
            if (existing == null) {
                throw concurrentInsert;
            }
            return updateEvidence(existing, event, occurrenceCount);
        }
    }

    public PageResult<SecurityAlertDO> page(SecurityAlertQuery query) {
        long current = Math.max(1, query.getCurrent());
        long pageSize = Math.min(Math.max(1, query.getPageSize()), properties.getMaximumPageSize());
        long total = securityAlertMapper.selectCount(buildQuery(query));
        if (total == 0) {
            return new PageResult<>(List.of(), current, pageSize, 0);
        }
        long maximumPage = (total - 1) / pageSize + 1;
        if (current > maximumPage) {
            return new PageResult<>(List.of(), current, pageSize, total);
        }
        long offset = Math.max(0, (current - 1) * pageSize);
        LambdaQueryWrapper<SecurityAlertDO> wrapper = buildQuery(query)
                .orderByDesc(SecurityAlertDO::getLastSeen).orderByDesc(SecurityAlertDO::getId)
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        return new PageResult<>(securityAlertMapper.selectList(wrapper), current, pageSize, total);
    }

    public AlertDetail detail(String alertId) {
        SecurityAlertDO alert = requireAlert(alertId);
        List<String> evidenceIds = splitEvidence(alert.getEvidenceIds());
        List<AuditEventDO> evidence = auditEventService.findByEventIds(evidenceIds);
        List<AlertActionDO> actions = alertActionMapper.selectList(
                new LambdaQueryWrapper<AlertActionDO>().eq(AlertActionDO::getAlertId, alertId)
                        .orderByAsc(AlertActionDO::getCreatedAt).orderByAsc(AlertActionDO::getId));
        return new AlertDetail(alert, evidence, actions);
    }

    public SecurityAlertDO getByAlertId(String alertId) {
        return requireAlert(alertId);
    }

    @Transactional
    public SecurityAlertDO transition(String alertId, AlertDispositionRequest request,
            String operator) {
        if (request == null || request.getStatus() == null) {
            throw new IllegalArgumentException("Target alert status is required");
        }
        if (request.getVersion() == null) {
            throw new IllegalArgumentException("Current alert version is required");
        }
        SecurityAlertDO existing = requireAlert(alertId);
        AlertStatus current = AlertStatus.valueOf(existing.getStatus());
        AlertStatus target = request.getStatus();
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(AlertStatus.class))
                .contains(target)) {
            throw new IllegalArgumentException(
                    "Alert status cannot transition from " + current + " to " + target);
        }
        if (target != AlertStatus.ACKNOWLEDGED && StringUtils.isBlank(request.getComment())) {
            throw new IllegalArgumentException("A disposition comment is required");
        }
        if (!request.getVersion().equals(existing.getVersion())) {
            throw new IllegalStateException("Alert was modified by another operator");
        }
        Date now = new Date();
        int updated = securityAlertMapper.transitionStatus(existing.getId(), existing.getVersion(),
                existing.getVersion() + 1, target.name(), now, operator);
        if (updated != 1) {
            throw new IllegalStateException("Alert was modified by another operator");
        }
        AlertActionDO action = new AlertActionDO();
        action.setActionId(UUID.randomUUID().toString().replace("-", ""));
        action.setAlertId(alertId);
        action.setFromStatus(current.name());
        action.setToStatus(target.name());
        action.setAction(actionFor(target).name());
        action.setOperatorName(operator);
        action.setComment(
                StringUtils.abbreviate(sanitizer.sanitizeQuestion(request.getComment()), 2000));
        action.setCreatedAt(now);
        alertActionMapper.insert(action);
        return requireAlert(alertId);
    }

    private SecurityAlertDO updateEvidence(SecurityAlertDO existing, AuditEventDO event,
            long occurrenceCount) {
        for (int attempt = 0; attempt < MAXIMUM_CAS_ATTEMPTS; attempt++) {
            int currentVersion = existing.getVersion() == null ? 0 : existing.getVersion();
            long currentCount =
                    existing.getOccurrenceCount() == null ? 0 : existing.getOccurrenceCount();
            boolean newEvidence =
                    !splitEvidence(existing.getEvidenceIds()).contains(event.getEventId());
            String evidence = appendEvidence(existing.getEvidenceIds(), event.getEventId());
            Date now = new Date();
            long nextCount = newEvidence ? saturatedIncrement(currentCount) : currentCount;
            int updated = mutationService.updateEvidence(existing.getId(), currentVersion,
                    currentVersion + 1, Math.max(nextCount, occurrenceCount),
                    event.getEventTime() == null ? now : event.getEventTime(), evidence,
                    event.getTraceId(), now);
            if (updated == 1) {
                return requireAlert(existing.getAlertId());
            }
            existing = findByFingerprint(existing.getFingerprint());
            if (existing == null) {
                throw new IllegalStateException("Security alert disappeared during update");
            }
        }
        throw new IllegalStateException("Security alert was modified too frequently");
    }

    private long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private LambdaQueryWrapper<SecurityAlertDO> buildQuery(SecurityAlertQuery query) {
        return new LambdaQueryWrapper<SecurityAlertDO>()
                .eq(StringUtils.isNotBlank(query.getRuleCode()), SecurityAlertDO::getRuleCode,
                        query.getRuleCode())
                .eq(query.getSeverity() != null, SecurityAlertDO::getSeverity,
                        query.getSeverity() == null ? null : query.getSeverity().name())
                .eq(query.getStatus() != null, SecurityAlertDO::getStatus,
                        query.getStatus() == null ? null : query.getStatus().name())
                .eq(StringUtils.isNotBlank(query.getUserName()), SecurityAlertDO::getUserName,
                        query.getUserName())
                .eq(StringUtils.isNotBlank(query.getOrganizationId()),
                        SecurityAlertDO::getOrganizationId, query.getOrganizationId())
                .ge(query.getStartTime() != null, SecurityAlertDO::getLastSeen,
                        query.getStartTime() == null ? null : new Date(query.getStartTime()))
                .le(query.getEndTime() != null, SecurityAlertDO::getLastSeen,
                        query.getEndTime() == null ? null : new Date(query.getEndTime()));
    }

    private SecurityAlertDO findByFingerprint(String fingerprint) {
        return securityAlertMapper.selectOne(new LambdaQueryWrapper<SecurityAlertDO>()
                .eq(SecurityAlertDO::getFingerprint, fingerprint).last("LIMIT 1"));
    }

    private SecurityAlertDO requireAlert(String alertId) {
        SecurityAlertDO alert =
                securityAlertMapper.selectOne(new LambdaQueryWrapper<SecurityAlertDO>()
                        .eq(SecurityAlertDO::getAlertId, alertId).last("LIMIT 1"));
        if (alert == null) {
            throw new IllegalArgumentException("Security alert does not exist");
        }
        return alert;
    }

    private String appendEvidence(String current, String eventId) {
        List<String> values = new java.util.ArrayList<>(splitEvidence(current));
        if (!values.contains(eventId)) {
            values.add(eventId);
        }
        int from = Math.max(0, values.size() - MAXIMUM_EVIDENCE_IDS);
        return String.join(",", values.subList(from, values.size()));
    }

    private List<String> splitEvidence(String evidenceIds) {
        if (StringUtils.isBlank(evidenceIds)) {
            return List.of();
        }
        return Arrays.stream(evidenceIds.split(",")).filter(StringUtils::isNotBlank)
                .limit(MAXIMUM_EVIDENCE_IDS).toList();
    }

    private AlertActionType actionFor(AlertStatus target) {
        return switch (target) {
            case ACKNOWLEDGED -> AlertActionType.ACKNOWLEDGE;
            case RESOLVED -> AlertActionType.RESOLVE;
            case CLOSED -> AlertActionType.CLOSE;
            case DISMISSED -> AlertActionType.DISMISS;
            default -> throw new IllegalArgumentException("Unsupported alert action");
        };
    }
}
