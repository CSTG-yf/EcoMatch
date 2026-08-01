export type ShareIdentityPolicy = 'AUTHENTICATED' | 'ORGANIZATION' | 'USERS';

export type ShareStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED';

export type ShareEffectiveStatus = ShareStatus | 'EXHAUSTED';

export type ShareCreateRequest = {
  dashboardId: number;
  identityPolicy: ShareIdentityPolicy;
  allowedUsers: string[];
  expiresAt: string;
  maxAccessCount?: number;
  watermarkEnabled: boolean;
};

export type ShareRecord = {
  shareId: string;
  dashboardId: number;
  dashboardName?: string;
  identityPolicy: ShareIdentityPolicy;
  allowedUsers: string[];
  status: ShareStatus;
  maxAccessCount?: number | null;
  accessCount: number;
  watermarkEnabled: boolean;
  expiresAt: string;
  createdAt: string;
  revokedAt?: string | null;
  /** Returned once by create. It must not be persisted by the client. */
  token?: string;
};

export type SharePage = {
  list: ShareRecord[];
  total: number;
  pageNum?: number;
  pageSize?: number;
};

export type SharedDashboardComponent = {
  id?: string;
  type?: 'chart' | 'table' | 'number' | string;
  title?: string;
  masked?: boolean;
  visualization?: {
    chartType?: string;
  };
};

export type SharedDashboard = {
  id: number;
  name: string;
  description?: string;
  status?: string;
  config?:
    | string
    | {
        components?: SharedDashboardComponent[];
      };
};

export type ShareAccessResponse = {
  shareId: string;
  dashboard: SharedDashboard;
  watermarkUser?: string | null;
  watermarkOrganization?: string | null;
  accessedAt: string;
  componentData: Record<string, SharedComponentQueryData> | SharedComponentDataEntry[];
  componentErrors: Record<string, string> | SharedComponentErrorEntry[];
};

export type SharedQueryColumn = {
  name?: string;
  bizName?: string;
  [key: string]: unknown;
};

export type SharedComponentQueryData = {
  queryResults?: Record<string, unknown>[];
  queryColumns?: SharedQueryColumn[];
  masked?: boolean;
  maskingApplied?: boolean;
  dataMasked?: boolean;
  [key: string]: unknown;
};

export type SharedComponentDataEntry = {
  componentId: string;
  data?: SharedComponentQueryData;
  result?: SharedComponentQueryData;
};

export type SharedComponentErrorEntry = {
  componentId: string;
  error?: string;
  message?: string;
};

export type ShareAccessErrorKind = 'EXPIRED' | 'REVOKED' | 'EXHAUSTED' | 'FORBIDDEN' | 'FAILED';

export type ShareCreateValues = {
  identityPolicy: ShareIdentityPolicy;
  allowedUsers?: string[];
  expiresAt: { toISOString: () => string } | string | Date;
  maxAccessCount?: number | null;
  watermarkEnabled?: boolean;
};
