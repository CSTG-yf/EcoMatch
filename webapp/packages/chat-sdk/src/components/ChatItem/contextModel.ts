import {
  BankClarificationType,
  BankIntentResultType,
  MultiTurnContextType,
  MultiTurnContextTurnType,
} from '../../common/type';

export type ContextEntry = {
  kind: 'metric' | 'dimension' | 'organization' | 'date' | 'filter';
  label: string;
  value: string;
};

const ORGANIZATION_PATTERN = /机构|分行|支行|网点|农商行|organization|\borg\b/i;

const unique = (values?: string[]) =>
  Array.from(new Set((values || []).map(value => value?.trim()).filter(Boolean))) as string[];

export const latestContextTurn = (
  context?: MultiTurnContextType
): MultiTurnContextTurnType | undefined => {
  const turns = context?.turns || [];
  return turns[turns.length - 1];
};

export const contextEntries = (context?: MultiTurnContextType): ContextEntry[] => {
  const turn = latestContextTurn(context);
  if (!turn) {
    return [];
  }
  const entries: ContextEntry[] = [];
  unique(turn.metrics).forEach(value => entries.push({ kind: 'metric', label: '指标', value }));
  unique(turn.dimensions).forEach(value =>
    entries.push({ kind: 'dimension', label: '维度', value })
  );
  if (turn.dateInfo?.trim()) {
    entries.push({ kind: 'date', label: '时间', value: turn.dateInfo.trim() });
  }
  unique(turn.filters).forEach(value =>
    entries.push({
      kind: ORGANIZATION_PATTERN.test(value) ? 'organization' : 'filter',
      label: ORGANIZATION_PATTERN.test(value) ? '机构' : '筛选',
      value,
    })
  );
  return entries;
};

export const buildContextAdjustmentQuestion = (
  originalQuestion: string,
  action: 'remove' | 'reset' | 'reinterpret',
  entry?: ContextEntry
) => {
  if (action === 'reset') {
    return `清空上下文，重新查询：${originalQuestion}`;
  }
  if (action === 'reinterpret') {
    return `不参考之前，重新解释：${originalQuestion}`;
  }
  return `基于“${originalQuestion}”，去掉${entry?.label || '筛选'}“${
    entry?.value || ''
  }”，其余条件保持不变`;
};

export const requiresMultipleSelection = (clarification?: BankClarificationType) =>
  clarification?.type === 'METRIC' || clarification?.type === 'ORGANIZATION';

export const shouldAwaitClarification = (intent?: BankIntentResultType) =>
  Boolean(intent?.clarificationRequired && intent.clarifications?.length);

export const clarificationComplete = (
  clarifications: BankClarificationType[],
  selections: Record<string, string[]>
) =>
  clarifications.every((_, index) => {
    const values = selections[String(index)] || [];
    return values.length > 0;
  });

export const buildClarifiedQuestion = (
  originalQuestion: string,
  clarifications: BankClarificationType[],
  selections: Record<string, string[]>
) => {
  const clauses = clarifications
    .map((clarification, index) => {
      const values = selections[String(index)] || [];
      if (!values.length) {
        return '';
      }
      const question = (clarification.question || clarification.type || '补充条件').replace(
        /[？?：:]\s*$/,
        ''
      );
      return `${question}：${values.join('、')}`;
    })
    .filter(Boolean);
  return `${originalQuestion}；${clauses.join('；')}`;
};
