export type PageResult<T> = {
  list: T[];
  pageNum: number;
  pageSize: number;
  total: number;
};

export type AuditEvent = {
  id: number;
  eventId: string;
  traceId?: string;
  chatId?: number;
  queryId?: number;
  userName?: string;
  organizationId?: string;
  eventType: string;
  resourceType?: string;
  resourceId?: string;
  outcome: string;
  reasonCode?: string;
  sanitizedQuestion?: string;
  metricCodes?: string;
  sqlType?: string;
  sqlDigest?: string;
  policyIds?: string;
  maskingSummary?: string;
  exportRowCount?: number;
  fileType?: string;
  fileSize?: number;
  durationMs?: number;
  eventTime: string;
  eventHash?: string;
};

export type AuditEventQuery = {
  current?: number;
  pageSize?: number;
  traceId?: string;
  userName?: string;
  eventType?: string;
  outcome?: string;
  resourceType?: string;
  resourceId?: string;
  startTime?: number;
  endTime?: number;
};

export type AuditRule = {
  id: number;
  ruleCode: string;
  ruleName: string;
  ruleType: string;
  thresholdValue?: number;
  windowSeconds?: number;
  workHoursStart?: string;
  workHoursEnd?: string;
  severity: string;
  enabled: boolean;
  configJson?: string;
  updatedAt?: string;
  updatedBy?: string;
  version: number;
};

export type AuditRuleRequest = Omit<AuditRule, 'id' | 'updatedAt' | 'updatedBy'>;

export type SecurityAlert = {
  id: number;
  alertId: string;
  ruleCode: string;
  traceId?: string;
  userName?: string;
  organizationId?: string;
  resourceType?: string;
  resourceId?: string;
  severity: string;
  status: string;
  title: string;
  description?: string;
  occurrenceCount: number;
  firstSeen: string;
  lastSeen: string;
  version: number;
  updatedAt?: string;
  updatedBy?: string;
};

export type SecurityAlertQuery = {
  current?: number;
  pageSize?: number;
  ruleCode?: string;
  severity?: string;
  status?: string;
  userName?: string;
  startTime?: number;
  endTime?: number;
};

export type AlertAction = {
  actionId: string;
  alertId: string;
  fromStatus: string;
  toStatus: string;
  action: string;
  operatorName: string;
  comment?: string;
  createdAt: string;
};

export type AlertDetail = {
  alert: SecurityAlert;
  evidence: AuditEvent[];
  actions: AlertAction[];
};

export type AlertDisposition = {
  status: string;
  comment?: string;
  version: number;
};
