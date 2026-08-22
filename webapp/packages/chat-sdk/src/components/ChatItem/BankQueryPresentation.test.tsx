import { fireEvent, render, screen } from '@testing-library/react';
import BankAnswerWorkflow from './BankAnswerWorkflow';
import BankClarificationPanel from './BankClarificationPanel';
import BusinessInsightPanel from './BusinessInsightPanel';
import MultiTurnContextBar from './MultiTurnContextBar';
import TechnicalDiagnosticsModal from './TechnicalDiagnosticsModal';
import BankAnswerToolbar from './BankAnswerToolbar';

jest.mock('./index', () => {
  const React = jest.requireActual('react');
  return {
    ChartItemContext: React.createContext({ register: jest.fn(), call: jest.fn() }),
  };
});

beforeEach(() => {
  window.matchMedia = jest.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  }));
});

const openWorkflow = () => {
  fireEvent.click(screen.getByRole('status'));
};

describe('bank query presentation', () => {
  it('renders the model-owned requirements contract in the understand stage', () => {
    render(
      <BankAnswerWorkflow
        question="对比各机构存款"
        workflowStage="completed"
        parseInfo={
          {
            properties: {
              'bank.nl2sql.requirements': JSON.stringify({
                action: 'EXECUTE',
                intent: 'COMPARISON',
                metricCodes: ['ZB001', 'ZB002'],
                organizationCodes: ['ORG004'],
                time: {
                  startDate: '2025-07-31',
                  endDate: '2025-07-31',
                  comparison: 'NONE',
                },
                answerFactTypes: ['VALUE', 'PROVINCE_AVERAGE', 'GAP_VALUE'],
              }),
            },
          } as any
        }
      />
    );

    expect(screen.getByRole('status')).toHaveTextContent('问数完成');
    openWorkflow();
    expect(screen.getAllByText('对比分析').length).toBeGreaterThan(0);
    expect(screen.getByText('指标、机构、时间')).toBeInTheDocument();
    expect(screen.getByText('ORG004')).toBeInTheDocument();
    expect(screen.getByText('2025-07-31')).toBeInTheDocument();
    expect(screen.getByText('VALUE、PROVINCE_AVERAGE、GAP_VALUE')).toBeInTheDocument();
  });

  it('shows the explaining status in the workflow summary', () => {
    render(
      <>
        <BankAnswerWorkflow question="贷款余额趋势" workflowStage="explaining" />
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

  it('only shows SQL versions when developer debug access is enabled', () => {
    const parseInfo = {
      queryMode: 'LLM_S2SQL',
      sqlInfo: {
        parsedS2SQL: 'SELECT 贷款余额 FROM 贷款主题',
        querySQL: 'SELECT loan_balance FROM loan_fact',
      },
    } as any;

    const { unmount } = render(
      <BankAnswerWorkflow
        question="查询贷款余额"
        workflowStage="completed"
        parseInfo={parseInfo}
        isDeveloper={false}
        isDebugMode
      />
    );
    openWorkflow();
    expect(screen.queryByRole('tab', { name: '最终执行 SQL' })).not.toBeInTheDocument();
    unmount();

    render(
      <BankAnswerWorkflow
        question="查询贷款余额"
        workflowStage="completed"
        parseInfo={parseInfo}
        isDeveloper
        isDebugMode
      />
    );
    openWorkflow();
    expect(screen.getByRole('tab', { name: '最终执行 SQL' })).toBeInTheDocument();
  });

  it('hides diagnostics and log export from non-developer users', () => {
    const parseInfo = {
      queryMode: 'LLM_S2SQL',
      sqlInfo: { querySQL: 'SELECT loan_balance FROM loan_fact' },
    } as any;
    const props = {
      msg: '查询贷款余额',
      queryId: 1,
      parseInfo,
      workflowStage: 'completed' as const,
    };

    const { rerender } = render(<BankAnswerToolbar {...props} isDeveloper={false} />);
    expect(screen.queryByRole('button', { name: '技术详情' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '导出日志' })).not.toBeInTheDocument();

    rerender(<BankAnswerToolbar {...props} isDeveloper />);
    expect(screen.getByRole('button', { name: '技术详情' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '导出日志' })).toBeInTheDocument();
  });

  it('does not render empty optional panels', () => {
    const { container } = render(
      <>
        <BankAnswerWorkflow question="测试" workflowStage="idle" />
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

  it('shows confidence, correction records and SQL versions in the diagnostics drawer', () => {
    const parseInfo = {
      queryMode: 'LLM_S2SQL',
      metrics: [{ id: 1, name: '不良贷款率', description: '不良贷款余额除以贷款余额' }],
      dimensions: [{ id: 2, name: '机构' }],
      dimensionFilters: [{ name: '机构', operator: '=', value: '南京分行' }],
      dateInfo: { text: '2026年一季度' },
      elementMatches: [
        {
          detectWord: '不良贷款率',
          similarity: 0.9,
          llmMatched: true,
          element: { id: 1, name: '不良贷款率', type: 'METRIC' },
        },
      ],
      sqlInfo: {
        parsedS2SQL: 'SELECT 不良贷款率 FROM 风险主题',
        correctedS2SQL: 'SELECT 不良贷款率 FROM 风险主题',
        querySQL: 'SELECT bad_loan_rate FROM risk_fact',
      },
      sqlEvaluation: {
        isValidated: true,
        semanticScore: 1,
        errorType: 'NONE',
        features: ['SINGLE_TABLE'],
      },
    } as any;

    render(
      <TechnicalDiagnosticsModal
        open
        question="查询南京分行不良贷款率"
        parseInfo={parseInfo}
        workflowStage="completed"
        onClose={() => {}}
      />
    );

    expect(screen.getByText('技术详情')).toBeInTheDocument();
    expect(screen.getByText('90.0% · 高')).toBeInTheDocument();
    expect(screen.getByText('100.0%')).toBeInTheDocument();
    expect(screen.getByText('校验通过')).toBeInTheDocument();
    expect(screen.getByText('转换与修正记录')).toBeInTheDocument();
    expect(screen.getByText('Schema 映射证据')).toBeInTheDocument();
    expect(screen.getByText('LLM 映射')).toBeInTheDocument();
    expect(screen.getByText('SQL 版本')).toBeInTheDocument();
    expect(screen.getByText('最终执行 SQL')).toBeInTheDocument();
  });

  it('shows the sanitized bank plan tool trace without exposing SQL or private reasoning', () => {
    const parseInfo = {
      queryMode: 'LLM_S2SQL',
      metrics: [{ id: 1, name: '各项贷款余额' }],
      dimensions: [{ id: 2, name: '机构' }],
      sqlInfo: {},
      properties: {
        'bank.nl2sql.trace': [
          {
            attempt: 1,
            traceId: 'trace-1',
            action: 'REPAIRING',
            actionMessage: '工具返回可修正错误，正在重新生成完整计划。',
            planSummary: {
              intent: 'RANKING',
              metrics: ['ZB002'],
              organizations: ['ORG001'],
              timeGranularity: 'MONTH',
              timeComparison: 'NONE',
              calculationType: 'DIRECT',
              outputColumns: ['rank', 'aggregate_value'],
            },
            failedStage: 'DATABASE_EXECUTE',
            errorCode: 'JDBC_GRAMMAR',
            message: '数据库执行失败，请根据允许值修正完整计划。',
            stageResults: [
              { stage: 'COMPILE', status: 'SUCCEEDED', message: '查询编译通过。' },
              {
                stage: 'DATABASE_EXECUTE',
                status: 'FAILED',
                errorCode: 'JDBC_GRAMMAR',
                message: '数据库执行失败，请根据允许值修正完整计划。',
              },
            ],
          },
          {
            attempt: 2,
            traceId: 'trace-1',
            action: 'SUCCEEDED',
            actionMessage: '计划执行及结果语义检查通过。',
            planSummary: {
              intent: 'RANKING',
              metrics: ['ZB002'],
              organizations: ['ORG001'],
              timeGranularity: 'MONTH',
              timeComparison: 'NONE',
              calculationType: 'DIRECT',
              outputColumns: ['rank', 'aggregate_value'],
            },
            stageResults: [
              { stage: 'DATABASE_EXECUTE', status: 'SUCCEEDED', message: '数据库执行通过。' },
              { stage: 'RESULT_SEMANTIC', status: 'SUCCEEDED', message: '结果语义检查通过。' },
            ],
          },
        ],
      },
    } as any;

    render(
      <TechnicalDiagnosticsModal
        open
        question="查询贷款余额排名"
        parseInfo={parseInfo}
        workflowStage="completed"
        onClose={() => {}}
      />
    );

    expect(screen.getByText('工具执行记录')).toBeInTheDocument();
    expect(screen.getByText('第 1 次计划')).toBeInTheDocument();
    expect(screen.getByText(/正在重新生成完整计划/)).toBeInTheDocument();
    expect(screen.getAllByText(/数据库执行失败/).length).toBeGreaterThan(0);
    expect(screen.getByText('第 2 次计划')).toBeInTheDocument();
    expect(screen.getByText(/计划执行及结果语义检查通过/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/select\s/i);
    expect(document.body).not.toHaveTextContent(/思维|推理过程|chain.of.thought/i);
  });
});
