import { Dayjs } from 'dayjs';
import { ChatContextTypeQueryTypeEnum } from './constants';

export type SearchRecommendItem = {
  complete: boolean;
  modelId: number;
  modelName: string;
  recommend: string;
  subRecommend: string;
  schemaElementType: string;
};

export type FieldType = {
  bizName: string;
  itemId: number;
  id: number;
  name: string;
  status: number;
  model: number;
  type: string;
  value: string;
};

export type ModelInfoType = {
  bizName: string;
  itemId: number;
  name: string;
  primaryEntityBizName: string;
  value: string;
  words: string[];
};

export type EntityInfoType = {
  dataSetInfo: ModelInfoType;
  dimensions: FieldType[];
  metrics: FieldType[];
  entityId: number;
};

export type DateInfoType = {
  dateList: any[];
  dateMode: string;
  period: string;
  startDate: string;
  endDate: string;
  text: string;
  unit: number;
};

export type FilterItemType = {
  elementID: number;
  name: string;
  bizName: string;
  operator?: string;
  type?: string;
  value: any;
  entityName?: string;
};

export type ModelType = {
  alias: string;
  bizName: string;
  id: number;
  model: number;
  name: string;
  modelNames: string[];
  type: string;
  useCnt: number;
};

export type EntityDimensionType = {
  bizName: string;
  itemId: number;
  name: string;
  value: string;
};

export type SqlInfoType = {
  parsedS2SQL: string;
  correctedS2SQL: string;
  querySQL: string;
  correctedQuerySQL?: string;
};

export type ChatContextType = {
  id: number;
  queryId?: number;
  aggType: string;
  modelId: number;
  modelName: string;
  dataSet: ModelType;
  dateInfo: DateInfoType;
  dimensions: FieldType[];
  metrics: FieldType[];
  entity: { alias: string[]; id: number };
  entityInfo: EntityInfoType;
  elementMatches: any[];
  nativeQuery: boolean;
  queryMode: string;
  queryType: ChatContextTypeQueryTypeEnum;
  dimensionFilters: FilterItemType[];
  properties: any;
  sqlInfo: SqlInfoType;
  textInfo: string;
};

export enum MsgValidTypeEnum {
  NORMAL = 0,
  SEARCH_EXCEPTION = 1,
  EMPTY = 2,
  INVALID = 3,
}

export type PluginResonseType = {
  description: string;
  webPage: { url: string; paramOptions: any; params: any; valueParams: any };
  pluginId: number;
  pluginType: string;
  name: string;
};

export type MetricInfoType = {
  date: string;
  name: string;
  statistics: any;
  value: string;
};

export type AggregateInfoType = {
  metricInfos: MetricInfoType[];
};

export type MsgDataType = {
  id: number;
  question: string;
  aggregateInfo: AggregateInfoType;
  chatContext: ChatContextType;
  entityInfo: EntityInfoType;
  queryAuthorization: any;
  queryColumns: ColumnType[];
  queryResults: any[];
  queryId: number;
  queryMode: string;
  queryState: string;
  queryText: string;
  response: PluginResonseType;
  parseInfos?: ChatContextType[];
  queryTimeCost?: number;
  similarQueries: SimilarQuestionType[];
  recommendedDimensions: DrillDownDimensionType[];
  textResult: string;
  errorMsg: string;
  textSummary: string;
  dataMasked?: boolean;
  maskedColumns?: string[];
  recommendedChart?: ChartRecommendationType;
  candidateCharts?: ChartRecommendationType[];
  businessExplanation?: BusinessExplanationType;
};

export enum ParseStateEnum {
  COMPLETED = 'COMPLETED',
  PENDING = 'PENDING',
  FAILED = 'FAILED',
}

export type ParseDataType = {
  chatId: number;
  queryId: number;
  queryText: string;
  state: ParseStateEnum;
  selectedParses: ChatContextType[];
  candidateParses: ChatContextType[];
  similarSolvedQuery: SimilarQuestionType[];
};

export type BankIntentMetricType = {
  code?: string;
  name?: string;
  matchedText?: string;
  confidence?: number;
};

export type BankIntentOrganizationType = {
  code?: string;
  name?: string;
  matchedText?: string;
  confidence?: number;
};

export type BankIntentResultType = {
  originalText?: string;
  normalizedText?: string;
  scene?: string;
  intent?: string;
  confidence?: number;
  clarificationRequired?: boolean;
  metrics?: BankIntentMetricType[];
  organizations?: BankIntentOrganizationType[];
  time?: {
    expression?: string;
    startDate?: string;
    endDate?: string;
    granularity?: string;
    ambiguous?: boolean;
  };
  filters?: Array<{
    field?: string;
    operator?: string;
    value?: string;
    sourceText?: string;
  }>;
  reasons?: string[];
};

export type ChartRecommendationType = {
  chartType?: string;
  confidence?: number;
  reason?: string;
  dimensionFields?: string[];
  metricFields?: string[];
};

export type BusinessExplanationType = {
  summary?: string;
  confidence?: number;
  timeRange?: string;
  evidence?: string[];
  warnings?: string[];
  metricDefinitions?: Record<string, string>;
};

export type QueryDataType = {
  chatContext: ChatContextType;
  aggregateInfo: AggregateInfoType;
  queryColumns: ColumnType[];
  queryResults: any[];
};

export type ColumnType = {
  authorized: boolean;
  name: string;
  nameEn: string;
  bizName: string;
  showType: string;
  type: string;
  dataFormatType: string;
  dataFormat: {
    decimalPlaces: number;
    needMultiply100: boolean;
  };
};

export enum SemanticTypeEnum {
  DOMAIN = 'DOMAIN',
  DIMENSION = 'DIMENSION',
  METRIC = 'METRIC',
  VALUE = 'VALUE',
}

export const SEMANTIC_TYPE_MAP = {
  [SemanticTypeEnum.DOMAIN]: '数据模型',
  [SemanticTypeEnum.DIMENSION]: '维度',
  [SemanticTypeEnum.METRIC]: '指标',
  [SemanticTypeEnum.VALUE]: '维度值',
};

export type SuggestionItemType = {
  model: number;
  name: string;
  bizName: string;
};

export type SuggestionType = {
  dimensions: SuggestionItemType[];
  metrics: SuggestionItemType[];
};

export type SuggestionDataType = {
  currentAggregateType: string;
  columns: ColumnType[];
  mainEntity: EntityInfoType;
  suggestions: SuggestionType;
};

export type HistoryMsgItemType = {
  questionId: number;
  queryText: string;
  parseInfos: ChatContextType[];
  parseTimeCost: ParseTimeCostType;
  queryResult: MsgDataType;
  chatId: number;
  createTime: string;
  feedback: string;
  score: number;
  similarQueries: SimilarQuestionType[];
};

export type HistoryType = {
  hasNextPage: boolean;
  list: HistoryMsgItemType[];
};

export type DrillDownDimensionType = {
  id: number;
  model: number;
  name: string;
  bizName: string;
};

export type SendMsgParamsType = {
  msg: string;
  agentId: number;
  modelId: number;
  filters?: FilterItemType[];
};

export type SimilarQuestionType = {
  queryId: number;
  parseId: number;
  queryText: string;
};

export type ParseTimeCostType = {
  parseStartTime: number;
  parseTime: number;
  sqlTime: number;
};

export type RangeValue = [Dayjs, Dayjs];
