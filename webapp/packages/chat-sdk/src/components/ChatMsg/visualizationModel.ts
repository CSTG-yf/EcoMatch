import { ChartRecommendationType, ColumnType, FilterItemType } from '../../common/type';

export type VisualizationType = 'KPI_CARD' | 'TABLE' | 'LINE' | 'BAR' | 'PIE' | 'COMBO';

const VISUALIZATION_TYPES: VisualizationType[] = [
  'KPI_CARD',
  'TABLE',
  'LINE',
  'BAR',
  'PIE',
  'COMBO',
];

const fieldMatches = (column: ColumnType, field: string) =>
  [column.bizName, column.name, column.nameEn].filter(Boolean).includes(field);

export const normalizeVisualizationType = (chartType?: string): VisualizationType | undefined => {
  const normalized = chartType?.trim().toUpperCase();
  if (normalized === 'METRIC_CARD') {
    return 'KPI_CARD';
  }
  return VISUALIZATION_TYPES.find(type => type === normalized);
};

const recommendationFieldsExist = (
  recommendation: ChartRecommendationType | undefined,
  columns: ColumnType[]
) => {
  const fields = [
    ...(recommendation?.dimensionFields || []),
    ...(recommendation?.metricFields || []),
  ];
  return fields.every(field => columns.some(column => fieldMatches(column, field)));
};

export const isVisualizationCompatible = (
  type: VisualizationType,
  columns: ColumnType[],
  rows: any[],
  recommendation?: ChartRecommendationType
) => {
  if (!recommendationFieldsExist(recommendation, columns)) {
    return false;
  }
  const metrics = columns.filter(column => column.showType === 'NUMBER');
  const dates = columns.filter(column => column.showType === 'DATE' || column.type === 'DATE');
  const categories = columns.filter(column => column.showType === 'CATEGORY');
  const authorizedMetrics = metrics.filter(column => column.authorized !== false);
  const allMetricsAuthorized = metrics.length > 0 && authorizedMetrics.length === metrics.length;
  const hasDimension = dates.length > 0 || categories.length > 0;

  switch (type) {
    case 'KPI_CARD':
      return rows.length === 1 && allMetricsAuthorized;
    case 'TABLE':
      return columns.length > 0;
    case 'LINE':
      return rows.length > 1 && dates.length > 0 && allMetricsAuthorized;
    case 'BAR':
      return rows.length > 1 && hasDimension && allMetricsAuthorized;
    case 'PIE':
      return (
        rows.length >= 2 &&
        rows.length <= 10 &&
        categories.length === 1 &&
        metrics.length === 1 &&
        allMetricsAuthorized &&
        rows.every(row => {
          const value = Number(row[authorizedMetrics[0].bizName]);
          return Number.isFinite(value) && value >= 0;
        })
      );
    case 'COMBO':
      return rows.length > 1 && hasDimension && metrics.length >= 2 && allMetricsAuthorized;
    default:
      return false;
  }
};

export const inferVisualizationType = (columns: ColumnType[], rows: any[]): VisualizationType => {
  const metrics = columns.filter(column => column.showType === 'NUMBER');
  const dates = columns.filter(column => column.showType === 'DATE' || column.type === 'DATE');
  const categories = columns.filter(column => column.showType === 'CATEGORY');
  if (isVisualizationCompatible('KPI_CARD', columns, rows)) {
    return 'KPI_CARD';
  }
  if (categories.length >= 3) {
    return 'TABLE';
  }
  if (dates.length > 0 && metrics.length > 1) {
    return 'COMBO';
  }
  if (dates.length > 0 && metrics.length > 0) {
    return 'LINE';
  }
  if (categories.length > 0 && metrics.length > 0) {
    return 'BAR';
  }
  return 'TABLE';
};

export const resolveVisualizationType = (
  recommendation: ChartRecommendationType | undefined,
  columns: ColumnType[],
  rows: any[]
) => {
  const recommended = normalizeVisualizationType(recommendation?.chartType);
  if (recommended && isVisualizationCompatible(recommended, columns, rows, recommendation)) {
    return recommended;
  }
  return inferVisualizationType(columns, rows);
};

export const visualizationOptions = (
  recommended: ChartRecommendationType | undefined,
  candidates: ChartRecommendationType[] | undefined,
  columns: ColumnType[],
  rows: any[]
) => {
  const recommendations = [recommended, ...(candidates || [])].filter(
    (item): item is ChartRecommendationType => Boolean(item)
  );
  const options = recommendations.reduce<VisualizationType[]>((result, recommendation) => {
    const type = normalizeVisualizationType(recommendation.chartType);
    if (
      type &&
      !result.includes(type) &&
      isVisualizationCompatible(type, columns, rows, recommendation)
    ) {
      result.push(type);
    }
    return result;
  }, []);
  const inferred = resolveVisualizationType(recommended, columns, rows);
  if (!options.includes(inferred)) {
    options.unshift(inferred);
  }
  if (!options.includes('TABLE') && isVisualizationCompatible('TABLE', columns, rows)) {
    options.push('TABLE');
  }
  return options;
};

export const mergeDimensionFilters = (
  baseFilters: FilterItemType[] = [],
  overlays: FilterItemType[] = []
) => {
  const overlayNames = new Set(overlays.map(filter => filter.bizName));
  return [...baseFilters.filter(filter => !overlayNames.has(filter.bizName)), ...overlays];
};
