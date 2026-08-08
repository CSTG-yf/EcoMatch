import { ChatContextType, MsgDataType } from './common/type';

export type DashboardSemanticQuery = {
  dataSetId: number;
  dataSetName?: string;
  groups: string[];
  aggregators: Array<{ column: string; func: string }>;
  dimensionFilters: Array<{
    relation: 'FILTER';
    bizName: string;
    operator: string;
    value: unknown;
  }>;
  metricFilters: Array<{
    relation: 'FILTER';
    bizName: string;
    operator: string;
    value: unknown;
  }>;
  dateInfo?: ChatContextType['dateInfo'];
  limit: number;
  queryType: string;
};

export type DashboardQueryDraft = {
  domainId?: number;
  sourceQueryId: number;
  sourceParseId: number;
  title: string;
  question: string;
  chartType: string;
  query: DashboardSemanticQuery;
};

const normalizeAggregator = (aggType?: string, defaultAgg?: string) => {
  const requested = String(aggType || '').toUpperCase();
  if (requested === 'DISTINCT') {
    return 'COUNT_DISTINCT';
  }
  if (requested && !['NONE', 'TOPN'].includes(requested)) {
    return requested;
  }
  return String(defaultAgg || 'SUM').toUpperCase();
};

const toFilters = (filters: ChatContextType['dimensionFilters'] = []) =>
  filters
    .filter(filter => Boolean(filter?.bizName) && filter?.value !== undefined)
    .map(filter => ({
      relation: 'FILTER' as const,
      bizName: filter.bizName,
      operator: String(filter.operator || 'IN').toUpperCase(),
      value: filter.value,
    }));

export function buildDashboardQueryDraft(
  question: string,
  data: MsgDataType
): DashboardQueryDraft | undefined {
  const context = data?.chatContext;
  const dataSetId = Number(context?.dataSet?.id);
  const queryId = Number(data?.queryId || context?.queryId);
  const parseId = Number(context?.id);
  if (
    !context ||
    !Number.isFinite(dataSetId) ||
    !Number.isFinite(queryId) ||
    !Number.isFinite(parseId)
  ) {
    return undefined;
  }

  const metrics = context.metrics || [];
  const chartType = String(
    data.recommendedChart?.chartType || (metrics.length === 1 ? 'KPI_CARD' : 'TABLE')
  )
    .toUpperCase()
    .replace('METRIC_CARD', 'KPI_CARD');

  return {
    domainId: Number((context.dataSet as any)?.domainId) || undefined,
    sourceQueryId: queryId,
    sourceParseId: parseId,
    title: question.trim().slice(0, 80) || '问数结果',
    question: question.trim(),
    chartType,
    query: {
      dataSetId,
      dataSetName: context.dataSet?.name,
      groups: (context.dimensions || []).map(item => item.bizName).filter(Boolean),
      aggregators: metrics
        .filter(item => Boolean(item.bizName))
        .map(item => ({
          column: item.bizName,
          func: normalizeAggregator(context.aggType, item.defaultAgg),
        })),
      dimensionFilters: toFilters(context.dimensionFilters),
      metricFilters: [],
      dateInfo: context.dateInfo,
      limit: 1000,
      queryType: context.queryType || 'AGGREGATE',
    },
  };
}
