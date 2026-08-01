import type { Dashboard, DashboardQuerySource } from '../Dashboard/types';
import {
  buildDashboardExportRequest,
  buildQueryExportRequest,
  dashboardSourceToQueryStruct,
} from './integration';

const source: DashboardQuerySource = {
  question: '各机构存款余额是多少？',
  dataSetId: 12,
  modelId: 7,
  masked: true,
  semanticQuery: {
    dataSetId: 12,
    modelId: 7,
    dimensions: [{ name: 'branch_code', bizName: '机构' }],
    metrics: [
      { name: 'deposit_balance', bizName: '存款余额', defaultAgg: 'AVG' },
      { name: 'customer_count', bizName: '客户数' },
    ],
    dimensionFilters: [
      {
        name: '机构',
        bizName: '机构',
        operator: 'IN',
        value: { selected: ['南京'], token: 'must-not-leak', result: [{ id: 1 }] },
      },
    ],
    dateInfo: {
      dateMode: 'BETWEEN',
      startDate: '2026-07-01',
      endDate: '2026-07-31',
      dateList: [],
    },
    limit: 800,
    sqlInfo: { correctedS2SQL: 'select secret' },
    token: 'must-not-leak',
    queryResults: [{ branch_code: '001' }],
  },
};

describe('export integration converter', () => {
  it('projects a dashboard query source into a replayable QueryStructReq', () => {
    const query = dashboardSourceToQueryStruct(source);

    expect(query).toEqual({
      dataSetId: 12,
      modelIds: [7],
      groups: ['机构'],
      aggregators: [
        { column: '存款余额', func: 'AVG' },
        { column: '客户数', func: 'SUM' },
      ],
      dimensionFilters: [
        {
          elementID: undefined,
          name: '机构',
          bizName: '机构',
          operator: 'IN',
          type: undefined,
          value: { selected: ['南京'] },
          entityName: undefined,
        },
      ],
      dateInfo: {
        dateList: [],
        dateMode: 'BETWEEN',
        period: undefined,
        startDate: '2026-07-01',
        endDate: '2026-07-31',
        text: undefined,
        unit: undefined,
      },
      limit: 800,
    });
    const serialized = JSON.stringify(query).toLowerCase();
    expect(serialized).not.toContain('sql');
    expect(serialized).not.toContain('token');
    expect(serialized).not.toContain('queryresults');
    expect(serialized).not.toContain('must-not-leak');
  });

  it('rejects sources that cannot be replayed safely', () => {
    expect(() =>
      dashboardSourceToQueryStruct({ ...source, dataSetId: undefined, semanticQuery: {} }),
    ).toThrow('缺少 dataSetId');
    expect(() =>
      dashboardSourceToQueryStruct({
        ...source,
        modelId: undefined,
        semanticQuery: { ...source.semanticQuery, modelId: undefined },
      }),
    ).toThrow('缺少模型标识');
    expect(() =>
      dashboardSourceToQueryStruct({
        ...source,
        semanticQuery: { ...source.semanticQuery, dimensions: [], metrics: [] },
      }),
    ).toThrow('缺少指标或维度');
  });

  it('builds a QUERY export request for direct ChatPage integration', () => {
    expect(buildQueryExportRequest(source, 'XLSX')).toMatchObject({
      resourceType: 'QUERY',
      format: 'XLSX',
      title: source.question,
      queries: [{ dataSetId: 12, modelIds: [7], groups: ['机构'] }],
      charts: [],
    });
  });

  it('builds dashboard queries, global filters, and safe PDF chart definitions', () => {
    const dashboard: Dashboard = {
      id: 21,
      domainId: 3,
      name: '经营分析看板',
      status: 'PUBLISHED',
      accessScope: 'PRIVATE',
      version: 2,
      config: JSON.stringify({
        globalFilters: [{ field: '机构', operator: '=', value: { code: '001', token: 'hidden' } }],
        components: [
          {
            title: '存款余额趋势',
            type: 'chart',
            visualization: { chartType: 'line' },
            query: source.semanticQuery,
          },
        ],
      }),
    };

    const request = buildDashboardExportRequest(dashboard, 'PDF');

    expect(request).toMatchObject({
      resourceType: 'DASHBOARD',
      dashboardId: 21,
      format: 'PDF',
      title: '经营分析看板',
      charts: [
        {
          queryIndex: 0,
          type: 'LINE',
          title: '存款余额趋势',
          categoryField: '机构',
          valueField: '存款余额',
        },
      ],
    });
    expect(request.queries[0].dimensionFilters).toHaveLength(2);
    expect(JSON.stringify(request)).not.toContain('hidden');

    const xlsxRequest = buildDashboardExportRequest(dashboard, 'XLSX');
    expect(xlsxRequest.charts).toEqual(request.charts);
    expect(xlsxRequest.charts[0]).toMatchObject({ type: 'LINE', queryIndex: 0 });
  });

  it('rejects empty or invalid dashboard configurations', () => {
    const dashboard = {
      id: 21,
      name: '空看板',
      config: { components: [] },
    } as Dashboard;
    expect(() => buildDashboardExportRequest(dashboard, 'XLSX')).toThrow(
      '没有可导出的语义查询组件',
    );
    expect(() => buildDashboardExportRequest({ ...dashboard, config: '{invalid' }, 'XLSX')).toThrow(
      '看板配置无效',
    );
  });
});
