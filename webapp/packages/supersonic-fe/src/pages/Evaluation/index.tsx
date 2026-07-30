import { ReloadOutlined } from '@ant-design/icons';
import { ProColumns, ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, message, Progress, Select, Space, Tabs, Tag } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { getEvaluationDashboard } from './service';
import {
  ErrorCase,
  EvaluationDashboard,
  MetricComparison,
  SecurityReport,
} from './types';
import styles from './style.less';

const REVIEW_STORAGE_KEY = 'supersonic-evaluation-review-state';

const SUITE_LABELS: Record<string, string> = {
  intent: '意图识别',
  sqlExecution: 'SQL 执行',
  resultConsistency: '结果一致性',
  multiTurn: '多轮上下文',
  chartRecommendation: '图表与解释',
  qa02a: '权限与脱敏',
  qa02b: '审计与告警',
};

const METRIC_LABELS: Record<string, string> = {
  'intent.intentAccuracy': '意图准确率',
  'intent.metricSetAccuracy': '指标集合准确率',
  'intent.clarificationAccuracy': '澄清准确率',
  'sqlExecution.executionSuccessRate': 'SQL 执行成功率',
  'resultConsistency.resultConsistencyRate': '结果一致率',
  'multiTurn.passRate': '多轮通过率',
  'chartRecommendation.chartAccuracy': '图表准确率',
  'chartRecommendation.explanationCoverage': '解释覆盖率',
  'sqlExecution.averageResponseTimeMs': '平均响应时间',
  'sqlExecution.p95ResponseTimeMs': 'P95 响应时间',
};

const percent = (value?: number) =>
  typeof value === 'number' ? `${(value * 100).toFixed(2)}%` : '-';

const metricValue = (path: string, value?: number) =>
  path.endsWith('TimeMs') && typeof value === 'number'
    ? `${value.toFixed(3)} ms`
    : percent(value);

const statusTag = (status?: string) => (
  <Tag color={status === 'PASS' || status === 'ALLOW' ? 'success' : 'error'}>
    {status || 'UNKNOWN'}
  </Tag>
);

const securitySuite = (key: string, report?: SecurityReport) => ({
  key,
  name: SUITE_LABELS[key],
  count: report?.summary?.caseCount || 0,
  metric: `${report?.summary?.passedControlCount || 0}/${report?.summary?.controlCount || 0} 控制项`,
  progress:
    report?.summary?.controlCount && report.summary.passedControlCount !== undefined
      ? report.summary.passedControlCount / report.summary.controlCount
      : 0,
  status: report?.status,
});

