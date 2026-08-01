import {
  applyDashboardGlobalFilters,
  addDashboardQueryComponent,
  buildDashboardConfig,
  canEditDashboard,
  canManageDashboard,
  classifyDashboardError,
  createDashboardComponent,
  createEmptyDashboardConfig,
  dashboardColumnKey,
  moveComponent,
  normalizeDashboardPage,
  parseDashboardConfig,
  parseDashboardRouteId,
  dashboardRouteParamFromPath,
  requireDashboardSourceDomain,
  serializeDashboardConfig,
  serializeDashboardWriteConfig,
} from './model';

describe('dashboard model', () => {
  it('accepts only positive integer dashboard route ids', () => {
    expect(parseDashboardRouteId('7')).toBe(7);
    expect(parseDashboardRouteId('0')).toBeUndefined();
    expect(parseDashboardRouteId('-1')).toBeUndefined();
    expect(parseDashboardRouteId('7x')).toBeUndefined();
  });

  it('extracts dashboard ids from editor and viewer paths', () => {
    expect(dashboardRouteParamFromPath('/dashboard/7/edit')).toBe('7');
    expect(dashboardRouteParamFromPath('/webapp/dashboard/8/view')).toBe('8');
    expect(dashboardRouteParamFromPath('/dashboard')).toBeUndefined();
  });
  it('normalizes wrapped and direct page responses', () => {
    expect(normalizeDashboardPage({ data: { list: [{ id: 1 }], total: 1 } })).toEqual({
      data: [{ id: 1 }],
      total: 1,
      success: true,
    });
    expect(normalizeDashboardPage({ list: [], total: 0 })).toEqual({
      data: [],
      total: 0,
      success: true,
    });
  });

  it('uses the semantic business name to read query result rows', () => {
    expect(dashboardColumnKey({ name: 'deposit_balance', bizName: '存款余额' })).toBe('存款余额');
    expect(dashboardColumnKey({ name: 'deposit_balance' })).toBe('deposit_balance');
  });

  it('falls back to a safe config when persisted JSON is invalid', () => {
    expect(parseDashboardConfig('{not-json')).toEqual(createEmptyDashboardConfig());
    expect(parseDashboardConfig(null)).toEqual(createEmptyDashboardConfig());
  });

  it('creates and lays out semantic components inside a 12-column canvas', () => {
    const component = createDashboardComponent({
      queryId: 29,
      parseId: 17,
      question: '存款余额是多少？',
      modelId: 3,
      dataSetId: 5,
      masked: true,
      chartType: 'column',
      semanticQuery: {
        queryId: 29,
        parseId: 17,
        modelId: 3,
        metrics: [{ bizName: '存款余额' }],
      },
    });
    const moved = moveComponent(component, { x: 10, y: -4, w: 8, h: 0 });

    expect(moved.layout).toEqual({ x: 4, y: 0, w: 8, h: 2 });
    expect(moved.masked).toBe(true);
  });

  it('serializes only the supported schema and rejects forbidden keys', () => {
    const config = buildDashboardConfig({
      ...createEmptyDashboardConfig(),
      globalFilters: [
        {
          field: '客户类型',
          operator: 'EQ',
          value: { safe: '对公', token: 'never-persist-this' },
        },
      ],
      components: [
        {
          id: 'component-1',
          type: 'chart',
          title: '存款余额',
          layout: { x: 0, y: 0, w: 6, h: 4 },
          visualization: { chartType: 'column' },
          query: {
            queryId: 29,
            parseId: 17,
            modelId: 3,
            sqlInfo: { sql: 'select 1' },
            token: 'secret',
            metrics: [{ bizName: '存款余额' }],
          } as any,
        },
      ],
    });

    const serialized = serializeDashboardConfig(config);
    expect(serialized).toMatchObject({
      schemaVersion: '1.0',
      layout: { columns: 12, rowHeight: 72 },
      components: [
        {
          query: {
            queryId: 29,
            parseId: 17,
            modelId: 3,
            metrics: [{ bizName: '存款余额' }],
          },
        },
      ],
    });
    expect(JSON.stringify(serialized).toLowerCase()).not.toContain('sql');
    expect(JSON.stringify(serialized).toLowerCase()).not.toContain('token');
    expect(serialized.globalFilters[0].value).toEqual({ safe: '对公' });
    expect(serialized.globalFilters[0].operator).toBe('=');
    expect(typeof serializeDashboardWriteConfig(config)).toBe('string');
    expect(JSON.parse(serializeDashboardWriteConfig(config))).toEqual(serialized);
  });

  it('classifies permissions and optimistic lock conflicts separately', () => {
    expect(classifyDashboardError({ code: 403 })).toBe('FORBIDDEN');
    expect(classifyDashboardError({ code: 409 })).toBe('CONFLICT');
    expect(classifyDashboardError({ response: { status: 412 } })).toBe('CONFLICT');
    expect(
      classifyDashboardError({
        code: 400,
        msg: 'Dashboard version conflict',
      }),
    ).toBe('CONFLICT');
    expect(classifyDashboardError({ code: 500 })).toBe('FAILED');
    expect(canEditDashboard('DRAFT')).toBe(true);
    expect(canEditDashboard('PUBLISHED')).toBe(true);
    expect(canEditDashboard('DISABLED')).toBe(false);
  });

  it('applies non-empty global filters without replacing component filters', () => {
    expect(
      applyDashboardGlobalFilters(
        {
          queryId: 29,
          dimensionFilters: [{ bizName: '机构', operator: 'EQ', value: 'B市' }],
        },
        [
          { field: '客户类型', operator: 'EQ', value: '对公' },
          { field: '  ', operator: 'EQ', value: '忽略' },
        ],
      ),
    ).toMatchObject({
      queryId: 29,
      dimensionFilters: [
        { bizName: '机构', operator: 'EQ', value: 'B市' },
        { name: '客户类型', bizName: '客户类型', operator: '=', value: '对公' },
      ],
    });
  });

  it('keeps shared dashboards read-only unless the viewer can manage them', () => {
    expect(canManageDashboard('owner', { name: 'viewer' }, false)).toBe(false);
    expect(canManageDashboard('owner', { name: 'owner' }, false)).toBe(true);
    expect(canManageDashboard('owner', { name: 'domain-admin' }, true)).toBe(true);
    expect(canManageDashboard('owner', { name: 'root', superAdmin: true }, false)).toBe(true);
  });

  it('appends a query result with the confirmed title and refresh policy', () => {
    const config = addDashboardQueryComponent(
      createEmptyDashboardConfig(),
      {
        queryId: 29,
        parseId: 17,
        question: '存款余额是多少？',
        semanticQuery: { queryId: 29, parseId: 17, dataSetId: 5, modelId: 3 },
      },
      '存款余额趋势',
      300,
    );

    expect(config.refreshIntervalSeconds).toBe(300);
    expect(config.components).toHaveLength(1);
    expect(config.components[0].title).toBe('存款余额趋势');
    expect(() =>
      addDashboardQueryComponent(
        {
          ...config,
          components: Array.from({ length: 100 }, (_, index) => ({
            ...config.components[0],
            id: `component-${index}`,
          })),
        },
        {
          question: '新增组件',
          semanticQuery: {},
        },
        '新增组件',
        0,
      ),
    ).toThrow('看板组件已达到 100 个上限');
  });

  it('requires a trusted source domain before opening the save flow', () => {
    expect(
      requireDashboardSourceDomain({
        domainId: 10,
        question: '存款余额是多少？',
        semanticQuery: {},
      }),
    ).toBe(10);
    expect(() =>
      requireDashboardSourceDomain({
        question: '存款余额是多少？',
        semanticQuery: {},
      }),
    ).toThrow('无法确认问数结果所属主题域');
  });
});
