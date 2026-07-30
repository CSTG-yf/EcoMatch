export type QueryWorkflowStage =
  | 'idle'
  | 'parsing'
  | 'clarifying'
  | 'executing'
  | 'explaining'
  | 'completed'
  | 'failed'
  | 'forbidden'
  | 'timeout';

export const stageFromResponseCode = (code?: number): QueryWorkflowStage => {
  if (code === 401 || code === 403) {
    return 'forbidden';
  }
  return 'failed';
};

export const stageFromRequestError = (error: any): QueryWorkflowStage => {
  if (
    error?.code === 'ECONNABORTED' ||
    error?.code === 'ETIMEDOUT' ||
    `${error?.message || ''}`.toLowerCase().includes('timeout')
  ) {
    return 'timeout';
  }
  return stageFromResponseCode(error?.response?.status);
};

export const WORKFLOW_STAGE_TEXT: Record<QueryWorkflowStage, string> = {
  idle: '',
  parsing: '正在理解问题',
  clarifying: '等待补充查询条件',
  executing: '正在查询数据',
  explaining: '正在生成业务解释',
  completed: '问数完成',
  failed: '问数失败',
  forbidden: '无权访问该数据',
  timeout: '问数超时',
};
