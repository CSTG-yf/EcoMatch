import { Tag } from 'antd';
import { ChatContextType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';

type Props = {
  parseInfo?: ChatContextType;
};

type ModelRequirements = {
  action?: string;
  intent?: string;
  metricCodes?: string[];
  organizationCodes?: string[];
  time?: {
    startDate?: string;
    endDate?: string;
    comparison?: string;
  };
  answerFactTypes?: string[];
};

const INTENT_LABELS: Record<string, string> = {
  POINT_QUERY: '指标查询',
  COMPARISON: '对比分析',
  RANKING: '排名分析',
  TREND: '趋势分析',
  CHANGE: '变化分析',
  RATIO: '占比分析',
  THRESHOLD: '阈值判断',
  AGGREGATION: '汇总分析',
};

const COMPARISON_LABELS: Record<string, string> = {
  NONE: '无基期对比',
  PERIOD_OVER_PERIOD: '环比',
  YEAR_OVER_YEAR: '同比',
  START_OF_YEAR: '较年初',
  MOM_AND_YOY: '环比和同比',
};

const exactCodes = (values?: string[]) =>
  (values || [])
    .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    .join('、');

const modelRequirements = (parseInfo?: ChatContextType): ModelRequirements | undefined => {
  const value = parseInfo?.properties?.['bank.nl2sql.requirements'];
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as ModelRequirements;
    } catch {
      return undefined;
    }
  }
  return value && typeof value === 'object' ? (value as ModelRequirements) : undefined;
};

const rangeText = (time?: ModelRequirements['time']) => {
  if (!time?.startDate && !time?.endDate) {
    return '';
  }
  if (!time.startDate || time.startDate === time.endDate) {
    return time.endDate || time.startDate || '';
  }
  return `${time.startDate} 至 ${time.endDate}`;
};

const BankQueryOverview: React.FC<Props> = ({ parseInfo }) => {
  const requirements = modelRequirements(parseInfo);
  if (requirements?.action !== 'EXECUTE') {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const metrics = exactCodes(requirements.metricCodes);
  const organizations = exactCodes(requirements.organizationCodes);
  const timeRange = rangeText(requirements.time);
  const factTypes = exactCodes(requirements.answerFactTypes);
  const comparison = requirements.time?.comparison;
  return (
    <section className={`${prefixCls}-bank-overview`} aria-label="模型理解的查询需求">
      <div className={`${prefixCls}-bank-overview-title`}>
        <span>模型理解</span>
        {requirements.intent && <Tag>{INTENT_LABELS[requirements.intent] || requirements.intent}</Tag>}
        <Tag color="blue">需求合同已校验</Tag>
      </div>
      <div className={`${prefixCls}-bank-overview-grid`}>
        {metrics && (
          <div>
            <span>指标代码</span>
            <strong>{metrics}</strong>
          </div>
        )}
        <div>
          <span>机构代码</span>
          <strong>{organizations || '全省范围'}</strong>
        </div>
        {timeRange && (
          <div>
            <span>时间范围</span>
            <strong>{timeRange}</strong>
          </div>
        )}
        {comparison && (
          <div>
            <span>比较口径</span>
            <strong>{COMPARISON_LABELS[comparison] || comparison}</strong>
          </div>
        )}
        {factTypes && (
          <div>
            <span>回答所需事实</span>
            <strong>{factTypes}</strong>
          </div>
        )}
      </div>
    </section>
  );
};

export default BankQueryOverview;
