import { buildDashboardQueryDraft } from './dashboardDraft';

describe('dashboard query draft', () => {
  it('keeps only governed structured query fields', () => {
    const draft = buildDashboardQueryDraft('各机构贷款余额', {
      queryId: 12,
      recommendedChart: { chartType: 'BAR' },
      chatContext: {
        id: 7,
        aggType: 'SUM',
        queryType: 'AGGREGATE',
        dataSet: { id: 3, name: '贷款主题', domainId: 9 },
        dimensions: [{ bizName: 'org_name' }],
        metrics: [{ bizName: 'loan_balance', defaultAgg: 'SUM' }],
        dimensionFilters: [{ bizName: 'region', operator: 'IN', value: ['江苏'] }],
        dateInfo: { startDate: '2026-01-01', endDate: '2026-06-30' },
        sqlInfo: { querySQL: 'select secret from account' },
      },
    } as any);

    expect(draft).toMatchObject({
      domainId: 9,
      sourceQueryId: 12,
      sourceParseId: 7,
      chartType: 'BAR',
      query: {
        dataSetId: 3,
        groups: ['org_name'],
        aggregators: [{ column: 'loan_balance', func: 'SUM' }],
      },
    });
    expect(JSON.stringify(draft)).not.toContain('querySQL');
    expect(JSON.stringify(draft)).not.toContain('secret');
  });

  it('rejects incomplete query results', () => {
    expect(buildDashboardQueryDraft('无效结果', { queryId: 1 } as any)).toBeUndefined();
  });
});
