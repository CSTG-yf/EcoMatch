jest.mock('./style.less', () => ({}));
jest.mock('./service', () => ({ exportApi: {} }));
jest.mock('./useExportTasks', () => ({ useExportTasks: jest.fn() }));
jest.mock('@umijs/max', () => ({
  history: { replace: jest.fn(), push: jest.fn() },
  useLocation: () => ({ state: undefined }),
  useModel: () => ({ initialState: undefined }),
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

describe('export task deletion', () => {
  const React = require('react');
  const { act, createElement } = React;
  const { createRoot } = require('react-dom/client');
  const { message } = require('antd');
  const ExportTaskCard = require('./components/ExportTaskCard').default;
  const { useExportTasks } = jest.requireActual('./useExportTasks');
  const { exportApi } = require('./service');

  const baseTask = {
    taskId: 'task-1',
    resourceType: 'QUERY',
    resourceId: 'task-1',
    format: 'XLSX',
    status: 'SUCCEEDED',
    downloadable: true,
    fileName: '网点存款明细.xlsx',
    createdAt: '2026-08-01T08:00:00Z',
    expiresAt: '2026-08-02T08:00:00Z',
  };

  const click = (element: Element) =>
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

  const renderCard = (task: any, onDelete = jest.fn()) => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(
        createElement(ExportTaskCard, {
          task,
          loading: false,
          onRefresh: jest.fn(),
          onDownload: jest.fn(),
          onRetry: jest.fn(),
          onDelete,
        }),
      );
    });
    return { container, root, onDelete };
  };

  beforeAll(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    if (!window.matchMedia) {
      window.matchMedia = ((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: jest.fn(),
        removeListener: jest.fn(),
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        dispatchEvent: jest.fn(),
      })) as any;
    }
  });

  beforeEach(() => {
    exportApi.list = jest.fn().mockResolvedValue({ list: [baseTask], total: 1 });
    exportApi.remove = jest.fn().mockResolvedValue(undefined);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    jest.restoreAllMocks();
  });

  it('offers a delete action for every task status', () => {
    for (const status of ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED']) {
      const { container, root } = renderCard({ ...baseTask, status, downloadable: false });
      const deleteButton = Array.from(container.querySelectorAll('button')).find((button) =>
        /删\s*除/.test(button.textContent || ''),
      );
      expect(deleteButton).toBeTruthy();
      act(() => root.unmount());
    }
  });

  it('requires a Popconfirm before deleting a task card', async () => {
    const { container, root, onDelete } = renderCard(baseTask);
    const deleteButton = Array.from(container.querySelectorAll('button')).find((button) =>
      /删\s*除/.test(button.textContent || ''),
    ) as HTMLButtonElement;

    await act(async () => {
      click(deleteButton);
    });

    expect(document.body.textContent).toContain('删除后文件不可恢复，确定删除该导出任务？');
    expect(onDelete).not.toHaveBeenCalled();
    const confirmButton = document.querySelector(
      '.ant-popover .ant-btn-primary',
    ) as HTMLButtonElement;
    expect(confirmButton).toBeTruthy();
    await act(async () => {
      click(confirmButton);
    });
    expect(onDelete).toHaveBeenCalledWith(baseTask);
    act(() => root.unmount());
  });

  it('removes the task from the list and confirms after a successful delete', async () => {
    const success = jest.spyOn(message, 'success').mockImplementation(jest.fn());
    const state: { current?: ReturnType<typeof useExportTasks> } = {};
    const Harness = () => {
      state.current = useExportTasks();
      return null;
    };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(createElement(Harness));
    });
    expect(state.current?.tasks).toHaveLength(1);

    await act(async () => {
      await state.current?.remove(state.current.tasks[0]);
    });

    expect(exportApi.remove).toHaveBeenCalledWith('task-1');
    expect(state.current?.tasks).toHaveLength(0);
    expect(success).toHaveBeenCalledWith('导出任务已删除');
    act(() => root.unmount());
  });

  it('keeps the task and surfaces the classified error when delete fails', async () => {
    const success = jest.spyOn(message, 'success').mockImplementation(jest.fn());
    exportApi.remove = jest.fn().mockRejectedValue({ status: 403 });
    const state: { current?: ReturnType<typeof useExportTasks> } = {};
    const Harness = () => {
      state.current = useExportTasks();
      return null;
    };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(createElement(Harness));
    });

    await act(async () => {
      await state.current?.remove(state.current.tasks[0]);
    });

    expect(state.current?.tasks).toHaveLength(1);
    expect(state.current?.pageError?.kind).toBe('FORBIDDEN');
    expect(success).not.toHaveBeenCalled();
    act(() => root.unmount());
  });
});
