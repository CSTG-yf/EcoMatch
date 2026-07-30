const ALERT_TRANSITIONS: Record<string, string[]> = {
  NEW: ['ACKNOWLEDGED', 'CLOSED', 'DISMISSED'],
  ACKNOWLEDGED: ['RESOLVED', 'CLOSED', 'DISMISSED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
  DISMISSED: [],
};

export const nextAlertStatuses = (status?: string) =>
  status ? ALERT_TRANSITIONS[status] || [] : [];

export const requiresDispositionComment = (status: string) => status !== 'ACKNOWLEDGED';

export const normalizeApiData = <T>(response: any): T =>
  (response?.data === undefined ? response : response.data) as T;

export const normalizePage = <T>(response: any) => {
  const page = normalizeApiData<any>(response) || {};
  return {
    data: Array.isArray(page.list) ? (page.list as T[]) : [],
    total: Number(page.total || 0),
    success: true,
  };
};

export const formatFileSize = (bytes?: number) => {
  if (bytes === undefined || bytes === null) {
    return '-';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

export const isSecurityWriter = (currentUser?: API.CurrentUser) =>
  Boolean(currentUser?.superAdmin || currentUser?.roles?.includes('SECURITY_ADMIN'));
