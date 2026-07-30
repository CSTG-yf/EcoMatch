import {
  formatFileSize,
  isSecurityWriter,
  nextAlertStatuses,
  normalizePage,
  requiresDispositionComment,
} from './model';

describe('security operations model', () => {
  it('enforces the backend alert transition contract', () => {
    expect(nextAlertStatuses('NEW')).toEqual(['ACKNOWLEDGED', 'CLOSED', 'DISMISSED']);
    expect(nextAlertStatuses('ACKNOWLEDGED')).toEqual(['RESOLVED', 'CLOSED', 'DISMISSED']);
    expect(nextAlertStatuses('CLOSED')).toEqual([]);
  });

  it('requires comments for terminal dispositions', () => {
    expect(requiresDispositionComment('ACKNOWLEDGED')).toBe(false);
    expect(requiresDispositionComment('RESOLVED')).toBe(true);
    expect(requiresDispositionComment('DISMISSED')).toBe(true);
  });

  it('normalizes wrapped and direct page responses', () => {
    expect(normalizePage({ data: { list: [{ id: 1 }], total: 1 } })).toEqual({
      data: [{ id: 1 }],
      total: 1,
      success: true,
    });
    expect(normalizePage({ list: [], total: 0 })).toEqual({
      data: [],
      total: 0,
      success: true,
    });
  });

  it('formats sizes without exposing raw payloads', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB');
    expect(formatFileSize(2 * 1024 * 1024)).toBe('2.0 MB');
  });

  it('separates read and write security roles', () => {
    expect(isSecurityWriter({ roles: ['SECURITY_ADMIN'] } as API.CurrentUser)).toBe(true);
    expect(isSecurityWriter({ roles: ['SECURITY_AUDITOR'] } as API.CurrentUser)).toBe(false);
  });
});
