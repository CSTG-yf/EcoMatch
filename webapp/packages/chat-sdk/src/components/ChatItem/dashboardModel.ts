import {
  ChatContextType,
  DashboardQuerySource,
  DateInfoType,
  FieldType,
  FilterItemType,
  MsgDataType,
} from '../../common/type';

type BuildDashboardQuerySourceInput = {
  question: string;
  context?: ChatContextType;
  data?: MsgDataType;
};

type SaveCapability = {
  enabled: boolean;
  reason?: string;
};

const forbiddenConfigKeys = new Set([
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
    return value.map(item => safeJsonValue(item, depth + 1));
  }
  if (typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !forbiddenConfigKeys.has(key.replace(/[^a-z0-9]/gi, '').toLowerCase()))
        .map(([key, item]) => [key, safeJsonValue(item, depth + 1)])
    );
  }
  return String(value);
};

const copyField = (field: FieldType): FieldType => ({
  bizName: field.bizName,
  itemId: field.itemId,
  id: field.id,
  name: field.name,
  status: field.status,
  model: field.model,
  type: field.type,
  value: field.value,
  description: field.description,
  defaultAgg: field.defaultAgg,
});

const copyDateInfo = (dateInfo?: DateInfoType): DateInfoType | undefined => {
  if (!dateInfo) {
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

const copyFilter = (filter: FilterItemType): FilterItemType => ({
  elementID: filter.elementID,
  name: filter.name,
  bizName: filter.bizName,
  operator: filter.operator,
  type: filter.type,
  value: safeJsonValue(filter.value),
  entityName: filter.entityName,
});

export const canSaveDashboardResult = (data?: MsgDataType): SaveCapability => {
  if (!data || data.queryState !== 'SUCCESS') {
    return { enabled: false, reason: '仅成功结果可以保存到看板' };
  }
  if (data.queryAuthorization?.authorized === false) {
    return { enabled: false, reason: '当前结果无权保存到看板' };
  }
  if (!Array.isArray(data.queryResults) || data.queryResults.length === 0) {
    return { enabled: false, reason: '空结果不能保存到看板' };
  }
  return { enabled: true };
};

export const buildDashboardQuerySource = ({
  question,
  context,
  data,
}: BuildDashboardQuerySourceInput): DashboardQuerySource => {
  const effectiveContext = data?.chatContext || context;
  const queryId = data?.queryId ?? effectiveContext?.queryId;
  const parseId = effectiveContext?.id;
  // 后端 chat 响应顶层没有 modelId；DATASET 根元素的 dataSet.model 未 set（恒 null）。
  // modelId 真实落点是每个 metric/dimension 元素的 model 字段（SchemaElement.model），
  // 看板只需任意一个 modelId 用于反查 domainId，故从元素 model 兜底取数。
  const firstElementModel = [
    ...(effectiveContext?.metrics || []),
    ...(effectiveContext?.dimensions || []),
  ]
    .map((field: FieldType) => Number(field?.model))
    .find(value => Number.isInteger(value) && value > 0);
  const modelId =
    effectiveContext?.modelId ??
    (effectiveContext?.dataSet?.model != null && Number(effectiveContext.dataSet.model) > 0
      ? Number(effectiveContext.dataSet.model)
      : undefined) ??
    firstElementModel;
  const dimensions = (effectiveContext?.dimensions || []).map(copyField);
  const metrics = (effectiveContext?.metrics || []).map(copyField);
  const dimensionFilters = (effectiveContext?.dimensionFilters || []).map(copyFilter);
  const metricFilters = (effectiveContext?.metricFilters || []).map(copyFilter);

  return {
    queryId,
    parseId,
    question,
    modelId,
    dataSetId: effectiveContext?.dataSet?.id,
    modelName: effectiveContext?.modelName,
    dataSetName: effectiveContext?.dataSet?.bizName || effectiveContext?.dataSet?.name,
    masked: Boolean(data?.dataMasked),
    chartType: data?.recommendedChart?.chartType,
    semanticQuery: {
      queryId,
      parseId,
      dataSetId: effectiveContext?.dataSet?.id,
      modelId,
      dimensions,
      metrics,
      dateInfo: copyDateInfo(effectiveContext?.dateInfo),
      dimensionFilters,
      metricFilters,
    },
  };
};
