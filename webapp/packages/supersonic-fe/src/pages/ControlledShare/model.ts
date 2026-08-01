import {
  ShareAccessErrorKind,
  ShareCreateRequest,
  ShareCreateValues,
  ShareEffectiveStatus,
  SharePage,
  ShareRecord,
  SharedComponentQueryData,
  SharedDashboardComponent,
} from './types';

export const MAX_SHARE_LIFETIME_DAYS = 30;
export const MAX_SHARE_ACCESS_COUNT = 100_000;
export const MAX_ALLOWED_USERS = 100;

export const unwrapShareResponse = <T>(response: any): T => {
  if (response?.code != null && ![0, 200].includes(Number(response.code))) {
    throw response;
  }
  return (response?.data ?? response) as T;
};

export const normalizeSharePage = (response: any): SharePage => {
  const page = unwrapShareResponse<any>(response) || {};
  return {
    list: Array.isArray(page.list) ? page.list : [],
    total: Number(page.total || 0),
    pageNum: page.pageNum == null ? undefined : Number(page.pageNum),
    pageSize: page.pageSize == null ? undefined : Number(page.pageSize),
  };
};

export const normalizeAllowedUsers = (users: string[] | undefined): string[] => {
  const normalized = (users || []).map((user) => String(user).trim()).filter(Boolean);
  return Array.from(new Set(normalized));
};

const toDate = (value: ShareCreateValues['expiresAt']) => {
  if (typeof value === 'string' || value instanceof Date) {
    return new Date(value);
  }
  return new Date(value.toISOString());
};

export const buildShareCreateRequest = (
  dashboardId: number,
  values: ShareCreateValues,
  now = new Date(),
): ShareCreateRequest => {
  if (!Number.isInteger(dashboardId) || dashboardId <= 0) {
    throw new Error('看板标识无效');
  }
  const expiresAt = toDate(values.expiresAt);
  const latestExpiry = new Date(now.getTime() + MAX_SHARE_LIFETIME_DAYS * 24 * 60 * 60 * 1000);
  if (!Number.isFinite(expiresAt.getTime()) || expiresAt <= now || expiresAt > latestExpiry) {
    throw new Error('有效期必须在未来 30 天内');
  }

  const allowedUsers = normalizeAllowedUsers(values.allowedUsers);
  if (allowedUsers.length > MAX_ALLOWED_USERS) {
    throw new Error('最多允许 100 个指定用户');
  }
  if (allowedUsers.some((user) => user.length > 100)) {
    throw new Error('用户标识不能超过 100 个字符');
  }
  if (values.identityPolicy === 'USERS' && allowedUsers.length === 0) {
    throw new Error('指定用户模式至少需要一个用户');
  }

  const maxAccessCount = values.maxAccessCount == null ? undefined : Number(values.maxAccessCount);
  if (
    maxAccessCount != null &&
    (!Number.isInteger(maxAccessCount) ||
      maxAccessCount < 1 ||
      maxAccessCount > MAX_SHARE_ACCESS_COUNT)
  ) {
    throw new Error('访问上限必须是 1 至 100000 的整数');
  }

  return {
    dashboardId,
    identityPolicy: values.identityPolicy,
    allowedUsers: values.identityPolicy === 'USERS' ? allowedUsers : [],
    expiresAt: expiresAt.toISOString(),
    maxAccessCount,
    watermarkEnabled: values.watermarkEnabled !== false,
  };
};

export const effectiveShareStatus = (
  share: ShareRecord,
  now = new Date(),
): ShareEffectiveStatus => {
  if (share.status === 'REVOKED') {
    return 'REVOKED';
  }
  if (share.status === 'EXPIRED' || new Date(share.expiresAt).getTime() <= now.getTime()) {
    return 'EXPIRED';
  }
  if (share.maxAccessCount != null && share.accessCount >= share.maxAccessCount) {
    return 'EXHAUSTED';
  }
  return 'ACTIVE';
};

