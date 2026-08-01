import {
  buildControlledShareUrl,
  buildShareCreateRequest,
  classifyShareAccessError,
  componentErrorFor,
  componentResultFor,
  effectiveShareStatus,
  extractShareToken,
  normalizeAllowedUsers,
  normalizeSharePage,
  parseSharedDashboardComponents,
  remainingAccessCount,
} from './model';
import { ShareRecord } from './types';

const now = new Date('2026-08-01T00:00:00.000Z');

const share = (overrides: Partial<ShareRecord> = {}): ShareRecord => ({
  shareId: 'share-1',
  dashboardId: 10,
  identityPolicy: 'AUTHENTICATED',
  allowedUsers: [],
  status: 'ACTIVE',
  accessCount: 2,
  maxAccessCount: 5,
  watermarkEnabled: true,
  expiresAt: '2026-08-08T00:00:00.000Z',
  createdAt: '2026-08-01T00:00:00.000Z',
  ...overrides,
});

describe('controlled share model', () => {
  it('builds the exact BE-13 create payload and normalizes users', () => {
    expect(
      buildShareCreateRequest(
        10,
        {
          identityPolicy: 'USERS',
          allowedUsers: [' alice ', 'bob', 'alice'],
          expiresAt: '2026-08-08T00:00:00.000Z',
          maxAccessCount: 20,
          watermarkEnabled: true,
        },
        now,
      ),
    ).toEqual({
      dashboardId: 10,
      identityPolicy: 'USERS',
      allowedUsers: ['alice', 'bob'],
      expiresAt: '2026-08-08T00:00:00.000Z',
      maxAccessCount: 20,
      watermarkEnabled: true,
    });
    expect(normalizeAllowedUsers([' alice ', '', 'alice'])).toEqual(['alice']);
  });

  it('rejects missing user allowlists and invalid access limits', () => {
    expect(() =>
      buildShareCreateRequest(
        10,
        { identityPolicy: 'USERS', expiresAt: '2026-08-02T00:00:00.000Z' },
        now,
      ),
    ).toThrow('至少需要一个用户');
    expect(() =>
      buildShareCreateRequest(
        10,
        {
          identityPolicy: 'AUTHENTICATED',
          expiresAt: '2026-08-02T00:00:00.000Z',
          maxAccessCount: 100001,
        },
        now,
      ),
    ).toThrow('1 至 100000');
  });

  it('enforces a future expiration no later than 30 days', () => {
    expect(() =>
      buildShareCreateRequest(10, { identityPolicy: 'AUTHENTICATED', expiresAt: now }, now),
    ).toThrow('未来 30 天内');
    expect(() =>
      buildShareCreateRequest(
        10,
        { identityPolicy: 'AUTHENTICATED', expiresAt: '2026-09-01T00:00:00.001Z' },
        now,
      ),
    ).toThrow('未来 30 天内');
  });

  it('derives active, expired, revoked and exhausted management states', () => {
    expect(effectiveShareStatus(share(), now)).toBe('ACTIVE');
    expect(effectiveShareStatus(share({ status: 'REVOKED' }), now)).toBe('REVOKED');
    expect(effectiveShareStatus(share({ expiresAt: '2026-07-31T00:00:00.000Z' }), now)).toBe(
      'EXPIRED',
    );
    expect(effectiveShareStatus(share({ accessCount: 5 }), now)).toBe('EXHAUSTED');
    expect(remainingAccessCount(share())).toBe(3);
  });

  it('builds and extracts encoded token links without query parameters', () => {
    const url = buildControlledShareUrl(
      'token/with+symbols',
      'https://bank.example/',
      '/webapp/share/',
    );
    expect(url).toBe('https://bank.example/webapp/share/token%2Fwith%2Bsymbols');
    expect(extractShareToken('/webapp/share/token%2Fwith%2Bsymbols')).toBe('token/with+symbols');
    expect(extractShareToken('/somewhere/else')).toBe('');
  });

  it('keeps a generic server 403 non-disclosing while honoring explicit reason codes', () => {
    expect(classifyShareAccessError({ status: 403, message: 'Share is not available' })).toBe(
      'FORBIDDEN',
    );
    expect(
      classifyShareAccessError({ status: 403, reasonCode: 'SHARE_ACCESS_LIMIT_REACHED' }),
    ).toBe('EXHAUSTED');
    expect(classifyShareAccessError({ code: 403, reasonCode: 'SHARE_EXPIRED' })).toBe('EXPIRED');
    expect(classifyShareAccessError({ code: 500 })).toBe('FAILED');
  });

  it('normalizes wrapped pages and safely selects display-only component metadata', () => {
    expect(normalizeSharePage({ data: { list: [share()], total: 1, pageNum: 1 } })).toMatchObject({
      list: [share()],
      total: 1,
      pageNum: 1,
    });
    expect(
      parseSharedDashboardComponents(
        JSON.stringify({
          components: [
            {
              id: 'component-1',
              title: '贷款余额',
              masked: true,
              visualization: { chartType: 'bar' },
              query: { rawSql: 'must-not-be-returned' },
            },
          ],
        }),
      ),
    ).toEqual([
      {
        id: 'component-1',
        type: '',
        title: '贷款余额',
        masked: true,
        visualization: { chartType: 'bar' },
      },
    ]);
    expect(parseSharedDashboardComponents('{invalid')).toEqual([]);
  });

  it('resolves component data and isolated errors from map and list response shapes', () => {
    const result = { queryResults: [{ amount: 10 }], queryColumns: [{ name: 'amount' }] };
    expect(componentResultFor({ 'component-1': result }, 'component-1')).toBe(result);
    expect(componentResultFor([{ componentId: 'component-1', data: result }], 'component-1')).toBe(
      result,
    );
    expect(componentErrorFor({ 'component-1': '权限不足' }, 'component-1')).toBe('权限不足');
    expect(
      componentErrorFor([{ componentId: 'component-1', message: '查询超时' }], 'component-1'),
    ).toBe('查询超时');
    expect(componentErrorFor({ 'component-1': 'FORBIDDEN' }, 'component-1')).toBe(
      '当前身份无权查看该组件',
    );
    expect(componentErrorFor({ 'component-1': 'QUERY_FAILED' }, 'component-1')).toBe(
      '组件数据暂时不可用',
    );
  });
});
