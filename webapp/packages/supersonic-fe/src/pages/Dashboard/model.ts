import {
  DashboardComponent,
  DashboardComponentLayout,
  DashboardConfig,
  DashboardErrorKind,
  DashboardGlobalFilter,
  DashboardQuerySource,
  DashboardStatus,
} from './types';

const MAX_COMPONENTS = 100;
const DEFAULT_COLUMNS = 12;
const DEFAULT_ROW_HEIGHT = 72;
const FORBIDDEN_CONFIG_KEYS = new Set([
  'password',
  'token',
  'secret',
  'apikey',
  'accesstoken',
  'refreshtoken',
  'sql',
  'rawsql',
  'credential',
]);

const safeJsonValue = (value: any, depth = 0): any => {
  if (depth > 8) {
    return undefined;
  }
  if (value == null || ['string', 'number', 'boolean'].includes(typeof value)) {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => safeJsonValue(item, depth + 1));
  }
  if (typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !FORBIDDEN_CONFIG_KEYS.has(key.replace(/[^a-z0-9]/gi, '').toLowerCase()))
        .map(([key, item]) => [key, safeJsonValue(item, depth + 1)]),
    );
  }
  return String(value);
};

const clampInteger = (value: unknown, minimum: number, maximum: number) => {
  const numericValue = Number(value);
  if (!Number.isFinite(numericValue)) {
    return minimum;
  }
  return Math.min(maximum, Math.max(minimum, Math.round(numericValue)));
};

const semanticField = (field: any) => ({
  bizName: field?.bizName,
  itemId: field?.itemId,
  id: field?.id,
  name: field?.name,
  status: field?.status,
  model: field?.model,
  type: field?.type,
  value: field?.value,
  description: field?.description,
  defaultAgg: field?.defaultAgg,
});

const semanticFilter = (filter: any) => ({
  elementID: filter?.elementID,
  name: filter?.name,
  bizName: filter?.bizName,
  operator: filter?.operator,
  type: filter?.type,
  value: safeJsonValue(filter?.value),
  entityName: filter?.entityName,
});

const semanticDate = (dateInfo: any) => {
  if (!dateInfo || typeof dateInfo !== 'object') {
    return undefined;
  }
  return {
    dateList: Array.isArray(dateInfo.dateList) ? safeJsonValue(dateInfo.dateList) : [],
    dateMode: dateInfo.dateMode,
    period: dateInfo.period,
    startDate: dateInfo.startDate,
    endDate: dateInfo.endDate,
    text: dateInfo.text,
    unit: dateInfo.unit,
  };
};

const sanitizeSemanticQuery = (query: any = {}) => ({
  queryId: query.queryId,
  parseId: query.parseId,
  modelId: query.modelId,
  dimensions: Array.isArray(query.dimensions) ? query.dimensions.map(semanticField) : [],
  metrics: Array.isArray(query.metrics) ? query.metrics.map(semanticField) : [],
  dateInfo: semanticDate(query.dateInfo),
  dimensionFilters: Array.isArray(query.dimensionFilters)
    ? query.dimensionFilters.map(semanticFilter)
    : [],
});

const normalizeDashboardFilterOperator = (operator: unknown) => {
  const normalized = String(operator || '=')
    .trim()
    .toUpperCase();
  return ['=', 'EQ', 'EQUALS'].includes(normalized) ? '=' : String(operator || '=');
};

const sanitizeGlobalFilter = (filter: any): DashboardGlobalFilter => ({
  field: String(filter?.field || ''),
  operator: normalizeDashboardFilterOperator(filter?.operator),
  value: safeJsonValue(filter?.value),
});

const sanitizeLayout = (layout: Partial<DashboardComponentLayout> = {}) => {
  const width = clampInteger(layout.w, 2, DEFAULT_COLUMNS);
  const height = clampInteger(layout.h, 2, 20);
  return {
    x: clampInteger(layout.x, 0, DEFAULT_COLUMNS - width),
    y: clampInteger(layout.y, 0, 10000),
    w: width,
    h: height,
  };
};

const sanitizeComponent = (component: any): DashboardComponent => ({
  id: String(component?.id || `component-${Date.now()}-${Math.random().toString(16).slice(2)}`),
  type: ['chart', 'table', 'number'].includes(component?.type) ? component.type : 'chart',
  title: String(component?.title || '未命名组件').slice(0, 120),
  layout: sanitizeLayout(component?.layout),
  visualization: {
    chartType: String(component?.visualization?.chartType || 'table'),
  },
  query: sanitizeSemanticQuery(component?.query),
  masked: Boolean(component?.masked),
});

export const createEmptyDashboardConfig = (): DashboardConfig => ({
  schemaVersion: '1.0',
  layout: {
    columns: DEFAULT_COLUMNS,
    rowHeight: DEFAULT_ROW_HEIGHT,
  },
  globalFilters: [],
  refreshIntervalSeconds: 0,
  components: [],
});

