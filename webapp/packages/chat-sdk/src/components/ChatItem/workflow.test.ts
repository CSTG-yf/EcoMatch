import {
  stageFromRequestError,
  stageFromResponseCode,
  WORKFLOW_STAGE_TEXT,
} from './workflow';

describe('query workflow state', () => {
  it('classifies forbidden responses', () => {
    expect(stageFromResponseCode(401)).toBe('forbidden');
    expect(stageFromResponseCode(403)).toBe('forbidden');
    expect(stageFromRequestError({ response: { status: 403 } })).toBe('forbidden');
  });

  it('classifies timeout errors independently from generic failures', () => {
    expect(stageFromRequestError({ code: 'ECONNABORTED' })).toBe('timeout');
    expect(stageFromRequestError({ message: 'request timeout after 30s' })).toBe('timeout');
    expect(stageFromRequestError(new Error('network unavailable'))).toBe('failed');
  });

  it('provides reader-facing labels for every stage', () => {
    expect(WORKFLOW_STAGE_TEXT.parsing).toBe('正在理解问题');
    expect(WORKFLOW_STAGE_TEXT.executing).toBe('正在查询数据');
    expect(WORKFLOW_STAGE_TEXT.explaining).toBe('正在生成业务解释');
    expect(WORKFLOW_STAGE_TEXT.forbidden).toBe('无权访问该数据');
  });
});