const Evaluation: React.FC = () => {
  const [dashboard, setDashboard] = useState<EvaluationDashboard>();
  const [loading, setLoading] = useState(false);
  const [suiteFilter, setSuiteFilter] = useState<string>();
  const [categoryFilter, setCategoryFilter] = useState<string>();
  const [scenarioFilter, setScenarioFilter] = useState<string>();
  const [difficultyFilter, setDifficultyFilter] = useState<string>();
  const [reviewStates, setReviewStates] = useState<Record<string, string>>(() => {
    try {
      return JSON.parse(localStorage.getItem(REVIEW_STORAGE_KEY) || '{}');
    } catch {
      return {};
    }
  });

  const loadDashboard = async () => {
    setLoading(true);
    try {
      const response = await getEvaluationDashboard();
      const data = response?.data || response;
      setDashboard(data);
    } catch {
      message.error('评测报告加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDashboard();
  }, []);

  const { qa01a, qa01b, qa02a, qa02b } = dashboard?.reports || {};
  const suiteRows = useMemo(() => {
    const businessRows = Object.entries(qa01a?.suites || {}).map(([key, suite]) => {
      const accuracy =
        suite.intentAccuracy ??
        suite.executionSuccessRate ??
        suite.resultConsistencyRate ??
        suite.passRate ??
        suite.chartAccuracy;
      return {
        key,
        name: SUITE_LABELS[key] || key,
        count: suite.caseCount || suite.testCount || 0,
        metric: percent(accuracy),
        progress: accuracy || 0,
        status: suite.status,
      };
    });
    return [...businessRows, securitySuite('qa02a', qa02a), securitySuite('qa02b', qa02b)];
  }, [qa01a, qa02a, qa02b]);

  const metricRows = qa01b?.metricComparison || [];
  const errorCases = qa01b?.errorCases || [];
  const filteredErrors = errorCases.filter(item => {
    return (
      (!suiteFilter || item.suite === suiteFilter) &&
      (!categoryFilter || item.category === categoryFilter) &&
      (!scenarioFilter || item.scenario === scenarioFilter) &&
      (!difficultyFilter || item.difficulty === difficultyFilter)
    );
  });

  const setReviewState = (item: ErrorCase, value: string) => {
    const key = `${item.suite || 'unknown'}:${item.id || 'unknown'}`;
    const next = { ...reviewStates, [key]: value };
    setReviewStates(next);
    localStorage.setItem(REVIEW_STORAGE_KEY, JSON.stringify(next));
  };

  const suiteColumns: ProColumns[] = [
    { title: '评测套件', dataIndex: 'name' },
    { title: '样本/用例', dataIndex: 'count', width: 120 },
    {
      title: '核心指标',
      dataIndex: 'metric',
      width: 260,
      render: (_, row: any) => (
        <Space size={12}>
          <Progress
            percent={Math.round(row.progress * 100)}
            size="small"
            showInfo={false}
            style={{ width: 120 }}
          />
          <span>{row.metric}</span>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (_, row: any) => statusTag(row.status),
    },
  ];

  const metricColumns: ProColumns<MetricComparison>[] = [
    {
      title: '指标',
      dataIndex: 'path',
      render: (_, row) => (
        <span className={styles.metricName}>{METRIC_LABELS[row.path] || row.path}</span>
      ),
    },
    {
      title: '基线版本',
      dataIndex: 'baseline',
      render: (_, row) => metricValue(row.path, row.baseline),
    },
    {
      title: '当前版本',
      dataIndex: 'current',
      render: (_, row) => metricValue(row.path, row.current),
    },
    {
      title: '变化',
      dataIndex: 'delta',
      render: (_, row) => {
        const delta = row.delta || 0;
        const improved = row.direction === 'lower' ? delta < 0 : delta > 0;
        return (
          <span className={improved ? styles.deltaPositive : delta ? styles.deltaNegative : ''}>
            {delta > 0 ? '+' : ''}
            {row.path.endsWith('TimeMs') ? `${delta.toFixed(3)} ms` : percent(delta)}
          </span>
        );
      },
    },
    {
      title: '门禁',
      dataIndex: 'status',
      width: 100,
      render: (_, row) => statusTag(row.status),
    },
  ];

  const errorColumns: ProColumns<ErrorCase>[] = [
    { title: '案例', dataIndex: 'id', width: 150 },
    {
      title: '套件',
      dataIndex: 'suite',
      width: 130,
      render: (_, row) => SUITE_LABELS[row.suite || ''] || row.suite || '-',
    },
    { title: '错误类型', dataIndex: 'category', width: 160 },
    { title: '错误摘要', dataIndex: 'message', ellipsis: true },
    {
      title: '修复状态',
      dataIndex: 'reviewStatus',
      width: 150,
      render: (_, row) => {
        const key = `${row.suite || 'unknown'}:${row.id || 'unknown'}`;
        return (
          <Select
            value={reviewStates[key] || 'PENDING'}
            style={{ width: 120 }}
            onChange={value => setReviewState(row, value)}
            options={[
              { label: '待复核', value: 'PENDING' },
              { label: '已确认', value: 'CONFIRMED' },
              { label: '修复中', value: 'FIXING' },
              { label: '已关闭', value: 'CLOSED' },
            ]}
          />
        );
      },
    },
  ];

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>评测与错误分析</h1>
          <div className={styles.subtitle}>
            数据集 {qa01a?.evaluationMode || '-'} 模式 · 更新于{' '}
            {qa01b?.generatedAt || qa01a?.evaluatedAt || '-'}
          </div>
        </div>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={loadDashboard}>
          刷新报告
        </Button>
      </header>

      {dashboard?.status === 'PARTIAL' && (
        <Alert
          type="warning"
          showIcon
          message={`当前仅加载 ${dashboard.availableReportCount || 0}/4 份评测报告`}
          style={{ marginBottom: 16 }}
        />
      )}

      <section className={styles.summary} aria-label="评测摘要">
        <div className={styles.summaryItem}>
          <span className={styles.summaryLabel}>发布决策</span>
          <span className={styles.summaryValue}>{qa01b?.releaseDecision || '-'}</span>
        </div>
        <div className={styles.summaryItem}>
          <span className={styles.summaryLabel}>业务套件</span>
          <span className={styles.summaryValue}>
            {qa01a?.summary?.passedSuiteCount || 0}/{qa01a?.summary?.requiredSuiteCount || 0}
          </span>
        </div>
        <div className={styles.summaryItem}>
          <span className={styles.summaryLabel}>权限安全用例</span>
          <span className={styles.summaryValue}>
            {qa02a?.summary?.caseCount || 0}
          </span>
        </div>
        <div className={styles.summaryItem}>
          <span className={styles.summaryLabel}>退化指标</span>
          <span className={styles.summaryValue}>{qa01b?.summary?.regressionCount || 0}</span>
        </div>
      </section>

      <section className={styles.content}>
        <Tabs
          items={[
            {
              key: 'overview',
              label: '评测总览',
              children: (
                <ProTable
                  rowKey="key"
                  columns={suiteColumns}
                  dataSource={suiteRows}
                  search={false}
                  options={false}
                  pagination={false}
                  loading={loading}
                />
              ),
            },
            {
              key: 'comparison',
              label: '版本对比',
              children: (
                <>
                  <Space style={{ marginBottom: 16 }}>
                    <Tag>基线：{qa01b?.versions?.baseline || '-'}</Tag>
                    <Tag color="blue">当前：{qa01b?.versions?.current || '-'}</Tag>
                    {statusTag(qa01b?.releaseDecision)}
                  </Space>
                  <ProTable
                    rowKey="path"
                    columns={metricColumns}
                    dataSource={metricRows}
                    search={false}
                    options={false}
                    pagination={false}
                    loading={loading}
                  />
                  {(qa01b?.sourceComparison || []).length > 0 && (
                    <ProTable
                      rowKey="field"
                      headerTitle="评测来源一致性"
                      columns={[
                        { title: '来源字段', dataIndex: 'field' },
                        { title: '基线', dataIndex: 'baseline', ellipsis: true },
                        { title: '当前', dataIndex: 'current', ellipsis: true },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          width: 100,
                          render: (_, row: any) => statusTag(row.status),
                        },
                      ]}
                      dataSource={qa01b?.sourceComparison || []}
                      search={false}
                      options={false}
                      pagination={false}
                    />
                  )}
                  {(qa01b?.stageTimingComparison || []).length > 0 && (
                    <ProTable
                      rowKey="stage"
                      headerTitle="阶段耗时对比"
                      columns={[
                        { title: '阶段', dataIndex: 'stage' },
                        { title: '基线耗时', dataIndex: 'baselineDurationMs' },
                        { title: '当前耗时', dataIndex: 'currentDurationMs' },
                        { title: '变化', dataIndex: 'deltaMs' },
                      ]}
                      dataSource={qa01b?.stageTimingComparison || []}
                      search={false}
                      options={false}
                      pagination={false}
                    />
                  )}
                </>
              ),
            },
            {
              key: 'errors',
              label: `错误案例 (${errorCases.length})`,
              children: (
                <>
                  <div className={styles.filters}>
                    <Select
                      allowClear
                      placeholder="评测套件"
                      style={{ width: 160 }}
                      value={suiteFilter}
                      onChange={setSuiteFilter}
                      options={[...new Set(errorCases.map(item => item.suite).filter(Boolean))].map(
                        value => ({ label: SUITE_LABELS[value!] || value, value }),
                      )}
                    />
                    <Select
                      allowClear
                      placeholder="错误类型"
                      style={{ width: 180 }}
                      value={categoryFilter}
                      onChange={setCategoryFilter}
                      options={[
                        ...new Set(errorCases.map(item => item.category).filter(Boolean)),
                      ].map(value => ({ label: value, value }))}
                    />
                    <Select
                      allowClear
                      placeholder="业务场景"
                      style={{ width: 160 }}
                      value={scenarioFilter}
                      onChange={setScenarioFilter}
                      options={[
                        ...new Set(errorCases.map(item => item.scenario).filter(Boolean)),
                      ].map(value => ({ label: value, value }))}
                    />
                    <Select
                      allowClear
                      placeholder="难度"
                      style={{ width: 120 }}
                      value={difficultyFilter}
                      onChange={setDifficultyFilter}
                      options={[
                        ...new Set(errorCases.map(item => item.difficulty).filter(Boolean)),
                      ].map(value => ({ label: value, value }))}
                    />
                  </div>
                  {filteredErrors.length ? (
                    <ProTable
                      rowKey={row => `${row.suite}:${row.id}`}
                      columns={errorColumns}
                      dataSource={filteredErrors}
                      search={false}
                      options={false}
                      pagination={{ pageSize: 20 }}
                    />
                  ) : (
                    <Empty description="当前版本没有错误案例" />
                  )}
                </>
              ),
            },
          ]}
        />
      </section>
    </main>
  );
};

export default Evaluation;
