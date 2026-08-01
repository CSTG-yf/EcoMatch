import request from '@/services/request';
import type {
  Dashboard,
  DashboardAccessScope,
  DashboardPage,
  DashboardQueryResult,
  DashboardStatus,
} from './types';

const base = `${process.env.API_BASE_URL || '/api/semantic/'}dashboard`;

export const listDashboards = (params: {
  domainId: number;
  status?: DashboardStatus;
  pageNum: number;
  pageSize: number;
}) => request<Result<DashboardPage>>(base, { method: 'GET', params });

export const getDashboard = (id: number) => request<Result<Dashboard>>(`${base}/${id}`);

export const createDashboard = (data: {
  domainId: number;
  name: string;
  description?: string;
  accessScope: DashboardAccessScope;
  config: string;
}) => request<Result<Dashboard>>(base, { method: 'POST', data });

export const updateDashboard = (
  id: number,
  data: {
    version: number;
    name: string;
    description?: string;
    accessScope: DashboardAccessScope;
    config: string;
  },
) => request<Result<Dashboard>>(`${base}/${id}`, { method: 'PUT', data });

export const copyDashboard = (id: number, name?: string) =>
  request<Result<Dashboard>>(`${base}/${id}/copy`, { method: 'POST', data: name ? { name } : {} });

export const publishDashboard = (id: number, version: number) =>
  request<Result<Dashboard>>(`${base}/${id}/publish`, { method: 'POST', data: { version } });

export const disableDashboard = (id: number, version: number) =>
  request<Result<Dashboard>>(`${base}/${id}/disable`, { method: 'POST', data: { version } });

export const deleteDashboard = (id: number) =>
  request<Result<void>>(`${base}/${id}`, { method: 'DELETE' });

export const queryDashboardComponent = (data: Record<string, unknown>) =>
  request<Result<DashboardQueryResult>>(
    `${process.env.API_BASE_URL || '/api/semantic/'}query/dataSet`,
    {
      method: 'POST',
      data,
    },
  );
