jest.mock('./style.less', () => ({}));
jest.mock('./service', () => ({ exportApi: {} }));
jest.mock('./useExportTasks', () => ({ useExportTasks: jest.fn() }));
jest.mock('@umijs/max', () => ({
  history: { replace: jest.fn() },
  useLocation: () => ({ state: undefined }),
}));

describe('export center public module', () => {
  it('loads the page and reusable locked-source component entry points', () => {
    const module = require('./index');
    expect(typeof module.default).toBe('function');
    expect(typeof module.CreateExport).toBe('function');
    expect(typeof module.buildQueryExportRequest).toBe('function');
    expect(typeof module.buildDashboardExportRequest).toBe('function');
  });
});
