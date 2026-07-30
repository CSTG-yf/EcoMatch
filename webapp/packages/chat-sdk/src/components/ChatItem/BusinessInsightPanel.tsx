import { Alert, Tag } from 'antd';
import { BarChartOutlined, FileSearchOutlined } from '@ant-design/icons';
import { BusinessExplanationType, ChartRecommendationType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';

type Props = {
  explanation?: BusinessExplanationType;
  recommendation?: ChartRecommendationType;
};

const CHART_LABELS: Record<string, string> = {
  TABLE: '表格',
  METRIC_CARD: '指标卡',
  LINE: '折线图',
  BAR: '柱状图',
  PIE: '饼图',
  COMBO: '组合图',
};

const BusinessInsightPanel: React.FC<Props> = ({ explanation, recommendation }) => {
  if (!explanation && !recommendation) {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const definitions = Object.entries(explanation?.metricDefinitions || {});
  return (
    <section className={`${prefixCls}-business-insight`} aria-label="业务解释">
      {recommendation && (
        <div className={`${prefixCls}-insight-block`}>
          <div className={`${prefixCls}-insight-heading`}>
            <BarChartOutlined />
            <span>图表推荐</span>
            {recommendation.chartType && (
              <Tag>{CHART_LABELS[recommendation.chartType] || recommendation.chartType}</Tag>
            )}
            {typeof recommendation.confidence === 'number' && (
              <span>{(recommendation.confidence * 100).toFixed(1)}%</span>
            )}
          </div>
          {recommendation.reason && <p>{recommendation.reason}</p>}
        </div>
      )}
      {explanation && (
        <div className={`${prefixCls}-insight-block`}>
          <div className={`${prefixCls}-insight-heading`}>
            <FileSearchOutlined />
            <span>业务解释</span>
            {explanation.timeRange && <Tag>{explanation.timeRange}</Tag>}
          </div>
          {explanation.summary && <p>{explanation.summary}</p>}
          {explanation.evidence && explanation.evidence.length > 0 && (
            <ul>
              {explanation.evidence.map(item => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          )}
          {definitions.length > 0 && (
            <dl>
              {definitions.map(([name, definition]) => (
                <div key={name}>
                  <dt>{name}</dt>
                  <dd>{definition}</dd>
                </div>
              ))}
            </dl>
          )}
          {explanation.warnings?.map(warning => (
            <Alert key={warning} type="warning" showIcon message={warning} />
          ))}
        </div>
      )}
    </section>
  );
};

export default BusinessInsightPanel;
