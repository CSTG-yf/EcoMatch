import request from '@/services/request';

jest.mock('@/services/request', () => ({
  __esModule: true,
  default: jest.fn(),
  TOKEN_KEY: 'SUPERSONIC_TOKEN',
}));

const mockedRequest = request as jest.MockedFunction<typeof request>;
const originalApiBaseUrl = process.env.API_BASE_URL;
process.env.API_BASE_URL = '/api/semantic/';

const { accessShare, createShare, listShares, revokeShare } = require('./service');

describe('controlled share service', () => {
  beforeEach(() => {
    mockedRequest.mockReset();
    localStorage.clear();
    global.fetch = jest.fn();
  });

  afterAll(() => {
    if (originalApiBaseUrl == null) {
      delete process.env.API_BASE_URL;
    } else {
      process.env.API_BASE_URL = originalApiBaseUrl;
    }
  });

  it('calls the formal create, list and revoke endpoints', async () => {
    mockedRequest
      .mockResolvedValueOnce({ data: { shareId: 'share-1', token: 'one-time-token' } } as any)
      .mockResolvedValueOnce({ data: { list: [], total: 0 } } as any)
      .mockResolvedValueOnce({ code: 200 } as any);

    const payload = {
      dashboardId: 10,
      identityPolicy: 'AUTHENTICATED',
      allowedUsers: [],
      expiresAt: '2026-08-08T00:00:00.000Z',
      maxAccessCount: 20,
      watermarkEnabled: true,
    };
    await createShare(payload);
    await listShares({ pageNum: 2, pageSize: 10 });
    await revokeShare('share/id');

    expect(mockedRequest).toHaveBeenNthCalledWith(1, '/api/semantic/share', {
      method: 'POST',
      data: payload,
    });
    expect(mockedRequest).toHaveBeenNthCalledWith(2, '/api/semantic/share', {
      method: 'GET',
      params: { pageNum: 2, pageSize: 10 },
    });
    expect(mockedRequest).toHaveBeenNthCalledWith(3, '/api/semantic/share/share%2Fid', {
      method: 'DELETE',
    });
  });

  it('accesses a token with no-store and does not persist the share token', async () => {
    localStorage.setItem('SUPERSONIC_TOKEN', 'login-token');
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        code: 200,
        data: {
          shareId: 'share-1',
          dashboard: { id: 10, name: '经营看板' },
          accessedAt: '2026-08-01T00:00:00.000Z',
          componentData: {},
          componentErrors: {},
        },
      }),
    });
    const token = 'a'.repeat(43);

    await expect(accessShare(token)).resolves.toMatchObject({ shareId: 'share-1' });
    expect(global.fetch).toHaveBeenCalledWith('/api/chat/query/sharedDashboardData', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer login-token',
        auth: 'Bearer login-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ token }),
      credentials: 'same-origin',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    });
    expect(localStorage.getItem('shareToken')).toBeNull();
    expect(localStorage.getItem(token)).toBeNull();
  });

  it('surfaces the formal non-disclosing 403 without redirecting', async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ code: 403, msg: 'Share is not available' }),
    });

    await expect(accessShare('b'.repeat(43))).rejects.toMatchObject({
      status: 403,
      code: 403,
      message: 'Share is not available',
    });
  });

  it('rejects malformed tokens before making a request', async () => {
    await expect(accessShare('short')).rejects.toMatchObject({ status: 403 });
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
