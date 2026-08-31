import {
  ExportCreateReq,
  ExportError,
  ExportFormat,
  ExportResourceType,
  ExportStatus,
  ExportTaskItem,
  ExportTaskResp,
  QueryStructReq,
} from './types';

export const EXPORT_LIMITS = {
  maxQueries: 20,
  maxRows: 10_000,
  maxPdfRows: 500,
  maxFileBytes: 25 * 1024 * 1024,
  retentionHours: 24,
  pollIntervalMs: 2_000,
} as const;

const STATUS_LABELS: Record<ExportStatus, string> = {
  PENDING: '等待处理',
  RUNNING: '正在生成',
  SUCCEEDED: '可下载',
  FAILED: '生成失败',
  EXPIRED: '已过期',
};

export const statusLabel = (status: ExportStatus) => STATUS_LABELS[status] || status;

export const isPollingStatus = (status: ExportStatus) =>
  status === 'PENDING' || status === 'RUNNING';

export const canDownloadTask = (task: ExportTaskResp) =>
  task.status === 'SUCCEEDED' && task.downloadable;

export const canRetryTask = (task: ExportTaskItem) =>
  Boolean(task.request && (task.status === 'FAILED' || task.status === 'EXPIRED'));

export const normalizeApiResult = <T>(response: T | Result<T>): T => {
  const wrapped = response as Result<T>;
  if (wrapped && typeof wrapped === 'object' && 'code' in wrapped && 'data' in wrapped) {
    if (Number(wrapped.code) !== 200 && Number(wrapped.code) !== 0) {
      const error = new Error(wrapped.msg || 'Export request failed') as Error & {
        code?: number;
        msg?: string;
      };
      error.code = wrapped.code;
      error.msg = wrapped.msg;
      throw error;
    }
    return wrapped.data;
  }
  return response as T;
};

export const validateExportRequest = (request: ExportCreateReq): ExportCreateReq => {
  if (!request || !['QUERY', 'DASHBOARD'].includes(request.resourceType)) {
    throw new Error('请选择有效的数据来源');
  }
  if (!['XLSX', 'PDF'].includes(request.format)) {
    throw new Error('请选择 XLSX 或 PDF 格式');
  }
  const title = request.title?.trim();
  if (title && title.length > 200) {
    throw new Error('导出标题不能超过 200 个字符');
  }
  const snapshotQueryId =
    request.snapshotQueryId == null ? undefined : Number(request.snapshotQueryId);
  if (
    request.snapshotQueryId != null &&
    (!Number.isInteger(snapshotQueryId) || (snapshotQueryId as number) <= 0)
  ) {
    throw new Error('快照导出必须指定有效的问数记录 ID');
  }
  const isSnapshot = snapshotQueryId != null;
  if (isSnapshot && request.resourceType !== 'QUERY') {
    throw new Error('快照导出仅支持问数结果来源');
  }
  const queries = Array.isArray(request.queries) ? request.queries : [];
  if (queries.length > EXPORT_LIMITS.maxQueries) {
    throw new Error('单个导出最多包含 20 个查询');
  }
  if (queries.some((query) => !query || typeof query !== 'object' || Array.isArray(query))) {
    throw new Error('结构化查询必须是 JSON 对象');
  }
  if (queries.some((query) => Number(query.offset || 0) < 0)) {
    throw new Error('查询偏移量不能为负数');
  }
  if (request.resourceType === 'QUERY' && !isSnapshot && queries.length !== 1) {
    throw new Error('问数导出必须包含且仅包含一个结构化查询');
  }
  if (
    request.resourceType === 'DASHBOARD' &&
    (!Number.isInteger(Number(request.dashboardId)) || Number(request.dashboardId) <= 0)
  ) {
    throw new Error('看板导出必须指定有效的看板 ID');
  }
  const charts = Array.isArray(request.charts) ? request.charts : [];
  if (charts.length > EXPORT_LIMITS.maxQueries) {
    throw new Error('单个导出最多包含 20 个图表');
  }
  const queryCount = isSnapshot ? 1 : queries.length;
  charts.forEach((chart) => {
    if (
      !chart ||
      !Number.isInteger(chart.queryIndex) ||
      chart.queryIndex < 0 ||
      chart.queryIndex >= queryCount ||
      !['BAR', 'LINE'].includes(chart.type) ||
      !chart.categoryField?.trim() ||
      !chart.valueField?.trim()
    ) {
      throw new Error('图表定义无效，请检查查询序号、图表类型和字段');
    }
    if (chart.title && chart.title.length > 200) {
      throw new Error('图表标题不能超过 200 个字符');
    }
  });
  return {
    ...request,
    dashboardId: request.resourceType === 'DASHBOARD' ? Number(request.dashboardId) : undefined,
    title: title || undefined,
    queries,
    charts,
    snapshotQueryId,
  };
};

