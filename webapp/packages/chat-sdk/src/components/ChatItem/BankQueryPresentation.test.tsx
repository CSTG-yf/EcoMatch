import { fireEvent, render, screen } from '@testing-library/react';
import BankQueryOverview from './BankQueryOverview';
import BankClarificationPanel from './BankClarificationPanel';
import BusinessInsightPanel from './BusinessInsightPanel';
import MultiTurnContextBar from './MultiTurnContextBar';
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
      />
    );

    expect(screen.getByRole('region', { name: '标准化问数意图' })).toHaveTextContent('指标查询');
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
      </>
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
      </>
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('collects official clarification options before resubmitting', () => {
    const onApply = jest.fn();
    render(
      <BankClarificationPanel
        question="最近贷款情况怎么样"
        intent={
          {
            clarificationRequired: true,
            clarifications: [
              {
                type: 'METRIC',
                question: '您希望查询哪个指标？',
                options: ['贷款余额', '不良贷款率'],
              },
              {
                type: 'TIME',
                question: '请选择查询时间范围',
                options: ['月末', '季末'],
              },
            ],
          } as any
        }
        onApply={onApply}
      />
    );

    const submit = screen.getByRole('button', { name: '按所选条件查询' });
    expect(submit).toBeDisabled();
    fireEvent.click(screen.getByLabelText('贷款余额'));
    fireEvent.click(screen.getByLabelText('不良贷款率'));
    fireEvent.click(screen.getByLabelText('季末'));
    expect(submit).toBeEnabled();
    fireEvent.click(submit);
    expect(onApply).toHaveBeenCalledWith(
      '最近贷款情况怎么样；您希望查询哪个指标：贷款余额、不良贷款率；请选择查询时间范围：季末'
    );
  });

  it('shows inherited context, expiry and context actions', () => {
    const onSendMsg = jest.fn();
    const { rerender } = render(
      <MultiTurnContextBar
        question="继续看风险情况"
        context={{
          usedRounds: 2,
          maxRounds: 10,
          operation: 'APPEND',
          rewrittenQuery: '查询南京分行不良贷款率',
          turns: [
            {
              metrics: ['不良贷款率'],
              filters: ['机构 = 南京分行'],
              dateInfo: '2026年一季度',
            },
          ],
        }}
        onSendMsg={onSendMsg}
      />
    );

    expect(screen.getByRole('region', { name: '本轮继承上下文' })).toHaveTextContent(
      '本轮上下文 2/10 轮'
    );
    expect(screen.getByText('查询南京分行不良贷款率')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重新解释' }));
    expect(onSendMsg).toHaveBeenCalledWith('不参考之前，重新解释：继续看风险情况');

    rerender(
      <MultiTurnContextBar
        question="继续看风险情况"
        context={{ expired: true, usedRounds: 0, maxRounds: 10, operation: 'APPEND' }}
      />
    );
    expect(screen.getByText(/已超过 30 分钟/)).toBeInTheDocument();
  });
});