export const remainingAccessCount = (share: ShareRecord): number | undefined =>
  share.maxAccessCount == null ? undefined : Math.max(share.maxAccessCount - share.accessCount, 0);

export const buildControlledShareUrl = (
  token: string,
  origin: string,
  routeBase = '/webapp/share',
) => {
  if (!token.trim()) {
    throw new Error('分享 Token 不能为空');
  }
  const normalizedOrigin = origin.replace(/\/$/, '');
  const normalizedBase = `/${routeBase}`.replace(/\/+/g, '/').replace(/\/$/, '');
  return `${normalizedOrigin}${normalizedBase}/${encodeURIComponent(token)}`;
};

export const extractShareToken = (pathname: string, routeBase = '/webapp/share') => {
  const normalizedBase = `/${routeBase}`.replace(/\/+/g, '/').replace(/\/$/, '');
  const prefix = `${normalizedBase}/`;
  if (!pathname.startsWith(prefix)) {
    return '';
  }
  const encodedToken = pathname.slice(prefix.length).split('/')[0];
  try {
    return decodeURIComponent(encodedToken || '');
  } catch {
    return '';
  }
};

export const classifyShareAccessError = (error: any): ShareAccessErrorKind => {
  const code = Number(error?.status ?? error?.code ?? error?.response?.status);
  const reason = String(
    error?.reasonCode || error?.data?.reasonCode || error?.message || error?.msg || '',
  ).toUpperCase();
  if (reason.includes('EXPIRED')) {
    return 'EXPIRED';
  }
  if (reason.includes('REVOKED') || reason.includes('INACTIVE')) {
    return 'REVOKED';
  }
  if (reason.includes('LIMIT') || reason.includes('EXHAUST')) {
    return 'EXHAUSTED';
  }
  if (code === 401 || code === 403) {
    return 'FORBIDDEN';
  }
  return 'FAILED';
};

export const parseSharedDashboardComponents = (config: unknown): SharedDashboardComponent[] => {
  try {
    const parsed = typeof config === 'string' ? JSON.parse(config) : config;
    if (!parsed || typeof parsed !== 'object' || !Array.isArray((parsed as any).components)) {
      return [];
    }
    return (parsed as any).components.slice(0, 100).map((component: any) => ({
      id: String(component?.id || ''),
      type: String(component?.type || ''),
      title: String(component?.title || '未命名组件').slice(0, 120),
      masked: Boolean(component?.masked),
      visualization: {
        chartType: String(component?.visualization?.chartType || component?.type || ''),
      },
    }));
  } catch {
    return [];
  }
};

export const componentResultFor = (
  componentData: unknown,
  componentId: string,
): SharedComponentQueryData | undefined => {
  if (Array.isArray(componentData)) {
    const entry = componentData.find((item: any) => String(item?.componentId) === componentId);
    return (entry?.data || entry?.result || entry) as SharedComponentQueryData | undefined;
  }
  if (componentData && typeof componentData === 'object') {
    return (componentData as Record<string, SharedComponentQueryData>)[componentId];
  }
  return undefined;
};

export const componentErrorFor = (componentErrors: unknown, componentId: string): string => {
  const presentError = (error: unknown): string => {
    const message =
      typeof error === 'string'
        ? error
        : String((error as any)?.message || (error as any)?.error || '');
    if (message === 'FORBIDDEN') {
      return '当前身份无权查看该组件';
    }
    if (message === 'QUERY_FAILED') {
      return '组件数据暂时不可用';
    }
    return message;
  };
  if (Array.isArray(componentErrors)) {
    const entry = componentErrors.find((item: any) => String(item?.componentId) === componentId);
    return presentError(entry?.error || entry?.message);
  }
  if (componentErrors && typeof componentErrors === 'object') {
    const error = (componentErrors as Record<string, unknown>)[componentId];
    return presentError(error);
  }
  return '';
};
