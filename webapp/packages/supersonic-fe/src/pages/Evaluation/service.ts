import request from 'umi-request';
import { EvaluationDashboard } from './types';

export function getEvaluationDashboard(): Promise<any> {
  return request<Result<EvaluationDashboard>>(
    `${process.env.API_BASE_URL}evaluation/dashboard`,
    {
      method: 'GET',
    },
  );
}
