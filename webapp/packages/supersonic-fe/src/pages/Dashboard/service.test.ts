import request from '@/services/request';
import { createEmptyDashboardConfig } from './model';

jest.mock('@/services/request', () => ({
  __esModule: true,
  default: jest.fn(),
}));

const mockedRequest = request as jest.MockedFunction<typeof request>;
const originalApiBaseUrl = process.env.API_BASE_URL;
process.env.API_BASE_URL = '/api/semantic/';
const {
  createDashboard,
  getDashboardDataSetDomain,
  getDashboardModel,
  refreshDashboardQuery,
  updateDashboard,
} = require('./service');

describe('dashboard service contracts', () => {
  afterAll(() => {
    if (originalApiBaseUrl == null) {
      delete process.env.API_BASE_URL;
    } else {
      process.env.API_BASE_URL = originalApiBaseUrl;
    }
  });

  beforeEach(() => {
    mockedRequest.mockReset();
  });

  it('serializes dashboard configs for create and update requests', async () => {
    const config = createEmptyDashboardConfig();

    await createDashboard({
      domainId: 10,
      name: '经营看板',
      accessScope: 'PRIVATE',
      config,
    });
    await updateDashboard(7, {
      version: 2,
      name: '经营看板',
      accessScope: 'PRIVATE',
      config,
    });

    expect(mockedRequest).toHaveBeenNthCalledWith(
      1,
      '/api/semantic/dashboard',
      expect.objectContaining({
        method: 'POST',
        data: expect.objectContaining({ config: JSON.stringify(config) }),
      }),
    );
    expect(mockedRequest).toHaveBeenNthCalledWith(
      2,
      '/api/semantic/dashboard/7',
      expect.objectContaining({
        method: 'PUT',
        data: expect.objectContaining({ config: JSON.stringify(config) }),
      }),
    );
  });

  it('refreshes only a server-authorized persisted dashboard component', async () => {
    await refreshDashboardQuery(7, 'component-1');

    expect(mockedRequest).toHaveBeenCalledWith('/api/chat/query/dashboardQueryData', {
      method: 'POST',
      data: { dashboardId: 7, componentId: 'component-1' },
    });
  });

  it('keeps model lookup on the viewer-authorized endpoint', async () => {
    await getDashboardModel(33);

    expect(mockedRequest).toHaveBeenCalledWith('/api/semantic/model/getModelListByIds/33', {
      method: 'GET',
    });
  });

  it('resolves a data set domain through the viewer-authorized endpoint', async () => {
    await getDashboardDataSetDomain(5);

    expect(mockedRequest).toHaveBeenCalledWith('/api/semantic/dataSet/5/domain', {
      method: 'GET',
    });
  });
});
