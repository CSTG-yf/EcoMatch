import {
  buildExportRequest,
  buildLockedExportRequest,
  canDownloadTask,
  canRetryTask,
  classifyExportError,
  dataRangeText,
  formatFileSize,
  isPollingStatus,
  maskingText,
  normalizeApiResult,
  parseQueryInput,
  statusLabel,
  upsertTask,
  validateExportRequest,
} from './model';
import { ExportCreateReq, ExportTaskResp } from './types';

const query = { dataSetId: 7, groups: ['branch'], limit: 1_200, offset: 0 };

const request: ExportCreateReq = {
  resourceType: 'QUERY',
  format: 'XLSX',
  title: '网点存款明细',
  queries: [query],
  charts: [],
};

const task: ExportTaskResp = {
  taskId: 'task-1',
  resourceType: 'QUERY',
  resourceId: 'task-1',
  format: 'XLSX',
  status: 'SUCCEEDED',
  fileName: '网点存款明细.xlsx',
  downloadable: true,
  createdAt: '2026-08-01T08:00:00Z',
};

describe('export center model', () => {
  it('builds the exact QUERY and DASHBOARD request shapes', () => {
    expect(buildExportRequest({ ...request })).toEqual(request);
    expect(
      buildExportRequest({
        resourceType: 'DASHBOARD',
        dashboardId: 9,
        format: 'PDF',
        queries: [query],
      }),
    ).toEqual({
      resourceType: 'DASHBOARD',
      dashboardId: 9,
      format: 'PDF',
      title: undefined,
      queries: [query],
      charts: [],
    });
  });

  it('requires exactly one structured query for QUERY exports', () => {
    expect(() => validateExportRequest({ ...request, queries: [] })).toThrow(
      '问数导出必须包含且仅包含一个结构化查询',
    );
    expect(() => validateExportRequest({ ...request, queries: [query, query] })).toThrow(
      '问数导出必须包含且仅包含一个结构化查询',
    );
  });

  it('allows snapshot exports with an implicit single query', () => {
    const snapshot = validateExportRequest({
      resourceType: 'QUERY',
      format: 'XLSX',
      title: '问数快照',
      queries: [],
      charts: [],
      snapshotQueryId: 321,
    });

    expect(snapshot.snapshotQueryId).toBe(321);
    expect(snapshot.queries).toEqual([]);
    expect(() =>
      validateExportRequest({
        ...snapshot,
        charts: [{ queryIndex: 0, type: 'BAR', categoryField: '机构', valueField: '存款余额' }],
      }),
    ).not.toThrow();
    expect(() =>
      validateExportRequest({
        ...snapshot,
        charts: [{ queryIndex: 1, type: 'BAR', categoryField: '机构', valueField: '存款余额' }],
      }),
    ).toThrow('图表定义无效');
  });

  it('rejects invalid snapshot query ids and non-query snapshot resources', () => {
    expect(() =>
      validateExportRequest({ ...request, queries: [], snapshotQueryId: 0 }),
    ).toThrow('快照导出必须指定有效的问数记录 ID');
    expect(() =>
      validateExportRequest({ ...request, queries: [], snapshotQueryId: -3 }),
    ).toThrow('快照导出必须指定有效的问数记录 ID');
    expect(() =>
      validateExportRequest({
        ...request,
        resourceType: 'DASHBOARD',
        dashboardId: 9,
        snapshotQueryId: 3,
      }),
    ).toThrow('快照导出仅支持问数结果来源');
  });

  it('keeps the snapshot id immutable through locked rebuilds', () => {
    const locked = buildLockedExportRequest(
      { ...request, queries: [], snapshotQueryId: 88 },
      'PDF',
    );
    expect(locked.snapshotQueryId).toBe(88);
    expect(locked.queries).toEqual([]);
  });

  it('describes snapshot exports as answer-time data', () => {
    expect(dataRangeText({ ...request, queries: [], snapshotQueryId: 7 })).toContain(
      '问数回答快照导出',
    );
    expect(dataRangeText({ ...request, queries: [], snapshotQueryId: 7 })).toContain(
      'PDF 最多 500 行',
    );
  });

  it('keeps trusted source fields immutable for locked integration entry points', () => {
    const locked = buildLockedExportRequest(request, 'PDF');
    expect(locked).toEqual({ ...request, format: 'PDF' });
    expect(locked.queries).toBe(request.queries);
    expect(locked.charts).toBe(request.charts);
    expect(() => buildLockedExportRequest(undefined, 'XLSX')).toThrow('缺少可信来源');
  });

  it('validates dashboard IDs, query count, offsets, and chart references', () => {
    expect(() =>
      validateExportRequest({ ...request, resourceType: 'DASHBOARD', dashboardId: 0 }),
    ).toThrow('看板导出必须指定有效的看板 ID');
    expect(() =>
      validateExportRequest({ ...request, queries: [{ ...query, offset: -1 }] }),
    ).toThrow('查询偏移量不能为负数');
    expect(() =>
      validateExportRequest({
        ...request,
        charts: [{ queryIndex: 2, type: 'BAR', categoryField: 'branch', valueField: 'balance' }],
      }),
    ).toThrow('图表定义无效');
  });

  it('parses one object for QUERY and an array for DASHBOARD', () => {
    expect(parseQueryInput('{"dataSetId":7}', 'QUERY')).toEqual([{ dataSetId: 7 }]);
    expect(parseQueryInput('[{"dataSetId":7}]', 'DASHBOARD')).toEqual([{ dataSetId: 7 }]);
    expect(() => parseQueryInput('{', 'QUERY')).toThrow('结构化查询不是有效的 JSON');
    expect(() => parseQueryInput('{"dataSetId":7}', 'DASHBOARD')).toThrow(
      '看板来源需要提供结构化查询数组',
    );
  });

  it('describes backend-enforced row limits without overstating scope', () => {
    expect(dataRangeText(request)).toContain('预计最多 1,200 行');
    expect(dataRangeText({ ...request, format: 'PDF' })).toContain('PDF 最多 500 行');
    expect(
      dataRangeText({
        ...request,
        resourceType: 'DASHBOARD',
        dashboardId: 9,
        queries: [{ limit: 8_000 }, { limit: 8_000 }],
      }),
    ).toContain('预计最多 10,000 行');
  });

  it('maps masking summaries and file sizes for display', () => {
    expect(maskingText('NONE')).toBe('服务端未报告字段脱敏');
    expect(maskingText('MASKED_FIELDS:3')).toBe('已按当前身份脱敏 3 个字段');
    expect(formatFileSize(1536)).toBe('1.5 KB');
    expect(formatFileSize(undefined)).toBe('-');
  });

  it('recognizes polling, download, and retry states', () => {
    expect(isPollingStatus('PENDING')).toBe(true);
    expect(isPollingStatus('RUNNING')).toBe(true);
    expect(isPollingStatus('FAILED')).toBe(false);
    expect(canDownloadTask(task)).toBe(true);
    expect(canDownloadTask({ ...task, downloadable: false })).toBe(false);
    expect(canRetryTask({ ...task, status: 'FAILED', request })).toBe(true);
    expect(canRetryTask({ ...task, status: 'EXPIRED' })).toBe(false);
    expect(statusLabel('EXPIRED')).toBe('已过期');
  });

  it('keeps the original request while replacing refreshed task state', () => {
    expect(
      upsertTask([{ ...task, status: 'RUNNING', downloadable: false, request }], task)[0],
    ).toMatchObject({ status: 'SUCCEEDED', request });
  });

  it('unwraps standard API results and rejects non-success envelopes', () => {
    expect(normalizeApiResult({ code: 200, data: task, msg: 'ok' })).toEqual(task);
    expect(normalizeApiResult(task)).toEqual(task);
    expect(() => normalizeApiResult({ code: 500, data: task, msg: 'failed' })).toThrow();
  });

  it('shows explicit authentication, permission, expiration, and validation errors', () => {
    expect(classifyExportError({ response: { status: 401 } }).kind).toBe('UNAUTHORIZED');
    expect(classifyExportError({ code: 403 }).kind).toBe('FORBIDDEN');
    expect(classifyExportError({ message: 'Export file expired' }).kind).toBe('EXPIRED');
    expect(classifyExportError({ code: 400, msg: 'bad request' })).toMatchObject({
      kind: 'INVALID',
      message: 'bad request',
    });
  });
});
