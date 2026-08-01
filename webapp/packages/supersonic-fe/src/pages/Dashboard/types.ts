import type { DashboardSemanticQuery } from 'supersonic-chat-sdk';

export type DashboardStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type DashboardAccessScope = 'PRIVATE' | 'ORGANIZATION' | 'DOMAIN';
export type DashboardChartType = 'KPI_CARD' | 'TABLE' | 'LINE' | 'BAR' | 'PIE' | 'COMBO';

export type DashboardLayout = {
  order: number;
  span: 1 | 2;
};

export type DashboardComponent = {
  id: string;
  title: string;
  question?: string;
  sourceQueryId?: number;
  sourceParseId?: number;
  chartType: DashboardChartType;
  query: DashboardSemanticQuery;
  layout: DashboardLayout;
};

export type DashboardGlobalFilter = {
  id: string;
  label: string;
  bizName: string;
  operator: string;
  value: string[];
};

export type DashboardConfig = {
  schemaVersion: 1;
  refreshInterval: number;
  globalFilters: DashboardGlobalFilter[];
  components: DashboardComponent[];
};

export type Dashboard = {
  id: number;
  domainId: number;
  name: string;
  description?: string;
  status: DashboardStatus;
  accessScope: DashboardAccessScope;
  owner: string;
  organizationId?: string;
  config?: string;
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
  pageNum: number;
  pageSize: number;
  total: number;
};

export type DashboardQueryResult = {
  resultList: Record<string, unknown>[];
  totalCount?: number;
};
