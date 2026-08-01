import request, { TOKEN_KEY } from '@/services/request';
import { ShareAccessResponse, ShareCreateRequest, SharePage, ShareRecord } from './types';
import { unwrapShareResponse } from './model';

const shareBase = `${process.env.API_BASE_URL || '/api/semantic/'}share`;
const sharedDashboardDataUrl = '/api/chat/query/sharedDashboardData';

export class ShareAccessHttpError extends Error {
  status: number;
  code?: number;
  reasonCode?: string;

  constructor(message: string, status: number, code?: number, reasonCode?: string) {
    super(message);
    this.name = 'ShareAccessHttpError';
    this.status = status;
    this.code = code;
    this.reasonCode = reasonCode;
  }
}

export const createShare = async (data: ShareCreateRequest): Promise<ShareRecord> =>
  unwrapShareResponse<ShareRecord>(
    await request(shareBase, {
      method: 'POST',
      data,
    }),
  );

export const listShares = async (params: {
  pageNum?: number;
  pageSize?: number;
}): Promise<SharePage> =>
  unwrapShareResponse<SharePage>(
    await request(shareBase, {
      method: 'GET',
      params,
    }),
  );

export const getShare = async (shareId: string): Promise<ShareRecord> =>
  unwrapShareResponse<ShareRecord>(
    await request(`${shareBase}/${encodeURIComponent(shareId)}`, { method: 'GET' }),
  );

export const revokeShare = async (shareId: string): Promise<void> => {
  unwrapShareResponse<unknown>(
    await request(`${shareBase}/${encodeURIComponent(shareId)}`, { method: 'DELETE' }),
  );
};

/**
 * The shared request helper redirects every non-dashboard 403 to login. This endpoint uses a
 * scoped fetch so the controlled-share page can render the server's non-disclosing 403 state.
 * The raw share token is sent only in the POST body and is never included in a request URL.
 */
export const accessShare = async (token: string): Promise<ShareAccessResponse> => {
  if (!token || token.length < 40 || token.length > 100) {
    throw new ShareAccessHttpError('分享链接无效', 403, 403);
  }
  const authToken = localStorage.getItem(TOKEN_KEY);
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
    headers.auth = `Bearer ${authToken}`;
  }
  headers['Content-Type'] = 'application/json';
  const response = await fetch(sharedDashboardDataUrl, {
    method: 'POST',
    headers,
    body: JSON.stringify({ token }),
    credentials: 'same-origin',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  });

  let payload: any = {};
  try {
    payload = await response.json();
  } catch {
    payload = {};
  }
  const code = payload?.code == null ? undefined : Number(payload.code);
  if (!response.ok || (code != null && ![0, 200].includes(code))) {
    throw new ShareAccessHttpError(
      String(payload?.msg || payload?.message || '分享不可用'),
      response.status || code || 500,
      code,
      payload?.reasonCode || payload?.data?.reasonCode,
    );
  }
  return unwrapShareResponse<ShareAccessResponse>(payload);
};
