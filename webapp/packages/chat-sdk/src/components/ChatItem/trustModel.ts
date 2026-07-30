import { ChatContextType, SchemaElementMatchType, SqlInfoType } from '../../common/type';

export type SqlCorrectionRecord = {
  key: string;
  label: string;
  status: 'completed' | 'changed' | 'failed' | 'pending';
  detail: string;
  sql?: string;
};

const normalizedSql = (sql?: string) => (sql || '').replace(/\s+/g, ' ').trim().toLowerCase();

export const mappingConfidence = (matches?: SchemaElementMatchType[]) => {
  const similarities = (matches || [])
    .map(match => match.similarity)
    .filter(
      (similarity): similarity is number =>
        typeof similarity === 'number' &&
        Number.isFinite(similarity) &&
        similarity >= 0 &&
        similarity <= 1
    );
  if (!similarities.length) {
    return undefined;
  }
  return similarities.reduce((sum, value) => sum + value, 0) / similarities.length;
};

export const confidenceLevel = (confidence?: number) => {
  if (confidence === undefined) {
    return { label: '未提供', color: 'default' };
  }
  if (confidence >= 0.9) {
    return { label: '高', color: 'success' };
  }
  if (confidence >= 0.75) {
    return { label: '中', color: 'warning' };
  }
  return { label: '低', color: 'error' };
};

export const sqlValidationLabel = (parseInfo?: ChatContextType) => {
  const validation = parseInfo?.sqlEvaluation;
  if (validation?.isValidated === true) {
    return { label: '校验通过', status: 'success' as const };
  }
  if (validation?.isValidated === false) {
    return { label: '校验未通过', status: 'error' as const };
  }
  if (parseInfo?.sqlInfo?.querySQL) {
    return { label: '已生成执行 SQL', status: 'processing' as const };
  }
  return { label: '等待 SQL 生成', status: 'default' as const };
};

export const correctionRecords = (
  sqlInfo?: SqlInfoType,
  validated?: boolean
): SqlCorrectionRecord[] => {
  if (!sqlInfo) {
    return [];
  }
  const parsed = sqlInfo.parsedS2SQL;
  const corrected = sqlInfo.correctedS2SQL;
  const query = sqlInfo.querySQL;
  const optimized = sqlInfo.correctedQuerySQL;
  const semanticChanged =
    Boolean(parsed && corrected) && normalizedSql(parsed) !== normalizedSql(corrected);
  const physicalChanged =
    Boolean(query && optimized) && normalizedSql(query) !== normalizedSql(optimized);
  return [
    {
      key: 'generated',
      label: '语义 SQL 生成',
      status: parsed ? 'completed' : 'pending',
      detail: parsed ? '已生成 S2SQL' : '尚未生成 S2SQL',
      sql: parsed,
    },
    {
      key: 'semantic-correction',
      label: '语义 SQL 修正',
      status: corrected ? (semanticChanged ? 'changed' : 'completed') : 'pending',
      detail: corrected
        ? semanticChanged
          ? '校正器修改了语义 SQL'
          : '校正器检查完成，SQL 无需改写'
        : '尚无修正结果',
      sql: corrected,
    },
    {
      key: 'validation',
      label: '复杂 SQL 校验',
      status: validated === false ? 'failed' : validated === true ? 'completed' : 'pending',
      detail:
        validated === false ? 'SQL 校验未通过' : validated === true ? 'SQL 校验通过' : '无校验结果',
    },
    {
      key: 'physical',
      label: '物理 SQL 优化',
      status: optimized
        ? physicalChanged
          ? 'changed'
          : 'completed'
        : query
        ? 'completed'
        : 'pending',
      detail: optimized
        ? physicalChanged
          ? '执行前完成物理 SQL 优化'
          : '物理 SQL 检查完成，无需改写'
        : query
        ? '已生成最终执行 SQL'
        : '尚未生成执行 SQL',
      sql: optimized || query,
    },
  ];
};

export const semanticNames = (
  items?: Array<{ name?: string; bizName?: string; description?: string }>
) =>
  (items || [])
    .map(item => ({
      name: item.name || item.bizName || '-',
      description: item.description,
    }))
    .filter(item => item.name !== '-');

export const filterSummaries = (parseInfo?: ChatContextType) => {
  const values = (parseInfo?.dimensionFilters || []).map(filter => {
    const value = Array.isArray(filter.value) ? filter.value.join('、') : `${filter.value ?? ''}`;
    return `${filter.name || filter.bizName || '筛选条件'} ${
      filter.operator || '='
    } ${value}`.trim();
  });
  if (parseInfo?.dateInfo?.text) {
    values.unshift(parseInfo.dateInfo.text);
  }
  return values;
};
