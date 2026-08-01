import type { Dashboard, DashboardQuerySource } from '../Dashboard/types';
import { EXPORT_LIMITS, validateExportRequest } from './model';
import { ExportCreateReq, ExportFormat, QueryStructReq } from './types';

const FORBIDDEN_KEYS = new Set([
  'password',
  'token',
  'secret',
  'apikey',
  'accesstoken',
  'refreshtoken',
  'sql',
  'rawsql',
  'credential',
  'queryresults',
  'queryresult',
  'resultlist',
  'result',
  'data',
  'rows',
  'snapshot',
]);

const safeValue = (value: unknown, depth = 0): unknown => {
  if (depth > 8) return undefined;
  if (value == null || ['string', 'number', 'boolean'].includes(typeof value)) return value;
  if (Array.isArray(value)) return value.map((item) => safeValue(item, depth + 1));
  if (typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .filter(([key]) => !FORBIDDEN_KEYS.has(key.replace(/[^a-z0-9]/gi, '').toLowerCase()))
        .map(([key, item]) => [key, safeValue(item, depth + 1)]),
    );
  }
  return String(value);
};

const positiveInteger = (value: unknown) => {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : undefined;
};

const fieldIdentifier = (field: any) =>
  String(field?.bizName || field?.name || '').trim() || undefined;

const AGGREGATORS = new Set([
  'MAX',
  'MIN',
  'AVG',
  'SUM',
  'COUNT',
  'COUNT_DISTINCT',
  'DISTINCT',
  'TOPN',
  'PERCENTILE',
  'RATIO_ROLL',
  'RATIO_OVER',
]);

const metricAggregator = (value: unknown) => {
  const normalized = String(value || 'SUM').toUpperCase();
  return AGGREGATORS.has(normalized) ? normalized : 'SUM';
};

const copyDateInfo = (dateInfo: any) => {
  if (!dateInfo || typeof dateInfo !== 'object') return undefined;
  return {
    dateList: Array.isArray(dateInfo.dateList) ? safeValue(dateInfo.dateList) : [],
    dateMode: dateInfo.dateMode,
    period: dateInfo.period,
    startDate: dateInfo.startDate,
    endDate: dateInfo.endDate,
    text: dateInfo.text,
    unit: dateInfo.unit,
  };
};

const copyDimensionFilter = (filter: any) => ({
  elementID: filter?.elementID,
  name: filter?.name,
  bizName: filter?.bizName,
  operator: filter?.operator,
  type: filter?.type,
  value: safeValue(filter?.value),
  entityName: filter?.entityName,
});

export const dashboardSourceToQueryStruct = (
  source: DashboardQuerySource,
  additionalFilters: Record<string, unknown>[] = [],
): QueryStructReq => {
  const semantic = source?.semanticQuery || {};
  const dataSetId = positiveInteger(source?.dataSetId ?? semantic.dataSetId);
  const modelId = positiveInteger(source?.modelId ?? semantic.modelId);
  const dimensions = Array.isArray(semantic.dimensions) ? semantic.dimensions : [];
  const metrics = Array.isArray(semantic.metrics) ? semantic.metrics : [];
  const groups = dimensions.map(fieldIdentifier).filter((field): field is string => Boolean(field));
  const aggregators = metrics
    .map((metric: any) => {
      const column = fieldIdentifier(metric);
      return column
        ? {
            column,
            func: metricAggregator(metric.defaultAgg),
          }
        : undefined;
    })
    .filter((aggregator): aggregator is NonNullable<typeof aggregator> => Boolean(aggregator));

  if (!dataSetId) throw new Error('导出来源缺少 dataSetId，无法安全重放');
  if (!modelId) throw new Error('导出来源缺少模型标识，无法安全重放');
  if (!groups.length && !aggregators.length) {
    throw new Error('导出来源缺少指标或维度，无法安全重放');
  }

  const requestedLimit = Number((semantic as Record<string, any>).limit);
  const limit = Number.isFinite(requestedLimit)
    ? Math.min(Math.max(1, Math.floor(requestedLimit)), EXPORT_LIMITS.maxRows)
    : EXPORT_LIMITS.maxRows;
  const sourceFilters = Array.isArray(semantic.dimensionFilters)
    ? semantic.dimensionFilters.map(copyDimensionFilter)
    : [];

  return {
    dataSetId,
    modelIds: [modelId],
    groups,
    aggregators,
    dimensionFilters: [...sourceFilters, ...additionalFilters.map(copyDimensionFilter)],
    dateInfo: copyDateInfo(semantic.dateInfo),
    limit,
  };
};

export const buildQueryExportRequest = (
  source: DashboardQuerySource,
  format: ExportFormat,
): ExportCreateReq =>
  validateExportRequest({
    resourceType: 'QUERY',
    format,
    title: source.question,
    queries: [dashboardSourceToQueryStruct(source)],
    charts: [],
  });

const parseDashboardConfig = (dashboard: Dashboard) => {
  try {
    const config =
      typeof dashboard.config === 'string' ? JSON.parse(dashboard.config) : dashboard.config;
    if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error();
    return config as Record<string, any>;
  } catch {
    throw new Error('看板配置无效，无法创建导出');
  }
};

export const buildDashboardExportRequest = (
  dashboard: Dashboard,
  format: ExportFormat,
): ExportCreateReq => {
  const dashboardId = positiveInteger(dashboard?.id);
  if (!dashboardId) throw new Error('看板缺少有效 ID，无法创建导出');
  const config = parseDashboardConfig(dashboard);
  const components = Array.isArray(config.components) ? config.components : [];
  if (!components.length) throw new Error('看板没有可导出的语义查询组件');
  const globalFilters = Array.isArray(config.globalFilters)
    ? config.globalFilters
        .filter((filter: any) => String(filter?.field || '').trim())
        .map((filter: any) => ({
          name: String(filter.field),
          bizName: String(filter.field),
          operator: filter.operator,
          value: safeValue(filter.value),
        }))
    : [];
  const queries = components.map((component: any) =>
    dashboardSourceToQueryStruct(
      {
        question: component.title || dashboard.name,
        dataSetId: component.query?.dataSetId,
        modelId: component.query?.modelId,
        semanticQuery: component.query || {},
      },
      globalFilters,
    ),
  );
  const charts = components.flatMap((component: any, queryIndex: number) => {
    const chartType = String(component.visualization?.chartType || '').toUpperCase();
    const type =
      chartType === 'LINE' ? 'LINE' : ['BAR', 'COLUMN'].includes(chartType) ? 'BAR' : undefined;
    const categoryField = queries[queryIndex].groups?.[0];
    const valueField = (queries[queryIndex].aggregators?.[0] as any)?.column;
    return type && categoryField && valueField
      ? [{ queryIndex, type, title: component.title, categoryField, valueField }]
      : [];
  });

  return validateExportRequest({
    resourceType: 'DASHBOARD',
    dashboardId,
    format,
    title: dashboard.name,
    queries,
    charts,
  });
};
