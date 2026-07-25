import request from 'umi-request';

const governanceBase = `${process.env.API_BASE_URL}metric-governance`;
const bankResourceBase = `${process.env.API_BASE_URL}bank/resources`;

export const getGovernanceDomains = () =>
  request.get(`${process.env.API_BASE_URL}domain/getDomainList`);

export const getGovernanceModels = (domainId: number) =>
  request.get(`${process.env.API_BASE_URL}model/getModelList/${domainId}`);

export const getGovernanceTerms = (domainId: number) =>
  request.get(`${process.env.API_BASE_URL}term`, { params: { domainId } });

export const getModelMetrics = (modelId: number) =>
  request.post(`${process.env.API_BASE_URL}metric/queryMetric`, {
    data: { current: 1, pageSize: 9999, modelIds: [modelId] },
  });

export const listGovernanceMetrics = (modelId: number, params?: Record<string, any>) =>
  request.get(`${governanceBase}/models/${modelId}/metrics`, { params });

export const bootstrapGovernance = (modelId: number, data: Record<string, any>) =>
  request.post(`${governanceBase}/models/${modelId}/bootstrap`, { data });

export const getGovernanceDetail = (metricId: number) =>
  request.get(`${governanceBase}/metrics/${metricId}`);

export const updateGovernance = (metricId: number, data: Record<string, any>) =>
  request(`${governanceBase}/metrics/${metricId}`, { method: 'PUT', data });

export const createMetricVersion = (metricId: number, changeSummary?: string) =>
  request.post(`${governanceBase}/metrics/${metricId}/versions`, {
    params: { changeSummary },
  });

export const submitMetricVersion = (metricId: number, versionId: number, comment?: string) =>
  request.post(`${governanceBase}/metrics/${metricId}/versions/${versionId}/submit`, {
    params: { comment },
  });

export const publishMetricVersion = (metricId: number, versionId: number, comment?: string) =>
  request.post(`${governanceBase}/metrics/${metricId}/versions/${versionId}/publish`, {
    params: { comment },
  });

export const rollbackMetricVersion = (metricId: number, versionNo: number, comment?: string) =>
  request.post(`${governanceBase}/metrics/${metricId}/versions/${versionNo}/rollback`, {
    params: { comment },
  });

export const deactivateMetric = (metricId: number, comment?: string) =>
  request.post(`${governanceBase}/metrics/${metricId}/deactivate`, { params: { comment } });

export const decideApproval = (approvalId: number, approved: boolean, comment?: string) =>
  request.post(`${governanceBase}/approvals/${approvalId}/${approved ? 'approve' : 'reject'}`, {
    data: { comment },
  });

export const listMetricConflicts = (modelId: number) =>
  request.get(`${governanceBase}/models/${modelId}/conflicts`);

export const getMetricLineage = (metricId: number) =>
  request.get(`${governanceBase}/metrics/${metricId}/lineage`);

const upload = (url: string, file: File, values?: Record<string, any>) => {
  const formData = new FormData();
  formData.append('file', file);
  Object.entries(values || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      formData.append(key, String(value));
    }
  });
  return request(url, { method: 'POST', data: formData });
};

export const validateBankWorkbook = (file: File) => upload(`${bankResourceBase}/validate`, file);

export const importBankWorkbook = (file: File, values: Record<string, any>) =>
  upload(`${bankResourceBase}/import`, file, values);
