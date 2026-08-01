import {
  applyGlobalFilters,
  createComponentFromDraft,
  parseDashboardConfig,
  reorderComponents,
} from '../../supersonic-fe/src/pages/Dashboard/model';

const component = createComponentFromDraft(
  {
    sourceQueryId: 11,
    sourceParseId: 2,
    title: '贷款余额',
    question: '各机构贷款余额',
    chartType: 'BAR',
    query: {
      dataSetId: 3,
      groups: ['org_name'],
      aggregators: [{ column: 'loan_balance', func: 'SUM' }],
      dimensionFilters: [
        { relation: 'FILTER', bizName: 'region', operator: 'IN', value: ['江苏'] },
      ],
      metricFilters: [],
      limit: 1000,
      queryType: 'AGGREGATE',
    },
  },
  'one'
);

describe('dashboard model integration', () => {
  it('falls back safely for malformed persisted config', () => {
    expect(parseDashboardConfig('{bad-json')).toEqual({
      schemaVersion: 1,
      refreshInterval: 0,
      globalFilters: [],
      components: [],
    });
  });

  it('reorders components and normalizes order', () => {
    const second = { ...component, id: 'two', layout: { order: 1, span: 1 as const } };
    const reordered = reorderComponents([component, second], 'two', 'one');
    expect(reordered.map(item => item.id)).toEqual(['two', 'one']);
    expect(reordered.map(item => item.layout.order)).toEqual([0, 1]);
  });

  it('overlays global filters without dropping unrelated query filters', () => {
    const query = applyGlobalFilters(component, [
      { id: 'f1', label: '地区', bizName: 'region', operator: 'IN', value: ['浙江'] },
      { id: 'f2', label: '机构', bizName: 'org_name', operator: 'IN', value: ['A行'] },
    ]);
    expect(query.dimensionFilters).toEqual([
      { relation: 'FILTER', bizName: 'region', operator: 'IN', value: ['浙江'] },
      { relation: 'FILTER', bizName: 'org_name', operator: 'IN', value: ['A行'] },
    ]);
  });
});
