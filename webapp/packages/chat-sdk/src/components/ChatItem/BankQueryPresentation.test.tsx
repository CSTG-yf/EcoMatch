import { render, screen } from '@testing-library/react';
import BankQueryOverview from './BankQueryOverview';
import BusinessInsightPanel from './BusinessInsightPanel';
import QueryStageStatus from './QueryStageStatus';

describe('bank query presentation', () => {
  it('renders the normalized intent and query context', () => {
    render(
      <BankQueryOverview
        intent={
          {
            originalText: '看看本月贷款余额',
            normalizedText: '查询本月贷款余额',
            intent: 'POINT_QUERY',
            scene: 'OPERATION_ANALYSIS',
            confidence: 0.96,
            metrics: [{ name: '贷款余额' }],
            organizations: [{ name: '南京分行' }],
            time: { expression: '本月' },
          } as any
        }
        parseInfo={
          {
            dimensions: [{ name: '机构' }],
            dataSet: { name: '信贷经营主题' },
          } as any
        }
      />,
    );

    expect(screen.getByRole('region', { name: '标准化问数意图' })).toHaveTextContent(
      '指标查询',
    );
    expect(screen.getByText('查询本月贷款余额')).toBeInTheDocument();
    expect(screen.getByText('南京分行')).toBeInTheDocument();
    expect(screen.getByText('信贷经营主题')).toBeInTheDocument();
  });

  it('renders status, chart recommendation, explanation evidence and warnings', () => {
    render(
      <>
        <QueryStageStatus stage="explaining" />
        <BusinessInsightPanel
          recommendation={
            {
              chartType: 'LINE',
              confidence: 0.91,
              reason: '时间序列适合展示趋势',
            } as any
          }
          explanation={
            {
              summary: '贷款余额保持增长。',
              timeRange: '2026年7月',
              evidence: ['较上月增长 3.2%'],
              metricDefinitions: { 贷款余额: '期末贷款本金余额' },
              warnings: ['结果已按权限脱敏'],
            } as any
          }
        />
      </>,
    );

    expect(screen.getByRole('status')).toHaveTextContent('正在生成业务解释');
    expect(screen.getByRole('region', { name: '业务解释' })).toHaveTextContent('折线图');
    expect(screen.getByText('较上月增长 3.2%')).toBeInTheDocument();
    expect(screen.getByText('期末贷款本金余额')).toBeInTheDocument();
    expect(screen.getByText('结果已按权限脱敏')).toBeInTheDocument();
  });

  it('does not render empty optional panels', () => {
    const { container } = render(
      <>
        <QueryStageStatus stage="idle" />
        <BankQueryOverview />
        <BusinessInsightPanel />
      </>,
    );

    expect(container).toBeEmptyDOMElement();
  });
});
