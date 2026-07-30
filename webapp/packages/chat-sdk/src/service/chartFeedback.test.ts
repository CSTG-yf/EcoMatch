import axios from './axiosInstance';
import { recordChartFeedback } from './index';

jest.mock('./axiosInstance', () => ({
  post: jest.fn(),
}));

describe('chart feedback service', () => {
  it('records an authenticated visualization switch without changing QA score', async () => {
    (axios.post as jest.Mock).mockResolvedValue(undefined);

    await recordChartFeedback(12, 'BAR', 'TABLE', 'CHART_SELECTOR');

    expect(axios.post).toHaveBeenCalledWith('/api/chat/manage/chartFeedback', {
      queryId: 12,
      recommendedChart: 'BAR',
      selectedChart: 'TABLE',
      source: 'CHART_SELECTOR',
    });
  });
});
