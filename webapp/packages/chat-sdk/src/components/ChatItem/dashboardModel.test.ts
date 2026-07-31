import { buildDashboardQuerySource, canSaveDashboardResult } from './dashboardModel';

describe('dashboard query source', () => {
  const context = {
    id: 17,
    queryId: 29,
    modelId: 3,
    modelName: '存贷款主题',
    dataSet: { id: 5, name: '银行经营数据' },
    dimensions: [{ bizName: '机构', name: 'branch_name' }],
    metrics: [{ bizName: '存款余额', name: 'deposit_balance' }],
    dateInfo: { startDate: '2025-01-01', endDate: '2025-06-30' },
    dimensionFilters: [{ bizName: '机构', value: '江苏省B市农商行' }],
    sqlInfo: { correctSql: 'select * from secret_table' },
    properties: { token: 'never-persist-this' },
  } as any;

  it('builds a replayable semantic source without physical query details', () => {
    const source = buildDashboardQuerySource({
      question: '2025年上半年存款余额是多少？',
      context,
      data: {
        queryId: 29,
        queryState: 'SUCCESS',
        queryResults: [{ branch_name: '江苏省B市农商行', deposit_balance: 100 }],
        queryColumns: [{ name: 'branch_name' }, { name: 'deposit_balance' }],
        recommendedChart: { chartType: 'column' },
        dataMasked: true,
      } as any,
    });

    expect(source).toMatchObject({
      queryId: 29,
      parseId: 17,
      question: '2025年上半年存款余额是多少？',
      modelId: 3,
      dataSetId: 5,
      masked: true,
      chartType: 'column',
      semanticQuery: {
        queryId: 29,
        parseId: 17,
        modelId: 3,
      },
    });

    const serialized = JSON.stringify(source).toLowerCase();
    expect(serialized).not.toContain('sql');
    expect(serialized).not.toContain('token');
    expect(serialized).not.toContain('properties');
    expect(serialized).not.toContain('queryresults');
  });

  it('only enables saving successful non-empty authorized results', () => {
    expect(
      canSaveDashboardResult({
        queryState: 'SUCCESS',
        queryResults: [{ value: 1 }],
      } as any)
    ).toEqual({ enabled: true });
    expect(
      canSaveDashboardResult({
        queryState: 'SUCCESS',
        queryResults: [],
      } as any)
    ).toEqual({ enabled: false, reason: '空结果不能保存到看板' });
    expect(
      canSaveDashboardResult({
        queryState: 'SUCCESS',
        queryResults: [{ value: 1 }],
        queryAuthorization: { authorized: false },
      } as any)
    ).toEqual({ enabled: false, reason: '当前结果无权保存到看板' });
  });
});
