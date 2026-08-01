import request from '@/services/request';
import { ExportCreateReq, ExportTaskResp } from './types';

jest.mock('@/services/request', () => ({
  __esModule: true,
  default: jest.fn(),
}));

const mockedRequest = request as jest.MockedFunction<typeof request>;
const originalApiBaseUrl = process.env.API_BASE_URL;
process.env.API_BASE_URL = '/api/semantic/';
const {
  createExportTask,
  downloadExportFile,
  fileNameFromDisposition,
  getExportTask,
  saveDownload,
} = require('./service');

const createRequest: ExportCreateReq = {
  resourceType: 'QUERY',
  format: 'XLSX',
  title: '存款分析',
  queries: [{ dataSetId: 7, limit: 100 }],
  charts: [],
};

const task: ExportTaskResp = {
  taskId: 'task/with spaces',
  resourceType: 'QUERY',
  resourceId: 'resource-1',
  format: 'XLSX',
  status: 'SUCCEEDED',
  downloadable: true,
};

describe('export service contracts', () => {
  afterAll(() => {
    if (originalApiBaseUrl == null) delete process.env.API_BASE_URL;
    else process.env.API_BASE_URL = originalApiBaseUrl;
  });

  beforeEach(() => mockedRequest.mockReset());

  it('creates a task through the formal BE-13 endpoint', async () => {
    mockedRequest.mockResolvedValue({ code: 200, data: task, msg: 'ok' } as any);

    await expect(createExportTask(createRequest)).resolves.toEqual(task);
    expect(mockedRequest).toHaveBeenCalledWith('/api/semantic/export', {
      method: 'POST',
      data: createRequest,
    });
  });

  it('gets only the requested owned task with an encoded path segment', async () => {
    mockedRequest.mockResolvedValue(task as any);

    await expect(getExportTask(task.taskId)).resolves.toEqual(task);
    expect(mockedRequest).toHaveBeenCalledWith('/api/semantic/export/task%2Fwith%20spaces', {
      method: 'GET',
    });
  });

  it('downloads through the shared authenticated request client as a Blob', async () => {
    const blob = new Blob(['xlsx']);
    mockedRequest.mockResolvedValue({
      data: blob,
      response: {
        headers: {
          get: (name: string) =>
            name === 'Content-Disposition'
              ? "attachment; filename*=UTF-8''%E5%AD%98%E6%AC%BE%E5%88%86%E6%9E%90.xlsx"
              : null,
        },
      },
    } as any);

    await expect(downloadExportFile(task.taskId, 'fallback.xlsx')).resolves.toEqual({
      blob,
      fileName: '存款分析.xlsx',
    });
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/semantic/export/task%2Fwith%20spaces/download',
      {
        method: 'GET',
        responseType: 'blob',
        getResponse: true,
      },
    );
  });

  it('parses RFC 5987 and legacy download filenames safely', () => {
    expect(fileNameFromDisposition("attachment; filename*=UTF-8''report%20one.pdf", 'x.pdf')).toBe(
      'report one.pdf',
    );
    expect(fileNameFromDisposition('attachment; filename="report.xlsx"', 'x.xlsx')).toBe(
      'report.xlsx',
    );
    expect(fileNameFromDisposition(null, 'fallback.xlsx')).toBe('fallback.xlsx');
  });

  it('saves a Blob using a temporary object URL', () => {
    const click = jest.fn();
    const anchor = document.createElement('a');
    anchor.click = click;
    jest.spyOn(document, 'createElement').mockReturnValue(anchor);
    Object.defineProperty(URL, 'createObjectURL', { value: jest.fn(() => 'blob:export') });
    Object.defineProperty(URL, 'revokeObjectURL', { value: jest.fn() });
    jest.spyOn(window, 'setTimeout').mockImplementation((callback: TimerHandler) => {
      if (typeof callback === 'function') callback();
      return 1;
    });

    saveDownload({ blob: new Blob(['pdf']), fileName: 'report.pdf' });

    expect(anchor.download).toBe('report.pdf');
    expect(click).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:export');
  });
});