export const parseQueryInput = (
  value: string,
  resourceType: ExportResourceType,
): QueryStructReq[] => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value || (resourceType === 'QUERY' ? '{}' : '[]'));
  } catch {
    throw new Error('结构化查询不是有效的 JSON');
  }
  const queries = resourceType === 'QUERY' ? [parsed] : parsed;
  if (!Array.isArray(queries)) {
    throw new Error('看板来源需要提供结构化查询数组');
  }
  return queries as QueryStructReq[];
};

export const buildExportRequest = (input: {
  resourceType: ExportResourceType;
  format: ExportFormat;
  title?: string;
  dashboardId?: number;
  queries: QueryStructReq[];
  charts?: ExportCreateReq['charts'];
}) =>
  validateExportRequest({
    resourceType: input.resourceType,
    format: input.format,
    title: input.title,
    dashboardId: input.dashboardId,
    queries: input.queries,
    charts: input.charts || [],
  });

export const buildLockedExportRequest = (
  initialRequest: ExportCreateReq | undefined,
  format: ExportFormat,
) => {
  if (!initialRequest) throw new Error('受控导出缺少可信来源');
  return validateExportRequest({
    resourceType: initialRequest.resourceType,
    format,
    title: initialRequest.title,
    dashboardId: initialRequest.dashboardId,
    queries: initialRequest.queries,
    charts: initialRequest.charts,
    snapshotQueryId: initialRequest.snapshotQueryId,
  });
};

export const dataRangeText = (request: ExportCreateReq) => {
  if (request.snapshotQueryId != null) {
    return '问数回答快照导出（结果以回答时数据为准），单文件最多 10,000 行；XLSX 最多 10,000 行 / PDF 最多 500 行';
  }
  const queryCount = request.queries.length;
  const requestedRows = request.queries.reduce((total, query) => {
    const limit = Number(query.limit ?? EXPORT_LIMITS.maxRows);
    return total + Math.min(Math.max(1, Number.isFinite(limit) ? limit : 1), EXPORT_LIMITS.maxRows);
  }, 0);
  const cappedRows = Math.min(requestedRows, EXPORT_LIMITS.maxRows);
  const formatLimit = request.format === 'PDF' ? 'PDF 最多 500 行' : 'XLSX 最多 10,000 行';
  return `${queryCount} 个结构化查询，预计最多 ${cappedRows.toLocaleString()} 行；${formatLimit}`;
};

export const maskingText = (summary?: string) => {
  if (!summary || summary === 'NONE') {
    return '服务端未报告字段脱敏';
  }
  const count = /^MASKED_FIELDS:(\d+)$/.exec(summary)?.[1];
  return count ? `已按当前身份脱敏 ${count} 个字段` : `已应用脱敏策略：${summary}`;
};

export const formatFileSize = (bytes?: number) => {
  if (bytes == null || !Number.isFinite(bytes)) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

export const formatDateTime = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';

export const upsertTask = (tasks: ExportTaskItem[], task: ExportTaskItem) => {
  const previous = tasks.find((item) => item.taskId === task.taskId);
  return [
    { ...previous, ...task, request: task.request || previous?.request },
    ...tasks.filter((item) => item.taskId !== task.taskId),
  ].sort((left, right) => {
    const leftTime = new Date(left.createdAt || 0).getTime();
    const rightTime = new Date(right.createdAt || 0).getTime();
    return rightTime - leftTime;
  });
};

const errorStatus = (error: any) =>
  Number(error?.code ?? error?.status ?? error?.response?.status ?? error?.data?.code) || undefined;

export const classifyExportError = (error: any): ExportError => {
  const status = errorStatus(error);
  const detail = String(
    error?.msg || error?.message || error?.data?.msg || error?.response?.data?.msg || '',
  ).toLowerCase();
  if (status === 401) {
    return { kind: 'UNAUTHORIZED', status, message: '登录状态已失效，请重新登录后再试' };
  }
  if (status === 403) {
    return { kind: 'FORBIDDEN', status, message: '无权访问该导出任务或其数据来源' };
  }
  if (detail.includes('expired') || detail.includes('过期')) {
    return { kind: 'EXPIRED', status, message: '导出文件已过期，请重新创建任务' };
  }
  if (status === 400 || status === 422) {
    return { kind: 'INVALID', status, message: error?.msg || error?.message || '导出参数无效' };
  }
  return {
    kind: 'FAILED',
    status,
    message: error?.msg || error?.message || '导出服务暂时不可用，请稍后重试',
  };
};
