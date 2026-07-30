import axios from './axiosInstance';
import { recognizeBankIntent } from '.';

jest.mock('./axiosInstance', () => ({
  post: jest.fn(),
}));

describe('bank intent service', () => {
  it('uses the backend recognize contract', async () => {
    (axios.post as jest.Mock).mockResolvedValue({ intent: 'POINT_QUERY' });

    await recognizeBankIntent('查询贷款余额');

    expect(axios.post).toHaveBeenCalledWith('/api/semantic/bank/intent/recognize', {
      queryText: '查询贷款余额',
    });
  });
});
