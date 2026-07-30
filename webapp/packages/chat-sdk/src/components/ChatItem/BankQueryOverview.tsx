import { Tag } from 'antd';
import { BankIntentResultType, ChatContextType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';

type Props = {
  intent?: BankIntentResultType;
  parseInfo?: ChatContextType;
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

const SCENE_LABELS: Record<string, string> = {
  OPERATION_ANALYSIS: '经营分析',
  RISK_MANAGEMENT: '风险管控',
  CUSTOMER_MARKETING: '客户营销',
};

const names = (items?: Array<{ name?: string; code?: string }>) =>
  (items || [])
    .map(item => item.name || item.code)
    .filter(Boolean)
    .join('、');

const BankQueryOverview: React.FC<Props> = ({ intent, parseInfo }) => {
  if (!intent || !intent.intent || intent.intent === 'UNKNOWN') {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const metrics = names(intent.metrics);
  const organizations = names(intent.organizations);
  const parseMetrics = names(parseInfo?.metrics);
  const parseDimensions = names(parseInfo?.dimensions);
  const timeRange = intent.time?.expression || parseInfo?.dateInfo?.text;
  return (
    <section className={`${prefixCls}-bank-overview`} aria-label="标准化问数意图">
      <div className={`${prefixCls}-bank-overview-title`}>
        <span>标准化意图</span>
        <Tag>{INTENT_LABELS[intent.intent] || intent.intent}</Tag>
        {intent.scene && <Tag>{SCENE_LABELS[intent.scene] || intent.scene}</Tag>}
        {typeof intent.confidence === 'number' && (
          <span className={`${prefixCls}-bank-overview-confidence`}>
            置信度 {(intent.confidence * 100).toFixed(1)}%
          </span>
        )}
      </div>
      {intent.normalizedText && intent.normalizedText !== intent.originalText && (
        <div className={`${prefixCls}-bank-overview-row`}>
          <span>规范问题</span>
          <strong>{intent.normalizedText}</strong>
        </div>
      )}
      <div className={`${prefixCls}-bank-overview-grid`}>
        {(metrics || parseMetrics) && (
          <div>
            <span>指标</span>
            <strong>{metrics || parseMetrics}</strong>
          </div>
        )}
        {parseDimensions && (
          <div>
            <span>维度</span>
            <strong>{parseDimensions}</strong>
          </div>
        )}
        {organizations && (
          <div>
            <span>机构</span>
            <strong>{organizations}</strong>
          </div>
        )}
        {timeRange && (
          <div>
            <span>查询范围</span>
            <strong>{timeRange}</strong>
          </div>
        )}
        {parseInfo?.dataSet?.name && (
          <div>
            <span>来源模型</span>
            <strong>{parseInfo.dataSet.name}</strong>
          </div>
        )}
      </div>
    </section>
  );
};

export default BankQueryOverview;
