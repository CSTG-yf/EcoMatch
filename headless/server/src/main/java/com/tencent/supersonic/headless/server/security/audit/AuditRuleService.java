package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditRuleMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import com.tencent.supersonic.headless.server.security.audit.model.AlertSeverity;
import com.tencent.supersonic.headless.server.security.audit.model.AuditRuleRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AuditRuleService {

    static final long MAXIMUM_THRESHOLD_VALUE = 1_000_000L;
    static final long MAXIMUM_WINDOW_SECONDS = 2_592_000L;
    static final long MAXIMUM_ROW_THRESHOLD = 10_000_000L;

    private static final int MAXIMUM_RULE_NAME_LENGTH = 128;
    private static final int MAXIMUM_OPERATOR_LENGTH = 128;
    private static final int MAXIMUM_CONFIG_JSON_LENGTH = 4_096;
    private static final Pattern WORK_HOURS = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    private final AuditRuleMapper auditRuleMapper;
    private final ObjectMapper objectMapper;

    public AuditRuleService(AuditRuleMapper auditRuleMapper, ObjectMapper objectMapper) {
        this.auditRuleMapper = auditRuleMapper;
        this.objectMapper = objectMapper;
    }

    public List<AuditRuleDO> list() {
        return auditRuleMapper
                .selectList(new QueryWrapper<AuditRuleDO>().orderByAsc("rule_type", "rule_code"));
    }

    public List<AuditRuleDO> listEnabled() {
        return auditRuleMapper
                .selectList(new QueryWrapper<AuditRuleDO>().eq("enabled", true).orderByAsc("id"));
    }

    @Transactional
    public AuditRuleDO create(AuditRuleRequest request, String operator) {
        ValidatedRule validated = validate(request, operator, false);
        if (findByCode(validated.ruleCode()) != null) {
            throw new IllegalArgumentException("Audit rule code already exists");
        }
        AuditRuleDO rule = newRule(request, validated);
        auditRuleMapper.insert(rule);
        return rule;
    }

    @Transactional
    public AuditRuleDO update(Long id, AuditRuleRequest request, String operator) {
        if (id == null) {
            throw new IllegalArgumentException("Audit rule id is required");
        }
        ValidatedRule validated = validate(request, operator, true);
        AuditRuleDO existing = auditRuleMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Audit rule does not exist");
        }
        if (!existing.getRuleCode().equals(validated.ruleCode())) {
            throw new IllegalArgumentException("Audit rule code cannot be changed");
        }
        if (!request.getVersion().equals(existing.getVersion())) {
            throw new IllegalStateException("Audit rule was modified by another operator");
        }
        AuditRuleDO changed = new AuditRuleDO();
        changed.setId(id);
        apply(changed, request, validated);
        changed.setUpdatedAt(new Date());
        changed.setUpdatedBy(validated.operator());
        int updated = auditRuleMapper.compareAndSet(changed, request.getVersion());
        if (updated != 1) {
            throw new IllegalStateException("Audit rule was modified by another operator");
        }
        return auditRuleMapper.selectById(id);
    }

    public void initializeDefaults() {
        createDefault("HIGH_FREQUENCY_QUERY", "High frequency query",
                AlertRuleType.HIGH_FREQUENCY_QUERY, 60L, 60L, null, null, AlertSeverity.MEDIUM,
                null);
        createDefault("BULK_EXPORT", "Bulk export", AlertRuleType.BULK_EXPORT, 5L, 300L, null, null,
                AlertSeverity.HIGH, "{\"rowThreshold\":10000}");
        createDefault("REPEATED_AUTH_DENIAL", "Repeated authorization denial",
                AlertRuleType.REPEATED_AUTH_DENIAL, 3L, 300L, null, null, AlertSeverity.HIGH, null);
        createDefault("OFF_HOURS_ACCESS", "Off-hours access", AlertRuleType.OFF_HOURS_ACCESS, 1L,
                0L, "07:00", "22:00", AlertSeverity.MEDIUM, null);
        createDefault("CROSS_ORGANIZATION_ACCESS", "Cross-organization access",
                AlertRuleType.CROSS_ORGANIZATION_ACCESS, 2L, 300L, null, null,
                AlertSeverity.HIGH, null);
        createDefault("POLICY_CHANGE_SPIKE", "Authorization policy change spike",
                AlertRuleType.POLICY_CHANGE_SPIKE, 5L, 300L, null, null, AlertSeverity.HIGH,
                null);
    }

    private void createDefault(String code, String name, AlertRuleType type, Long threshold,
            Long windowSeconds, String start, String end, AlertSeverity severity, String config) {
        if (findByCode(code) != null) {
            return;
        }
        AuditRuleRequest request = new AuditRuleRequest();
        request.setRuleCode(code);
        request.setRuleName(name);
        request.setRuleType(type);
        request.setThresholdValue(threshold);
        request.setWindowSeconds(windowSeconds);
        request.setWorkHoursStart(start);
        request.setWorkHoursEnd(end);
        request.setSeverity(severity);
        request.setEnabled(true);
        request.setConfigJson(config);
        ValidatedRule validated = validate(request, "system", false);
        try {
            auditRuleMapper.insert(newRule(request, validated));
        } catch (DuplicateKeyException e) {
            // Another application instance initialized this immutable rule code first.
            log.debug("Default audit rule already exists: {}", code);
        }
    }

    private AuditRuleDO findByCode(String code) {
        return auditRuleMapper
                .selectOne(new QueryWrapper<AuditRuleDO>().eq("rule_code", code).last("LIMIT 1"));
    }

    private AuditRuleDO newRule(AuditRuleRequest request, ValidatedRule validated) {
        Date now = new Date();
        AuditRuleDO rule = new AuditRuleDO();
        apply(rule, request, validated);
        rule.setVersion(0);
        rule.setCreatedAt(now);
        rule.setCreatedBy(validated.operator());
        rule.setUpdatedAt(now);
        rule.setUpdatedBy(validated.operator());
        return rule;
    }

    private void apply(AuditRuleDO rule, AuditRuleRequest request, ValidatedRule validated) {
        rule.setRuleCode(validated.ruleCode());
        rule.setRuleName(validated.ruleName());
        rule.setRuleType(request.getRuleType().name());
        rule.setThresholdValue(request.getThresholdValue());
        rule.setWindowSeconds(request.getWindowSeconds());
        rule.setWorkHoursStart(request.getWorkHoursStart());
        rule.setWorkHoursEnd(request.getWorkHoursEnd());
        rule.setSeverity(request.getSeverity().name());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
        rule.setConfigJson(validated.configJson());
    }

    private ValidatedRule validate(AuditRuleRequest request, String operator,
            boolean versionRequired) {
        if (request == null || StringUtils.isBlank(request.getRuleCode())
                || request.getRuleType() == null || request.getSeverity() == null) {
            throw new IllegalArgumentException("Rule code, type and severity are required");
        }
        String ruleCode = request.getRuleCode().trim();
        if (!ruleCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Audit rule code format is invalid");
        }
        String ruleName = StringUtils.defaultIfBlank(request.getRuleName(), ruleCode).trim();
        validateText("Audit rule name", ruleName, MAXIMUM_RULE_NAME_LENGTH);
        String normalizedOperator = StringUtils.trimToNull(operator);
        validateText("Audit rule operator", normalizedOperator, MAXIMUM_OPERATOR_LENGTH);
        if (versionRequired && request.getVersion() == null) {
            throw new IllegalArgumentException("Audit rule version is required for updates");
        }
        if (request.getVersion() != null && request.getVersion() < 0) {
            throw new IllegalArgumentException("Audit rule version cannot be negative");
        }
        if (request.getThresholdValue() == null || request.getThresholdValue() < 1
                || request.getThresholdValue() > MAXIMUM_THRESHOLD_VALUE) {
            throw new IllegalArgumentException(
                    "Audit rule threshold must be between 1 and " + MAXIMUM_THRESHOLD_VALUE);
        }
        if (request.getWindowSeconds() == null || request.getWindowSeconds() < 0
                || request.getWindowSeconds() > MAXIMUM_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Audit rule time window must be between 0 and "
                    + MAXIMUM_WINDOW_SECONDS + " seconds");
        }
        if (request.getRuleType() != AlertRuleType.OFF_HOURS_ACCESS
                && request.getWindowSeconds() < 1) {
            throw new IllegalArgumentException("Audit rule time window must be positive");
        }
        if (request.getRuleType() == AlertRuleType.OFF_HOURS_ACCESS) {
            validateTime(request.getWorkHoursStart());
            validateTime(request.getWorkHoursEnd());
        } else if (StringUtils.isNotBlank(request.getWorkHoursStart())
                || StringUtils.isNotBlank(request.getWorkHoursEnd())) {
            throw new IllegalArgumentException(
                    "Work hours are only supported for off-hours access rules");
        }
        String configJson =
                validateAndNormalizeConfig(request.getRuleType(), request.getConfigJson());
        return new ValidatedRule(ruleCode, ruleName, normalizedOperator, configJson);
    }

    private void validateTime(String value) {
        if (value == null || !WORK_HOURS.matcher(value).matches()) {
            throw new IllegalArgumentException("Work hours must use strict HH:mm format");
        }
    }

    private String validateAndNormalizeConfig(AlertRuleType ruleType, String configJson) {
        if (configJson != null && configJson.length() > MAXIMUM_CONFIG_JSON_LENGTH) {
            throw new IllegalArgumentException("Audit rule configuration is too long");
        }
        if (StringUtils.isBlank(configJson)) {
            return null;
        }
        try {
            JsonNode config = objectMapper.readTree(configJson);
            if (!config.isObject()) {
                throw new IllegalArgumentException("Audit rule configuration must be an object");
            }
            config.fieldNames().forEachRemaining(key -> {
                if (!"rowThreshold".equals(key)) {
                    throw new IllegalArgumentException(
                            "Unsupported audit rule configuration key: " + key);
                }
            });
            if (config.size() == 0) {
                return null;
            }
            if (ruleType != AlertRuleType.BULK_EXPORT) {
                throw new IllegalArgumentException(
                        "rowThreshold is only supported for bulk export rules");
            }
            JsonNode rowThreshold = config.get("rowThreshold");
            if (rowThreshold == null || !rowThreshold.isIntegralNumber()
                    || !rowThreshold.canConvertToLong()) {
                throw new IllegalArgumentException("rowThreshold must be an integer");
            }
            long value = rowThreshold.longValue();
            if (value < 1 || value > MAXIMUM_ROW_THRESHOLD) {
                throw new IllegalArgumentException(
                        "rowThreshold must be between 1 and " + MAXIMUM_ROW_THRESHOLD);
            }
            return objectMapper.createObjectNode().put("rowThreshold", value).toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Audit rule configuration is not valid JSON", e);
        }
    }

    private void validateText(String field, String value, int maximumLength) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " cannot exceed " + maximumLength + " characters");
        }
        if (CONTROL_CHARACTER.matcher(value).find()) {
            throw new IllegalArgumentException(field + " contains control characters");
        }
    }

    private record ValidatedRule(String ruleCode, String ruleName, String operator,
            String configJson) {}
}