export const buildDashboardConfig = (config: Partial<DashboardConfig>): DashboardConfig => ({
  schemaVersion: '1.0',
  layout: {
    columns: DEFAULT_COLUMNS,
    rowHeight: clampInteger(config.layout?.rowHeight, 48, 160),
  },
  globalFilters: Array.isArray(config.globalFilters)
    ? config.globalFilters.map(sanitizeGlobalFilter)
    : [],
  refreshIntervalSeconds: clampInteger(config.refreshIntervalSeconds, 0, 86400),
  components: Array.isArray(config.components)
    ? config.components.slice(0, MAX_COMPONENTS).map(sanitizeComponent)
    : [],
});

export const parseDashboardConfig = (value: unknown): DashboardConfig => {
  if (!value) {
    return createEmptyDashboardConfig();
  }
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return createEmptyDashboardConfig();
    }
    return buildDashboardConfig(parsed as DashboardConfig);
  } catch {
    return createEmptyDashboardConfig();
  }
};

export const serializeDashboardConfig = (config: DashboardConfig): DashboardConfig =>
  buildDashboardConfig(config);

export const serializeDashboardWriteConfig = (config: DashboardConfig): string =>
  JSON.stringify(serializeDashboardConfig(config));

export const requireDashboardSourceDomain = (source: DashboardQuerySource): number => {
  const domainId = Number(source.domainId);
  if (!Number.isInteger(domainId) || domainId <= 0) {
    throw new Error('无法确认问数结果所属主题域');
  }
  return domainId;
};

export const normalizeDashboardPage = (response: any) => {
  const page = response?.data?.list ? response.data : response || {};
  return {
    data: Array.isArray(page.list) ? page.list : [],
    total: Number(page.total || 0),
    success: true,
  };
};

export const dashboardColumnKey = (column: { bizName?: string; name?: string }) =>
  column.bizName || column.name || '';

export const createDashboardComponent = (source: DashboardQuerySource): DashboardComponent =>
  sanitizeComponent({
    id: `component-${source.queryId || source.parseId || Date.now()}`,
    type: source.chartType === 'table' ? 'table' : 'chart',
    title: source.question || '问数结果',
    layout: { x: 0, y: 0, w: 6, h: 4 },
    visualization: { chartType: source.chartType || 'table' },
    query: source.semanticQuery,
    masked: source.masked,
  });

export const addDashboardQueryComponent = (
  config: DashboardConfig,
  source: DashboardQuerySource,
  title: string,
  refreshIntervalSeconds: number,
): DashboardConfig => {
  if (config.components.length >= MAX_COMPONENTS) {
    throw new Error('看板组件已达到 100 个上限');
  }
  return buildDashboardConfig({
    ...config,
    refreshIntervalSeconds,
    components: [
      ...config.components,
      {
        ...createDashboardComponent(source),
        title: title.trim().slice(0, 120) || source.question,
      },
    ],
  });
};

export const moveComponent = (
  component: DashboardComponent,
  layout: Partial<DashboardComponentLayout>,
): DashboardComponent => ({
  ...component,
  layout: sanitizeLayout({ ...component.layout, ...layout }),
});

export const classifyDashboardError = (error: any): DashboardErrorKind => {
  const code = Number(error?.code ?? error?.status ?? error?.response?.status);
  const detail = String(error?.msg || error?.message || error?.data?.msg || '').toLowerCase();
  if (code === 401 || code === 403) {
    return 'FORBIDDEN';
  }
  if (code === 409 || code === 412 || detail.includes('dashboard version conflict')) {
    return 'CONFLICT';
  }
  return 'FAILED';
};

export const canEditDashboard = (status: DashboardStatus) => status !== 'DISABLED';

export const canManageDashboard = (
  owner: string | undefined,
  currentUser: { name?: string; staffName?: string; superAdmin?: boolean } | undefined,
  domainHasEditPermission: boolean,
) => {
  const currentUserName = currentUser?.name || currentUser?.staffName;
  return Boolean(
    currentUser?.superAdmin ||
      domainHasEditPermission ||
      (owner && currentUserName && owner === currentUserName),
  );
};

export const applyDashboardGlobalFilters = (
  query: Record<string, any>,
  filters: DashboardGlobalFilter[],
) => ({
  ...query,
  dimensionFilters: [
    ...(Array.isArray(query.dimensionFilters) ? query.dimensionFilters : []),
    ...filters
      .filter((filter) => filter.field.trim())
      .map((filter) => ({
        name: filter.field,
        bizName: filter.field,
        operator: normalizeDashboardFilterOperator(filter.operator),
        value: filter.value,
      })),
  ],
});
