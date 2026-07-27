package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

/** Performs one append-only hash-chain mutation in an isolated transaction. */
@Service
public class AuditEventMutationService {

    static final String ROOT_HASH = DigestUtils.sha256Hex("S2_AUDIT_ROOT");

    private final AuditEventMapper auditEventMapper;

    public AuditEventMutationService(AuditEventMapper auditEventMapper) {
        this.auditEventMapper = auditEventMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventDO append(AuditEventDO event) {
        AuditEventDO previous = auditEventMapper.selectOne(new QueryWrapper<AuditEventDO>()
                .eq("trace_id", event.getTraceId()).orderByDesc("id").last("LIMIT 1"));
        event.setId(null);
        event.setPreviousHash(previous == null ? ROOT_HASH : previous.getEventHash());
        event.setEventHash(calculateHash(event));
        if (auditEventMapper.insert(event) != 1) {
            throw new IllegalStateException("Audit event was not persisted");
        }
        return event;
    }

    public boolean hasValidHash(AuditEventDO event) {
        if (event == null || StringUtils.isBlank(event.getEventHash())) {
            return false;
        }
        byte[] expected = calculateHash(event).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = event.getEventHash().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String calculateHash(AuditEventDO event) {
        StringBuilder canonical = new StringBuilder(1024);
        append(canonical, event.getPreviousHash());
        append(canonical, event.getEventId());
        append(canonical, event.getTraceId());
        append(canonical, event.getChatId());
        append(canonical, event.getQueryId());
        append(canonical, event.getUserName());
        append(canonical, event.getOrganizationId());
        append(canonical, event.getEventType());
        append(canonical, event.getResourceType());
        append(canonical, event.getResourceId());
        append(canonical, event.getOutcome());
        append(canonical, event.getReasonCode());
        append(canonical, event.getSanitizedQuestion());
        append(canonical, event.getQuestionHash());
        append(canonical, event.getMetricCodes());
        append(canonical, event.getSqlType());
        append(canonical, event.getSqlDigest());
        append(canonical, event.getPolicyIds());
        append(canonical, event.getMaskingSummary());
        append(canonical, event.getExportRowCount());
        append(canonical, event.getFileType());
        append(canonical, event.getFileSize());
        append(canonical, event.getClientIpHash());
        append(canonical, event.getUserAgentHash());
        append(canonical, event.getDurationMs());
        append(canonical, event.getMetadataJson());
        append(canonical, dateMillis(event.getEventTime()));
        append(canonical, dateMillis(event.getCreatedAt()));
        return DigestUtils.sha256Hex(canonical.toString());
    }

    private void append(StringBuilder canonical, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        canonical.append(text.length()).append(':').append(text).append('|');
    }

    private Long dateMillis(Date value) {
        return value == null ? null : value.getTime();
    }
}
