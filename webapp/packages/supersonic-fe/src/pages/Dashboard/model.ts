import type { DashboardQueryDraft } from 'supersonic-chat-sdk';
import type {
  DashboardChartType,
  DashboardComponent,
  DashboardConfig,
  DashboardGlobalFilter,
} from './types';

export const DASHBOARD_DRAFT_STORAGE_KEY = 'BANK_DASHBOARD_QUERY_DRAFT';

const CHART_TYPES = new Set<DashboardChartType>([
  'KPI_CARD',
  'TABLE',
  'LINE',
  'BAR',
  'PIE',
  'COMBO',
]);

export const emptyDashboardConfig = (): DashboardConfig => ({
  schemaVersion: 1,
  refreshInterval: 0,
  globalFilters: [],
  components: [],
});

export function normalizeChartType(value?: string): DashboardChartType {
  const normalized = String(value || '')
    .toUpperCase()
    .replace('METRIC_CARD', 'KPI_CARD');
  return CHART_TYPES.has(normalized as DashboardChartType)
    ? (normalized as DashboardChartType)
    : 'TABLE';
}

export function parseDashboardConfig(raw?: string): DashboardConfig {
  if (!raw) {
    return emptyDashboardConfig();
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || !Array.isArray(parsed.components)) {
      return emptyDashboardConfig();
    }
    const components = parsed.components
      .filter((item: any) => item?.id && item?.query?.dataSetId)
      .map((item: any, index: number) => ({
        ...item,
        chartType: normalizeChartType(item.chartType),
        layout: {
          order: Number.isFinite(item.layout?.order) ? item.layout.order : index,
          span: item.layout?.span === 2 ? 2 : 1,
        },
      }))
      .sort(
        (left: DashboardComponent, right: DashboardComponent) =>
          left.layout.order - right.layout.order,
      );
    return {
      schemaVersion: 1,
      refreshInterval: [0, 30, 60, 300].includes(parsed.refreshInterval)
        ? parsed.refreshInterval
        : 0,
      globalFilters: Array.isArray(parsed.globalFilters) ? parsed.globalFilters : [],
      components,
    };
  } catch {
    return emptyDashboardConfig();
  }
}

export function createComponentFromDraft(
  draft: DashboardQueryDraft,
  id = `query-${draft.sourceQueryId}-${Date.now()}`,
): DashboardComponent {
  return {
    id,
    title: draft.title,
    question: draft.question,
    sourceQueryId: draft.sourceQueryId,
    sourceParseId: draft.sourceParseId,
    chartType: normalizeChartType(draft.chartType),
    query: draft.query,
    layout: { order: 0, span: 2 },
  };
}

export function reorderComponents(
  components: DashboardComponent[],
  sourceId: string,
  targetId: string,
): DashboardComponent[] {
  if (sourceId === targetId) {
    return components;
  }
  const sourceIndex = components.findIndex((item) => item.id === sourceId);
  const targetIndex = components.findIndex((item) => item.id === targetId);
  if (sourceIndex < 0 || targetIndex < 0) {
    return components;
  }
  const next = [...components];
  const [source] = next.splice(sourceIndex, 1);
  next.splice(targetIndex, 0, source);
  return next.map((item, index) => ({ ...item, layout: { ...item.layout, order: index } }));
}

export function applyGlobalFilters(
  component: DashboardComponent,
  filters: DashboardGlobalFilter[],
) {
  const active = filters.filter((filter) => filter.bizName && filter.value.length > 0);
  const names = new Set(active.map((filter) => filter.bizName));
  const retained = (component.query.dimensionFilters || []).filter(
    (filter) => !names.has(filter.bizName),
  );
  return {
    ...component.query,
    dimensionFilters: [
      ...retained,
      ...active.map((filter) => ({
        relation: 'FILTER' as const,
        bizName: filter.bizName,
        operator: filter.operator || 'IN',
        value: filter.value,
      })),
    ],
  };
}
