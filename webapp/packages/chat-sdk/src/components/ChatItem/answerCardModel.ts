import { format } from 'sql-formatter';
import { ChatContextType, MsgDataType, SchemaElementMatchType } from '../../common/type';
import { QueryWorkflowStage } from './workflow';
import { mappingConfidence } from './trustModel';

export type ModelRequirements = {
  action?: string;
  intent?: string;
  metricCodes?: string[];
  organizationCodes?: string[];
  time?: {
    startDate?: string | string[];
    endDate?: string | string[];
    comparison?: string;
  };
  answerFactTypes?: string[];
};

export const INTENT_LABELS: Record<string, string> = {
  POINT_QUERY: '指标查询',
  COMPARISON: '对比分析',
  RANKING: '排名分析',
  TREND: '趋势分析',
  CHANGE: '变化分析',
  RATIO: '占比分析',
  THRESHOLD: '阈值判断',
  AGGREGATION: '汇总分析',
};

export const COMPARISON_LABELS: Record<string, string> = {
  NONE: '无基期对比',
  PERIOD_OVER_PERIOD: '环比',
  YEAR_OVER_YEAR: '同比',
  START_OF_YEAR: '较年初',
  MOM_AND_YOY: '环比和同比',
};

export const CHART_TYPE_LABELS: Record<string, string> = {
  TABLE: '表格',
  METRIC_CARD: '指标卡',
  KPI_CARD: '指标卡',
  LINE: '折线图',
  BAR: '柱状图',
  PIE: '饼图',
  COMBO: '组合图',
};

export const modelRequirements = (parseInfo?: ChatContextType): ModelRequirements | undefined => {
  const value = parseInfo?.properties?.['bank.nl2sql.requirements'];
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as ModelRequirements;
    } catch {
      return undefined;
    }
  }
  return value && typeof value === 'object' ? (value as ModelRequirements) : undefined;
};

/** 后端 requirements 里日期可能是数组（['2025','12','31']）或非标准分隔符，统一规范化。 */
const normalizeDateText = (value?: any) => {
  if (Array.isArray(value)) {
    return value.join('-');
  }
  return String(value || '').replace(/[./]/g, '-');
};

export const requirementRangeText = (time?: ModelRequirements['time']) => {
  const startDate = normalizeDateText(time?.startDate);
  const endDate = normalizeDateText(time?.endDate);
  if (!startDate && !endDate) {
    return '';
  }
  if (!startDate || startDate === endDate) {
    return endDate || startDate;
  }
  return `${startDate} 至 ${endDate}`;
};

export type SqlVersionItem = {
  key: string;
  label: string;
  sql: string;
};

/** 仅返回真实存在的 SQL 版本，Tab 顺序与原型一致。 */
export const sqlVersions = (parseInfo?: ChatContextType): SqlVersionItem[] => {
  const sqlInfo = parseInfo?.sqlInfo;
  if (!sqlInfo) {
    return [];
  }
  const parsePrefix = parseInfo?.queryMode === 'LLM_S2SQL' ? 'LLM 解析' : 'Rule 解析';
  return [
    { key: 'parsedS2SQL', label: `${parsePrefix} S2SQL`, sql: sqlInfo.parsedS2SQL },
    { key: 'correctedS2SQL', label: '修正 S2SQL', sql: sqlInfo.correctedS2SQL },
    { key: 'querySQL', label: '最终执行 SQL', sql: sqlInfo.querySQL },
    { key: 'correctedQuerySQL', label: '优化后 SQL', sql: sqlInfo.correctedQuerySQL || '' },
  ].filter(item => !!item.sql);
};

/** Schema 映射文本（原型中的 Schema 映射 Tab），来自真实的 elementMatches。 */
export const schemaMappingText = (matches?: SchemaElementMatchType[]) => {
  const lines = (matches || [])
    .map(match => {
      const target = match.element?.name || match.element?.bizName;
      const source = match.detectWord || match.word;
      if (!target || !source) {
        return '';
      }
      return `${target}  <-  ${source}`;
    })
    .filter(Boolean);
  return lines.length ? lines.join('\n') : '';
};

export type AnswerCardStageKey = 'understand' | 'caliber' | 'sql' | 'query';

export type StageStatus = {
  label: string;
  state: 'done' | 'active' | 'pending' | 'failed';
};

const DONE_LABELS: Record<AnswerCardStageKey, string> = {
  understand: '已完成',
  caliber: '已确认',
  sql: '已通过',
  query: '已完成',
};

/**
 * 把整体 workflowStage 映射到四阶段状态。
 * 解析阶段（parsing）覆盖需求理解/口径/SQL 生成；执行与解释覆盖数据查询阶段。
 */
