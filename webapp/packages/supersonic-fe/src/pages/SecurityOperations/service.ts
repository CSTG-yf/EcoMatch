import request from '@/services/request';
import {
  AlertDetail,
  AlertDisposition,
  AuditEvent,
  AuditEventQuery,
  AuditRule,
  AuditRuleRequest,
  PageResult,
  SecurityAlert,
  SecurityAlertQuery,
} from './types';

const securityBase = process.env.SECURITY_API_BASE_URL || '/api/security/';

export const getAuditEvents = (params: AuditEventQuery) =>
  request<PageResult<AuditEvent>>(`${securityBase}audit/events`, {
    method: 'GET',
    params,
  });

export const getAuditEvent = (eventId: string) =>
  request<AuditEvent>(`${securityBase}audit/events/${encodeURIComponent(eventId)}`);

export const getAuditTrace = (traceId: string) =>
  request<AuditEvent[]>(`${securityBase}audit/traces/${encodeURIComponent(traceId)}`);

export const getAuditRules = () => request<AuditRule[]>(`${securityBase}audit/rules`);

export const createAuditRule = (data: AuditRuleRequest) =>
  request<AuditRule>(`${securityBase}audit/rules`, {
    method: 'POST',
    data,
  });

export const updateAuditRule = (id: number, data: AuditRuleRequest) =>
  request<AuditRule>(`${securityBase}audit/rules/${id}`, {
    method: 'PUT',
    data,
  });

export const getSecurityAlerts = (params: SecurityAlertQuery) =>
  request<PageResult<SecurityAlert>>(`${securityBase}alerts`, {
    method: 'GET',
    params,
  });

export const getSecurityAlert = (alertId: string) =>
  request<AlertDetail>(`${securityBase}alerts/${encodeURIComponent(alertId)}`);

export const updateSecurityAlertStatus = (alertId: string, data: AlertDisposition) =>
  request<SecurityAlert>(`${securityBase}alerts/${encodeURIComponent(alertId)}/status`, {
    method: 'PUT',
    data,
  });
