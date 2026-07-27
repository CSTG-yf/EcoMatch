package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.TraceIdUtil;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventQuery;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.security.audit.model.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditEventService {

    private static final int MAXIMUM_APPEND_ATTEMPTS = 5;
    private static final Object[] CHAIN_LOCKS = new Object[64];
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");

    static {
        Arrays.setAll(CHAIN_LOCKS, ignored -> new Object());
    }

    private final AuditEventMapper auditEventMapper;
    private final AuditSanitizer sanitizer;
    private final AuditProperties properties;
    private final AuditEventMutationService mutationService;

    public AuditEventService(AuditEventMapper auditEventMapper, AuditSanitizer sanitizer,
            AuditProperties properties, AuditEventMutationService mutationService) {
        this.auditEventMapper = auditEventMapper;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.mutationService = mutationService;
    }

    public AuditEventDO persist(AuditEvent event, User user) {
        if (event == null || event.getEventType() == null) {
            throw new IllegalArgumentException("Audit event type is required");
        }
        Date now = canonicalTimestamp(new Date());
        AuditEventDO dataObject = new AuditEventDO();
        dataObject.setEventId(UUID.randomUUID().toString().replace("-", ""));
        dataObject.setTraceId(resolveTraceId(event));
        dataObject.setChatId(event.getChatId());
        dataObject.setQueryId(event.getQueryId());
        dataObject.setUserName(resolveUserName(event, user));
        dataObject.setOrganizationId(resolveOrganization(event, user));
        dataObject.setEventType(event.getEventType().name());
        dataObject.setResourceType(sanitizer.safeLabel(event.getResourceType(), 64));
        dataObject.setResourceId(sanitizer.safeLabel(event.getResourceId()));
        AuditOutcome outcome =
                event.getOutcome() == null ? AuditOutcome.UNKNOWN : event.getOutcome();
        dataObject.setOutcome(outcome.name());
        dataObject.setReasonCode(sanitizer.safeLabel(event.getReasonCode(), 64));
        dataObject.setSanitizedQuestion(sanitizer.sanitizeQuestion(event.getRawQuestion()));
        dataObject.setQuestionHash(sanitizer.digest(event.getRawQuestion()));
        dataObject.setMetricCodes(sanitizer.joinSafe(event.getMetricCodes()));
        dataObject.setSqlType(sanitizer.detectSqlType(event.getRawSql()));
        dataObject.setSqlDigest(sanitizer.digest(event.getRawSql()));
        dataObject.setPolicyIds(sanitizer.joinSafe(event.getPolicyIds()));
        dataObject.setMaskingSummary(sanitizer.safeLabel(event.getMaskingSummary()));
        dataObject.setExportRowCount(nonNegative(event.getExportRowCount()));
        dataObject.setFileType(sanitizer.safeLabel(event.getFileType(), 64));
        dataObject.setFileSize(nonNegative(event.getFileSize()));
        dataObject.setDurationMs(nonNegative(event.getDurationMs()));
        dataObject.setMetadataJson(sanitizer.safeMetadataJson(event.getMetadata()));
        dataObject.setEventTime(
                event.getEventTime() == null ? now : canonicalTimestamp(event.getEventTime()));
        applyRequestFingerprint(dataObject);
        dataObject.setCreatedAt(now);
        return appendWithRetry(dataObject);
    }

    public PageResult<AuditEventDO> page(AuditEventQuery query) {
        long current = Math.max(1, query.getCurrent());
        long pageSize = Math.min(Math.max(1, query.getPageSize()), properties.getMaximumPageSize());
        LambdaQueryWrapper<AuditEventDO> countWrapper = buildQuery(query);
        long total = auditEventMapper.selectCount(countWrapper);
        if (total == 0) {
            return new PageResult<>(List.of(), current, pageSize, 0);
        }
        long maximumPage = (total - 1) / pageSize + 1;
        if (current > maximumPage) {
            return new PageResult<>(List.of(), current, pageSize, total);
        }
        long offset = Math.max(0, (current - 1) * pageSize);
        LambdaQueryWrapper<AuditEventDO> dataWrapper = buildQuery(query)
                .orderByDesc(AuditEventDO::getEventTime).orderByDesc(AuditEventDO::getId)
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        List<AuditEventDO> events = auditEventMapper.selectList(dataWrapper);
        validateHashes(events);
        return new PageResult<>(events, current, pageSize, total);
    }

    public List<AuditEventDO> trace(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return List.of();
        }
        if (!TraceIdUtil.isValidTraceId(traceId)) {
            throw new IllegalArgumentException("Audit trace id format is invalid");
        }
        List<AuditEventDO> events =
                auditEventMapper.selectList(new LambdaQueryWrapper<AuditEventDO>()
                        .eq(AuditEventDO::getTraceId, traceId).orderByAsc(AuditEventDO::getId)
                        .last("LIMIT " + properties.getMaximumTraceEvents()));
        validateChain(events);
        return events;
    }

    public AuditEventDO getByEventId(String eventId) {
        AuditEventDO event = auditEventMapper.selectOne(new LambdaQueryWrapper<AuditEventDO>()
                .eq(AuditEventDO::getEventId, eventId).last("LIMIT 1"));
        validateHash(event);
        return event;
    }

    public List<AuditEventDO> findByEventIds(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        List<AuditEventDO> events =
                auditEventMapper.selectList(new LambdaQueryWrapper<AuditEventDO>()
                        .in(AuditEventDO::getEventId, new ArrayList<>(eventIds))
                        .orderByAsc(AuditEventDO::getEventTime));
        validateHashes(events);
        return events;
    }

    private LambdaQueryWrapper<AuditEventDO> buildQuery(AuditEventQuery query) {
        return new LambdaQueryWrapper<AuditEventDO>()
                .eq(StringUtils.isNotBlank(query.getTraceId()), AuditEventDO::getTraceId,
                        query.getTraceId())
                .eq(StringUtils.isNotBlank(query.getUserName()), AuditEventDO::getUserName,
                        query.getUserName())
                .eq(StringUtils.isNotBlank(query.getOrganizationId()),
                        AuditEventDO::getOrganizationId, query.getOrganizationId())
                .eq(query.getEventType() != null, AuditEventDO::getEventType,
                        query.getEventType() == null ? null : query.getEventType().name())
                .eq(query.getOutcome() != null, AuditEventDO::getOutcome,
                        query.getOutcome() == null ? null : query.getOutcome().name())
                .eq(StringUtils.isNotBlank(query.getResourceType()), AuditEventDO::getResourceType,
                        query.getResourceType())
                .eq(StringUtils.isNotBlank(query.getResourceId()), AuditEventDO::getResourceId,
                        query.getResourceId())
                .ge(query.getStartTime() != null, AuditEventDO::getEventTime,
                        query.getStartTime() == null ? null : new Date(query.getStartTime()))
                .le(query.getEndTime() != null, AuditEventDO::getEventTime,
                        query.getEndTime() == null ? null : new Date(query.getEndTime()));
    }

    private String resolveTraceId(AuditEvent event) {
        String traceId = StringUtils.defaultIfBlank(event.getTraceId(), TraceIdUtil.getTraceId());
        return TraceIdUtil.resolveTraceId(traceId);
    }

    private String resolveUserName(AuditEvent event, User user) {
        String name = StringUtils.defaultIfBlank(event.getUserName(),
                user == null ? null : user.getName());
        return StringUtils.defaultIfBlank(sanitizer.safeLabel(name, 128), "anonymous");
    }

    private String resolveOrganization(AuditEvent event, User user) {
        Map<String, String> attributes = user == null ? null : user.getAttributes();
        if (attributes != null) {
            String authenticatedOrganization = ORGANIZATION_ATTRIBUTE_KEYS.stream()
                    .map(attributes::get).filter(StringUtils::isNotBlank).findFirst()
                    .map(String::trim).map(value -> sanitizer.safeLabel(value, 128)).orElse(null);
            if (authenticatedOrganization != null) {
                return authenticatedOrganization;
            }
        }
        return sanitizer.safeLabel(StringUtils.trimToNull(event.getOrganizationId()), 128);
    }

    private void applyRequestFingerprint(AuditEventDO dataObject) {
        if (!(RequestContextHolder
                .getRequestAttributes()instanceof ServletRequestAttributes attributes)) {
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        dataObject.setClientIpHash(sanitizer.digest(request.getRemoteAddr()));
        dataObject.setUserAgentHash(sanitizer.digest(request.getHeader("User-Agent")));
    }

    private AuditEventDO appendWithRetry(AuditEventDO event) {
        int lockIndex = Math.floorMod(event.getTraceId().hashCode(), CHAIN_LOCKS.length);
        synchronized (CHAIN_LOCKS[lockIndex]) {
            DuplicateKeyException lastFailure = null;
            for (int attempt = 0; attempt < MAXIMUM_APPEND_ATTEMPTS; attempt++) {
                try {
                    return mutationService.append(event);
                } catch (DuplicateKeyException concurrentAppend) {
                    lastFailure = concurrentAppend;
                }
            }
            throw lastFailure == null ? new IllegalStateException("Audit event append failed")
                    : lastFailure;
        }
    }

    private void validateChain(List<AuditEventDO> events) {
        String expectedPrevious = AuditEventMutationService.ROOT_HASH;
        for (int index = 0; index < events.size(); index++) {
            AuditEventDO event = events.get(index);
            validateHash(event);
            if (index == 0 && event.getPreviousHash() == null) {
                expectedPrevious = null;
            }
            if (!Objects.equals(expectedPrevious, event.getPreviousHash())) {
                throw new IllegalStateException("Audit trace integrity check failed");
            }
            expectedPrevious = event.getEventHash();
        }
    }

    private void validateHashes(List<AuditEventDO> events) {
        if (events != null) {
            events.forEach(this::validateHash);
        }
    }

    private void validateHash(AuditEventDO event) {
        if (event != null && !mutationService.hasValidHash(event)) {
            throw new IllegalStateException("Audit event integrity check failed");
        }
    }

    private Long nonNegative(Long value) {
        return value == null ? null : Math.max(0, value);
    }

    private Date canonicalTimestamp(Date value) {
        return new Date((value.getTime() / 1000L) * 1000L);
    }
}