export const stageStatuses = (
  stage: QueryWorkflowStage,
  parseFailed: boolean
): Record<AnswerCardStageKey, StageStatus> => {
  const done = (key: AnswerCardStageKey): StageStatus => ({ label: DONE_LABELS[key], state: 'done' });
  const pending: StageStatus = { label: '等待', state: 'pending' };
  const active = (label: string): StageStatus => ({ label, state: 'active' });
  const failed: StageStatus = { label: '未完成', state: 'failed' };

  switch (stage) {
    case 'parsing':
      return {
        understand: active('理解中'),
        caliber: pending,
        sql: pending,
        query: pending,
      };
    case 'clarifying':
      return {
        understand: { label: '待澄清', state: 'active' },
        caliber: pending,
        sql: pending,
        query: pending,
      };
    case 'executing':
      return {
        understand: done('understand'),
        caliber: done('caliber'),
        sql: done('sql'),
        query: active('查询中'),
      };
    case 'explaining':
      return {
        understand: done('understand'),
        caliber: done('caliber'),
        sql: done('sql'),
        query: active('生成解释'),
      };
    case 'completed':
      return {
        understand: done('understand'),
        caliber: done('caliber'),
        sql: done('sql'),
        query: done('query'),
      };
    case 'failed':
    case 'forbidden':
    case 'timeout':
      if (parseFailed) {
        return { understand: failed, caliber: pending, sql: pending, query: pending };
      }
      return {
        understand: done('understand'),
        caliber: done('caliber'),
        sql: done('sql'),
        query: failed,
      };
    default:
      return { understand: pending, caliber: pending, sql: pending, query: pending };
  }
};

/**
 * 阶段耗时：仅使用后端真实字段，没有则不显示。
 * parseTime 是解析全流程（理解+口径+SQL 生成）总耗时，挂在流程摘要行，不归到单个阶段。
 */
export const stageTimeCost = (
  key: AnswerCardStageKey,
  parseTimeCost?: { parseTime?: number; sqlTime?: number },
  queryTimeCost?: number
): number | undefined => {
  if (key === 'sql') {
    return parseTimeCost?.sqlTime || undefined;
  }
  if (key === 'query') {
    return queryTimeCost || undefined;
  }
  return undefined;
};

/** 结果呈现方式：优先图表推荐，默认表格。 */
export const resultPresentation = (data?: MsgDataType) => {
  const chartType = data?.recommendedChart?.chartType;
  if (chartType) {
    return CHART_TYPE_LABELS[chartType] || chartType;
  }
  return '表格';
};

export const mappingConfidenceText = (parseInfo?: ChatContextType) => {
  const confidence = mappingConfidence(parseInfo?.elementMatches);
  return confidence === undefined ? undefined : confidence;
};

type ExportLogParams = {
  question?: string;
  queryMode?: string;
  llmReq?: any;
  llmResp?: any;
  sqlInfo?: ChatContextType['sqlInfo'];
  executeErrorMsg?: string;
};

/** 技术日志文本，与 SqlItem 导出内容保持一致的真实数据来源。 */
export const buildExportLogText = ({
  question,
  queryMode,
  llmReq,
  llmResp,
  sqlInfo,
  executeErrorMsg,
}: ExportLogParams) => {
  const formatSql = (sql?: string) => {
    if (!sql) {
      return '';
    }
    try {
      return format(sql);
    } catch {
      return sql;
    }
  };
  let text = '';
  if (question) {
    text += `\n问题：${question}\n`;
  }
  const { schema, terms, priorExts } = llmReq || {};
  if (llmReq) {
    text += `\nSchema映射\n${
      schema?.fieldNameList?.length > 0 ? `名称：${schema.fieldNameList.join('、')}` : ''
    }${
      schema?.values?.length > 0
        ? `\n取值：${schema.values
            .map((item: any) => `${item.fieldName}: ${item.fieldValue}`)
            .join('、')}`
        : ''
    }${priorExts ? `\n附加：${priorExts}` : ''}${
      terms?.length > 0
        ? `\n术语：${terms
            .map(
              (item: any) =>
                `${item.name}${item.alias?.length > 0 ? `(${item.alias.join(',')})` : ''}: ${
                  item.description
                }`
            )
            .join('、')}`
        : ''
    }\n`;
  }
  const fewShots = (Object.values(llmResp?.sqlRespMap || {})[0] as any)?.fewShots || [];
  if (fewShots.length > 0) {
    text += `\nFew-shot示例${fewShots
      .map(
        (item: any, index: number) =>
          `\n\n示例${index + 1}：\n问题：${item.question}\nSQL：\n${formatSql(item.sql)}`
      )
      .join('')}\n`;
  }
  if (sqlInfo?.parsedS2SQL) {
    text += `\n${queryMode === 'LLM_S2SQL' || queryMode === 'PLAIN_TEXT' ? 'LLM' : 'Rule'}解析S2SQL\n\n${formatSql(
      sqlInfo.parsedS2SQL
    )}\n`;
  }
  if (sqlInfo?.correctedS2SQL) {
    text += `\n修正S2SQL\n\n${formatSql(sqlInfo.correctedS2SQL)}\n`;
  }
  if (sqlInfo?.correctedQuerySQL) {
    text += `\n物理SQL修正\n\n${formatSql(sqlInfo.correctedQuerySQL)}\n`;
  }
  if (sqlInfo?.querySQL) {
    text += `\n最终执行SQL\n\n${formatSql(sqlInfo.querySQL)}\n`;
  }
  if (executeErrorMsg) {
    text += `\n异常日志\n\n${executeErrorMsg}\n`;
  }
  return text;
};
