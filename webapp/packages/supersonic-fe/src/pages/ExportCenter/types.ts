export type ExportResourceType = 'QUERY' | 'DASHBOARD';

export type ExportFormat = 'XLSX' | 'PDF';

export type ExportStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';

export type ExportChartType = 'BAR' | 'LINE';

/**
 * The backend owns the full QueryStructReq schema. Keeping extensible nested values here lets
 * callers pass the same structured query object that was used by the semantic query endpoint.
 */
export interface QueryStructReq {
  dataSetId?: number;
  dataSetName?: string;
  modelIds?: number[];
  dimensions?: Record<string, unknown>[];
  groups?: string[];
  aggregators?: Record<string, unknown>[];
  orders?: Record<string, unknown>[];
  dimensionFilters?: Record<string, unknown>[];
  metricFilters?: Record<string, unknown>[];
  dateInfo?: Record<string, unknown>;
  params?: Record<string, unknown>[];
  cacheInfo?: Record<string, unknown>;
  sqlInfo?: Record<string, unknown>;
  limit?: number;
  offset?: number;
  queryType?: string;
  convertToSql?: boolean;
  [key: string]: unknown;
}

export interface ExportChartReq {
  queryIndex: number;
  type: ExportChartType;
  title?: string;
  categoryField: string;
  valueField: string;
}

export interface ExportCreateReq {
  resourceType: ExportResourceType;
  dashboardId?: number;
  format: ExportFormat;
  title?: string;
  queries: QueryStructReq[];
  charts: ExportChartReq[];
  /**
   * Snapshot export carries only the chat query id; the server resolves the stored result
   * from chat history. Clients must never submit result snapshots.
   */
  snapshotQueryId?: number;
}

export interface ExportTaskResp {
  taskId: string;
  resourceType: ExportResourceType;
  resourceId: string;
  format: ExportFormat;
  status: ExportStatus;
  fileName?: string;
  fileSize?: number;
  rowCount?: number;
  maskingSummary?: string;
  failureCode?: string;
  expiresAt?: string;
  createdAt?: string;
  completedAt?: string;
  downloadable: boolean;
}

export interface ExportTaskPage {
  list: ExportTaskResp[];
  pageNum?: number;
  pageSize?: number;
  total?: number;
  pages?: number;
}

export interface ExportTaskItem extends ExportTaskResp {
  request?: ExportCreateReq;
  actionError?: ExportError;
}

export type ExportErrorKind = 'FORBIDDEN' | 'UNAUTHORIZED' | 'EXPIRED' | 'INVALID' | 'FAILED';

export interface ExportError {
  kind: ExportErrorKind;
  message: string;
  status?: number;
}

export interface ExportDownload {
  blob: Blob;
  fileName: string;
}

export interface CreateExportProps {
  initialRequest?: ExportCreateReq;
  lockedSource?: boolean;
  autoOpen?: boolean;
  disabled?: boolean;
  buttonType?: 'default' | 'primary' | 'dashed' | 'link' | 'text';
  buttonText?: string;
  onCreate: (request: ExportCreateReq) => Promise<ExportTaskResp | undefined>;
}
