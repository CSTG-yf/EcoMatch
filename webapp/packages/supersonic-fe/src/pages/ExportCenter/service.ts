import request from '@/services/request';
import { normalizeApiResult } from './model';
import { ExportCreateReq, ExportDownload, ExportTaskResp } from './types';

const exportBase = `${process.env.API_BASE_URL || '/api/semantic/'}export`;

export const createExportTask = async (data: ExportCreateReq): Promise<ExportTaskResp> => {
  const response = await request<ExportTaskResp | Result<ExportTaskResp>>(exportBase, {
    method: 'POST',
    data,
  });
  return normalizeApiResult(response);
};

export const getExportTask = async (taskId: string): Promise<ExportTaskResp> => {
  const response = await request<ExportTaskResp | Result<ExportTaskResp>>(
    `${exportBase}/${encodeURIComponent(taskId)}`,
    { method: 'GET' },
  );
  return normalizeApiResult(response);
};

export const fileNameFromDisposition = (disposition: string | null, fallback: string) => {
  if (!disposition) return fallback;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return fallback;
    }
  }
  return /filename="?([^";]+)"?/i.exec(disposition)?.[1] || fallback;
};

export const downloadExportFile = async (
  taskId: string,
  fallbackFileName = `export-${taskId}`,
): Promise<ExportDownload> => {
  // The shared request client adds the current Bearer token to this Blob request.
  const result: any = await request(`${exportBase}/${encodeURIComponent(taskId)}/download`, {
    method: 'GET',
    responseType: 'blob',
    getResponse: true,
  });
  const blob = result?.data instanceof Blob ? result.data : new Blob([result?.data]);
  const disposition = result?.response?.headers?.get?.('Content-Disposition') || null;
  return {
    blob,
    fileName: fileNameFromDisposition(disposition, fallbackFileName),
  };
};

export const saveDownload = ({ blob, fileName }: ExportDownload) => {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
};

export const exportApi = {
  create: createExportTask,
  get: getExportTask,
  download: downloadExportFile,
  saveDownload,
};
