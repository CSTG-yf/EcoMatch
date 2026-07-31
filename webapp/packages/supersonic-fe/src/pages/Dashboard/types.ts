export type DashboardStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type DashboardAccessScope = 'PRIVATE' | 'ORGANIZATION' | 'DOMAIN';
export type DashboardErrorKind = 'FORBIDDEN' | 'CONFLICT' | 'FAILED';

export type DashboardQuerySource = {
  domainId?: number;
  queryId?: number;
  parseId?: number;
  question: string;
  modelId?: number;
  dataSetId?: number;
  modelName?: string;
  dataSetName?: string;
  masked?: boolean;
  chartType?: string;
  semanticQuery: Record<string, any>;
};

export type DashboardComponentLayout = {
  x: number;
  y: number;
  w: number;
  h: number;
};

export type DashboardComponent = {
  id: string;
  type: 'chart' | 'table' | 'number';
  title: string;
  layout: DashboardComponentLayout;
  visualization: {
    chartType: string;
  };
  query: Record<string, any>;
  masked?: boolean;
};

export type DashboardGlobalFilter = {
  field: string;
  operator: string;
  value: any;
};

export type DashboardConfig = {
  schemaVersion: '1.0';
  layout: {
    columns: 12;
    rowHeight: number;
  };
  globalFilters: DashboardGlobalFilter[];
  refreshIntervalSeconds: number;
  components: DashboardComponent[];
};

export type Dashboard = {
  id: number;
  domainId: number;
  name: string;
  description?: string;
  status: DashboardStatus;
  accessScope: DashboardAccessScope;
  owner?: string;
  organizationId?: string;
  config: DashboardConfig | string | Record<string, any>;
  version: number;
  publishedAt?: string;
  disabledAt?: string;
  createdAt?: string;
  createdBy?: string;
  updatedAt?: string;
  updatedBy?: string;
};

export type DashboardPage = {
  list: Dashboard[];
  total: number;
  pageNum?: number;
  pageSize?: number;
};
