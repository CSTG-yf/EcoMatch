import {
  buildClarifiedQuestion,
  buildContextAdjustmentQuestion,
  clarificationComplete,
  contextEntries,
  requiresMultipleSelection,
  shouldAwaitClarification,
} from './contextModel';

describe('multi-turn context model', () => {
  it('uses the latest successful turn as the effective inherited context', () => {
    expect(
      contextEntries({
        turns: [
          { metrics: ['贷款余额'] },
          {
            metrics: ['不良贷款率', '不良贷款率'],
            dimensions: ['机构'],
            dateInfo: '2026年一季度',
            filters: ['机构 = 南京分行', '贷款类型 = 对公'],
          },
        ],
      })
    ).toEqual([
      { kind: 'metric', label: '指标', value: '不良贷款率' },
      { kind: 'dimension', label: '维度', value: '机构' },
      { kind: 'date', label: '时间', value: '2026年一季度' },
      { kind: 'organization', label: '机构', value: '机构 = 南京分行' },
      { kind: 'filter', label: '筛选', value: '贷款类型 = 对公' },
    ]);
  });

  it('builds explicit context adjustment questions', () => {
    expect(
      buildContextAdjustmentQuestion('查询不良贷款率', 'remove', {
        kind: 'date',
        label: '时间',
        value: '2026年一季度',
      })
    ).toContain('去掉时间“2026年一季度”');
    expect(buildContextAdjustmentQuestion('查询不良贷款率', 'reset')).toBe(
      '清空上下文，重新查询：查询不良贷款率'
    );
    expect(buildContextAdjustmentQuestion('查询不良贷款率', 'reinterpret')).toBe(
      '不参考之前，重新解释：查询不良贷款率'
    );
  });

  it('requires every clarification and preserves multi-select answers', () => {
    const clarifications = [
      { type: 'METRIC', question: '请选择指标', options: ['贷款余额', '不良贷款率'] },
      { type: 'TIME', question: '请选择时间', options: ['月末', '季末'] },
    ];
    const selections = { '0': ['贷款余额', '不良贷款率'], '1': ['季末'] };

    expect(requiresMultipleSelection(clarifications[0])).toBe(true);
    expect(requiresMultipleSelection(clarifications[1])).toBe(false);
    expect(shouldAwaitClarification({ clarificationRequired: true, clarifications } as any)).toBe(
      true
    );
    expect(shouldAwaitClarification({ clarificationRequired: true } as any)).toBe(false);
    expect(clarificationComplete(clarifications, selections)).toBe(true);
    expect(buildClarifiedQuestion('最近贷款情况怎么样', clarifications, selections)).toBe(
      '最近贷款情况怎么样；请选择指标：贷款余额、不良贷款率；请选择时间：季末'
    );
  });
});
