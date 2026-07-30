const DEVELOPER_ROLES = new Set(['DATA_ADMIN', 'BI_ADMIN', 'DEVELOPER']);

export const canViewDeveloperDiagnostics = (currentUser?: API.CurrentUser) =>
  Boolean(
    currentUser?.superAdmin ||
      currentUser?.isAdmin === 1 ||
      currentUser?.roles?.some(
        (role) => typeof role === 'string' && DEVELOPER_ROLES.has(role.toUpperCase()),
      ),
  );
