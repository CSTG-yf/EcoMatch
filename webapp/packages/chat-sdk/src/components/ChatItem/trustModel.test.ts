import {
  confidenceLevel,
  correctionRecords,
  filterSummaries,
  mappingConfidence,
  sqlValidationLabel,
} from './trustModel';

describe('SQL trust model', () => {
  it('calculates mapping confidence only from bounded backend similarities', () => {
    expect(
      mappingConfidence([
        { similarity: 1 },
        { similarity: 0.8 },
        { similarity: 9 },
        { similarity: Number.NaN },
      ])
    ).toBeCloseTo(0.9);
    expect(confidenceLevel(0.9)).toEqual({ label: '高', color: 'success' });
    expect(confidenceLevel(undefined)).toEqual({ label: '未提供', color: 'default' });
  });

  it('uses the backend SQL evaluation instead of inferring validation', () => {
    expect(sqlValidationLabel({ sqlEvaluation: { isValidated: true } } as any)).toEqual({
      label: '校验通过',
      status: 'success',
    });
    expect(sqlValidationLabel({ sqlEvaluation: { isValidated: false } } as any)).toEqual({
      label: '校验未通过',
      status: 'error',
    });
    expect(sqlValidationLabel({ sqlInfo: { querySQL: 'select 1' } } as any).label).toBe(
      '已生成执行 SQL'
    );
  });

  it('records semantic correction, validation and physical optimization separately', () => {
    expect(
      correctionRecords(
        {
          parsedS2SQL: 'SELECT a FROM t',
          correctedS2SQL: 'SELECT a FROM t WHERE dt = 1',
          querySQL: 'SELECT a FROM fact_t WHERE dt = 1',
          correctedQuerySQL: 'SELECT a FROM fact_t WHERE dt = 1 LIMIT 100',
        },
        true
      ).map(record => record.status)
    ).toEqual(['completed', 'changed', 'completed', 'changed']);
  });

  it('summarizes date and dimension filters for business confirmation', () => {
    expect(
      filterSummaries({
        dateInfo: { text: '2026年一季度' },
        dimensionFilters: [{ name: '机构', operator: '=', value: ['南京分行', '苏州分行'] }],
      } as any)
    ).toEqual(['2026年一季度', '机构 = 南京分行、苏州分行']);
  });
});
