import request from '@/services/request';
import { serializeDashboardWriteConfig } from './model';
import { Dashboard, DashboardAccessScope, DashboardConfig, DashboardStatus } from './types';

const dashboardBase = `${process.env.API_BASE_URL}dashboard`;

export const getDashboards = (params: {
  domainId: number;
  status?: DashboardStatus;
  pageNum?: number;
  pageSize?: number;
}) =>
  request(`${dashboardBase}`, {
    method: 'GET',
    params,
  });

export const getDashboard = (id: number) =>
  request<Dashboard>(`${dashboardBase}/${id}`, { method: 'GET' });

export const createDashboard = (data: {
  domainId: number;
  name: string;
  description?: string;
  accessScope: DashboardAccessScope;
  config: DashboardConfig;
}) =>
  request<Dashboard>(dashboardBase, {
    method: 'POST',
    data: { ...data, config: serializeDashboardWriteConfig(data.config) },
  });

export const updateDashboard = (
  id: number,
  data: {
    version: number;
    name: string;
    description?: string;
    accessScope: DashboardAccessScope;
    config: DashboardConfig;
  },
) =>
  request<Dashboard>(`${dashboardBase}/${id}`, {
    method: 'PUT',
    data: { ...data, config: serializeDashboardWriteConfig(data.config) },
  });

export const copyDashboard = (id: number) =>
  request<Dashboard>(`${dashboardBase}/${id}/copy`, { method: 'POST' });

export const publishDashboard = (id: number, version: number) =>
  request<Dashboard>(`${dashboardBase}/${id}/publish`, {
    method: 'POST',
    data: { version },
  });

export const disableDashboard = (id: number, version: number) =>
  request<Dashboard>(`${dashboardBase}/${id}/disable`, {
    method: 'POST',
    data: { version },
  });

export const deleteDashboard = (id: number) =>
  request(`${dashboardBase}/${id}`, { method: 'DELETE' });

export const refreshDashboardQuery = (dashboardId: number, componentId: string) =>
  request('/api/chat/query/dashboardQueryData', {
    method: 'POST',
    data: { dashboardId, componentId },
  });

export const getDashboardModel = (modelId: number) =>
  request(`${process.env.API_BASE_URL}model/getModelListByIds/${modelId}`, {
    method: 'GET',
  });

export const getDashboardDomains = () =>
  request(`${process.env.API_BASE_URL}domain/getDomainList`, { method: 'GET' });
