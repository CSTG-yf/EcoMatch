import {
  inferVisualizationType,
  isVisualizationCompatible,
  mergeDimensionFilters,
  normalizeVisualizationType,
  resolveVisualizationType,
  visualizationOptions,
} from './visualizationModel';
import * as fs from 'fs';
import * as path from 'path';

const column = (bizName: string, showType: string) =>
  ({
    authorized: true,
    name: bizName,
    nameEn: bizName,
    bizName,
    showType,
    type: showType,
  } as any);

describe('visualization model', () => {
  it('normalizes the backend metric card name', () => {
    expect(normalizeVisualizationType('metric_card')).toBe('KPI_CARD');
    expect(normalizeVisualizationType('KPI_CARD')).toBe('KPI_CARD');
    expect(normalizeVisualizationType('SCRIPT')).toBeUndefined();
  });

  it.each([
    ['KPI_CARD', [column('amount', 'NUMBER')], [{ amount: 10 }]],
    [
      'TABLE',
      [column('branch', 'CATEGORY'), column('amount', 'NUMBER')],
      [{ branch: '南京', amount: 10 }],
    ],
    [
      'LINE',
      [column('month', 'DATE'), column('amount', 'NUMBER')],
      [
        { month: '2026-01', amount: 10 },
        { month: '2026-02', amount: 12 },
      ],
    ],
    [
      'BAR',
      [column('branch', 'CATEGORY'), column('amount', 'NUMBER')],
      [
        { branch: '南京', amount: 10 },
        { branch: '苏州', amount: 12 },
      ],
    ],
    [
      'PIE',
      [column('branch', 'CATEGORY'), column('amount', 'NUMBER')],
      [
        { branch: '南京', amount: 10 },
        { branch: '苏州', amount: 12 },
      ],
    ],
    [
      'COMBO',
      [column('month', 'DATE'), column('amount', 'NUMBER'), column('rate', 'NUMBER')],
      [
        { month: '2026-01', amount: 10, rate: 0.1 },
        { month: '2026-02', amount: 12, rate: 0.2 },
      ],
    ],
  ])('accepts compatible %s recommendations', (type, columns, rows) => {
    expect(isVisualizationCompatible(type as any, columns, rows)).toBe(true);
    expect(resolveVisualizationType({ chartType: type } as any, columns, rows)).toBe(type);
  });

  it('falls back when recommended fields are absent or a pie contains negative values', () => {
    const columns = [column('branch', 'CATEGORY'), column('amount', 'NUMBER')];
    const rows = [
      { branch: '南京', amount: -1 },
      { branch: '苏州', amount: 2 },
    ];
    expect(
      resolveVisualizationType({ chartType: 'PIE', metricFields: ['missing'] }, columns, rows)
    ).toBe('BAR');
    expect(inferVisualizationType(columns, rows)).toBe('BAR');
  });

  it('keeps only compatible unique candidates and always offers the data table', () => {
    const columns = [column('branch', 'CATEGORY'), column('amount', 'NUMBER')];
    const rows = [
      { branch: '南京', amount: 1 },
      { branch: '苏州', amount: 2 },
    ];
    expect(
      visualizationOptions(
        { chartType: 'BAR' },
        [{ chartType: 'BAR' }, { chartType: 'PIE' }, { chartType: 'COMBO' }],
        columns,
        rows
      )
    ).toEqual(['BAR', 'PIE', 'TABLE']);
  });

  it('preserves unrelated filters and lets linked filters replace the same dimension', () => {
    expect(
      mergeDimensionFilters(
        [
          { bizName: 'organization', value: '全行' },
          { bizName: 'customer_type', value: '个人' },
        ] as any,
        [{ bizName: 'organization', value: '南京分行' }] as any
      )
    ).toEqual([
      { bizName: 'customer_type', value: '个人' },
      { bizName: 'organization', value: '南京分行' },
    ]);
  });

  it('matches every held-out DATA-03 chart recommendation', () => {
    const datasetPath = path.resolve(
      process.cwd(),
      '../../../evaluation/bank_chart_explanation/test.jsonl'
    );
    const samples = fs
      .readFileSync(datasetPath, 'utf8')
      .trim()
      .split(/\r?\n/)
      .map(line => JSON.parse(line));
    const matches = samples.filter(sample => {
      const rows = sample.result.rows.map((values: any[]) =>
        sample.result.columns.reduce(
          (row: Record<string, any>, name: string, index: number) => ({
            ...row,
            [name]: values[index],
          }),
          {}
        )
      );
      const columns = sample.result.columns.map((name: string, index: number) => {
        const firstValue = sample.result.rows[0]?.[index];
        const showType = name.includes('date')
          ? 'DATE'
          : typeof firstValue === 'number'
          ? 'NUMBER'
          : 'CATEGORY';
        return column(name, showType);
      });
      return (
        resolveVisualizationType(
          { chartType: sample.chartAnnotation.recommended },
          columns,
          rows
        ) === sample.chartAnnotation.recommended
      );
    });

    expect(matches).toHaveLength(samples.length);
    expect(matches.length / samples.length).toBeGreaterThanOrEqual(0.9);
  });
});
