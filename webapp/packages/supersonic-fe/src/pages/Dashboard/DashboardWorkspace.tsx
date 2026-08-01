import {
  Alert,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  message,
} from 'antd';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  DeleteOutlined,
  DragOutlined,
  FilterOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import DashboardChart from './DashboardChart';
import { applyGlobalFilters, parseDashboardConfig, reorderComponents } from './model';
import { getDashboard, queryDashboardComponent, updateDashboard } from './service';
import type {
  Dashboard,
  DashboardAccessScope,
  DashboardChartType,
  DashboardComponent,
  DashboardConfig,
  DashboardGlobalFilter,
} from './types';
import styles from './style.less';

const chartOptions = [
  { label: '指标卡', value: 'KPI_CARD' },
  { label: '表格', value: 'TABLE' },
  { label: '折线图', value: 'LINE' },
  { label: '柱状图', value: 'BAR' },
  { label: '饼图', value: 'PIE' },
  { label: '组合图', value: 'COMBO' },
];

const unwrap = <T,>(response: Result<T>): T => {
  if (Number(response?.code) !== 200) {
    throw new Error(response?.msg || '请求失败');
  }
  return response.data;
};

type ResultState = { loading: boolean; rows: Record<string, unknown>[]; error?: string };

const DashboardWorkspace: React.FC<{ editable: boolean }> = ({ editable }) => {
  const params = useParams<{ id: string }>();
  const dashboardId = Number(params.id);
  const [dashboard, setDashboard] = useState<Dashboard>();
  const [config, setConfig] = useState<DashboardConfig>(() => parseDashboardConfig());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [results, setResults] = useState<Record<string, ResultState>>({});
  const [componentDrawer, setComponentDrawer] = useState(false);
  const [filterDrawer, setFilterDrawer] = useState(false);
  const [draggingId, setDraggingId] = useState<string>();
  const [componentForm] = Form.useForm();
  const [filterForm] = Form.useForm();
  const querySequence = useRef<Record<string, number>>({});

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const data = unwrap(await getDashboard(dashboardId));
      setDashboard(data);
      setConfig(parseDashboardConfig(data.config));
    } catch (error: any) {
      message.error(error?.message || '看板加载失败');
    } finally {
      setLoading(false);
    }
  }, [dashboardId]);

  useEffect(() => {
    if (Number.isFinite(dashboardId)) {
      loadDashboard();
    }
  }, [dashboardId, loadDashboard]);

  const runComponent = useCallback(
    async (component: DashboardComponent, filters = config.globalFilters) => {
      const sequence = (querySequence.current[component.id] || 0) + 1;
      querySequence.current[component.id] = sequence;
      setResults((current) => ({
        ...current,
        [component.id]: {
          ...(current[component.id] || { rows: [] }),
          loading: true,
          error: undefined,
        },
      }));
      try {
        const response = unwrap(
          await queryDashboardComponent(applyGlobalFilters(component, filters) as any),
        );
        if (querySequence.current[component.id] !== sequence) {
          return;
        }
        setResults((current) => ({
          ...current,
          [component.id]: { loading: false, rows: response?.resultList || [] },
        }));
      } catch (error: any) {
        if (querySequence.current[component.id] !== sequence) {
          return;
        }
        setResults((current) => ({
          ...current,
          [component.id]: {
            loading: false,
            rows: current[component.id]?.rows || [],
            error: error?.message || '查询失败',
          },
        }));
      }
    },
    [config.globalFilters],
  );

  const refreshAll = useCallback(
    (nextConfig = config) => {
      nextConfig.components.forEach((component) =>
        runComponent(component, nextConfig.globalFilters),
      );
    },
    [config, runComponent],
  );

  useEffect(() => {
    if (!loading && config.components.length) {
      refreshAll(config);
    }
  }, [loading, dashboardId]);

  useEffect(() => {
    if (!config.refreshInterval) {
      return undefined;
    }
    const timer = window.setInterval(() => refreshAll(), config.refreshInterval * 1000);
    return () => window.clearInterval(timer);
  }, [config.refreshInterval, refreshAll]);

  const save = async () => {
    if (!dashboard) {
      return;
    }
    setSaving(true);
    try {
      const updated = unwrap(
        await updateDashboard(dashboard.id, {
          version: dashboard.version,
          name: dashboard.name,
          description: dashboard.description,
          accessScope: dashboard.accessScope,
          config: JSON.stringify(config),
        }),
      );
      setDashboard(updated);
      setConfig(parseDashboardConfig(updated.config));
      message.success('看板已保存');
    } catch (error: any) {
      message.error(error?.message || '保存失败');
      await loadDashboard();
    } finally {
      setSaving(false);
    }
  };

  const availableDimensions = useMemo(() => {
    const values = new Set<string>();
    config.components.forEach((component) =>
      component.query.groups.forEach((item) => values.add(item)),
    );
    return Array.from(values).map((value) => ({ label: value, value }));
  }, [config.components]);

  const addComponent = async () => {
    const values = await componentForm.validateFields();
    const dimensions = String(values.dimensions || '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
    const metrics = String(values.metrics || '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
    const component: DashboardComponent = {
      id: `manual-${Date.now()}`,
      title: values.title,
      chartType: values.chartType,
      query: {
        dataSetId: values.dataSetId,
        groups: dimensions,
        aggregators: metrics.map((column) => ({ column, func: 'SUM' })),
        dimensionFilters: [],
        metricFilters: [],
        limit: 1000,
        queryType: 'AGGREGATE',
      },
      layout: { order: config.components.length, span: 1 },
    };
    const next = { ...config, components: [...config.components, component] };
    setConfig(next);
    setComponentDrawer(false);
    componentForm.resetFields();
    runComponent(component, next.globalFilters);
  };

  const addFilter = async () => {
    const values = await filterForm.validateFields();
    const nextFilter: DashboardGlobalFilter = {
      id: `filter-${Date.now()}`,
      label: values.label || values.bizName,
      bizName: values.bizName,
      operator: 'IN',
      value: String(values.value || '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean),
    };
    const next = { ...config, globalFilters: [...config.globalFilters, nextFilter] };
    setConfig(next);
    setFilterDrawer(false);
    filterForm.resetFields();
    refreshAll(next);
  };

  const updateComponent = (id: string, patch: Partial<DashboardComponent>) => {
    setConfig((current) => ({
      ...current,
      components: current.components.map((item) => (item.id === id ? { ...item, ...patch } : item)),
    }));
  };

  if (loading) {
    return (
      <div className={styles.fullState}>
        <Spin />
      </div>
    );
  }
  if (!dashboard) {
    return (
      <div className={styles.fullState}>
        <Empty description="看板不存在或无权访问" />
      </div>
    );
  }

  return (
    <div className={styles.workspace}>
      <header className={styles.workspaceHeader}>
        <Space size={12}>
          <Tooltip title="返回看板列表">
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/dashboard')}
            />
          </Tooltip>
          <div>
            <Input
              className={styles.dashboardName}
              bordered={false}
              value={dashboard.name}
              readOnly={!editable}
              onChange={(event) => setDashboard({ ...dashboard, name: event.target.value })}
            />
            <Space size={6} className={styles.dashboardMeta}>
              <Tag>{dashboard.status}</Tag>
              <span>v{dashboard.version}</span>
              <span>{dashboard.owner}</span>
            </Space>
          </div>
        </Space>
        <Space wrap>
          {editable && (
            <Select
              value={dashboard.accessScope}
              style={{ width: 112 }}
              options={[
                { label: '仅自己', value: 'PRIVATE' },
                { label: '本机构', value: 'ORGANIZATION' },
                { label: '主题域', value: 'DOMAIN' },
              ]}
              onChange={(accessScope: DashboardAccessScope) =>
                setDashboard({ ...dashboard, accessScope })
              }
            />
          )}
          <Select
            value={config.refreshInterval}
            style={{ width: 118 }}
            options={[
              { label: '手动刷新', value: 0 },
              { label: '每30秒', value: 30 },
              { label: '每1分钟', value: 60 },
              { label: '每5分钟', value: 300 },
            ]}
            onChange={(refreshInterval) => setConfig({ ...config, refreshInterval })}
          />
          <Button icon={<FilterOutlined />} onClick={() => setFilterDrawer(true)}>
            筛选
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => refreshAll()}>
            刷新
          </Button>
          {editable && (
            <>
              <Button icon={<PlusOutlined />} onClick={() => setComponentDrawer(true)}>
                组件
              </Button>
              <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>
                保存
              </Button>
            </>
          )}
        </Space>
      </header>

      {config.globalFilters.length > 0 && (
        <div className={styles.filterBand}>
          {config.globalFilters.map((filter) => (
            <Tag
              key={filter.id}
              closable
              onClose={(event) => {
                event.preventDefault();
                const next = {
                  ...config,
                  globalFilters: config.globalFilters.filter((item) => item.id !== filter.id),
                };
                setConfig(next);
                refreshAll(next);
              }}
            >
              {filter.label}: {filter.value.join('、')}
            </Tag>
          ))}
        </div>
      )}

      <main className={styles.canvas}>
        {config.components.length === 0 ? (
          <Empty
            image={<AppstoreOutlined className={styles.emptyIcon} />}
            description="暂无看板组件"
          >
            {editable && (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => setComponentDrawer(true)}
              >
                添加组件
              </Button>
            )}
          </Empty>
        ) : (
          <div className={styles.grid}>
            {config.components.map((component) => {
              const result = results[component.id] || { loading: false, rows: [] };
              return (
                <section
                  key={component.id}
                  className={`${styles.component} ${
                    component.layout.span === 2 ? styles.componentWide : ''
                  }`}
                  draggable={editable}
                  onDragStart={() => setDraggingId(component.id)}
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={() => {
                    if (draggingId) {
                      setConfig({
                        ...config,
                        components: reorderComponents(config.components, draggingId, component.id),
                      });
                    }
                    setDraggingId(undefined);
                  }}
                >
                  <div className={styles.componentHeader}>
                    <Space size={8} className={styles.componentTitle}>
                      {editable && <DragOutlined className={styles.dragHandle} />}
                      <Input
                        bordered={false}
                        value={component.title}
                        readOnly={!editable}
                        onChange={(event) =>
                          updateComponent(component.id, { title: event.target.value })
                        }
                      />
                    </Space>
                    {editable && (
                      <Space size={4}>
                        <Select
                          size="small"
                          value={component.chartType}
                          options={chartOptions}
                          style={{ width: 92 }}
                          onChange={(chartType: DashboardChartType) =>
                            updateComponent(component.id, { chartType })
                          }
                        />
                        <Tooltip title={component.layout.span === 2 ? '半宽' : '通栏'}>
                          <Button
                            size="small"
                            type="text"
                            icon={<AppstoreOutlined />}
                            onClick={() =>
                              updateComponent(component.id, {
                                layout: {
                                  ...component.layout,
                                  span: component.layout.span === 2 ? 1 : 2,
                                },
                              })
                            }
                          />
                        </Tooltip>
                        <Popconfirm
                          title="删除该组件？"
                          onConfirm={() =>
                            setConfig({
                              ...config,
                              components: config.components.filter(
                                (item) => item.id !== component.id,
                              ),
                            })
                          }
                        >
                          <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                      </Space>
                    )}
                  </div>
                  {component.question && (
                    <div className={styles.question}>{component.question}</div>
                  )}
                  {result.error && (
                    <Alert
                      type="error"
                      showIcon
                      message={result.error}
                      action={
                        <Button size="small" onClick={() => runComponent(component)}>
                          重试
                        </Button>
                      }
                    />
                  )}
                  <Spin spinning={result.loading}>
                    <DashboardChart component={component} rows={result.rows} />
                  </Spin>
                </section>
              );
            })}
          </div>
        )}
      </main>

      <Drawer
        title="添加查询组件"
        open={componentDrawer}
        onClose={() => setComponentDrawer(false)}
        width={420}
      >
        <Form
          form={componentForm}
          layout="vertical"
          initialValues={{ chartType: 'BAR' }}
          onFinish={addComponent}
        >
          <Form.Item name="title" label="组件名称" rules={[{ required: true }]}>
            <Input maxLength={80} />
          </Form.Item>
          <Form.Item name="dataSetId" label="数据集 ID" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dimensions" label="维度字段">
            <Input placeholder="org_name, product_name" />
          </Form.Item>
          <Form.Item name="metrics" label="指标字段" rules={[{ required: true }]}>
            <Input placeholder="loan_balance" />
          </Form.Item>
          <Form.Item name="chartType" label="图表">
            <Select options={chartOptions} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>
            添加
          </Button>
        </Form>
      </Drawer>

      <Drawer
        title="全局筛选"
        open={filterDrawer}
        onClose={() => setFilterDrawer(false)}
        width={420}
      >
        <Form form={filterForm} layout="vertical" onFinish={addFilter}>
          <Form.Item name="bizName" label="筛选维度" rules={[{ required: true }]}>
            <Select showSearch options={availableDimensions} />
          </Form.Item>
          <Form.Item name="label" label="显示名称">
            <Input maxLength={40} />
          </Form.Item>
          <Form.Item name="value" label="筛选值" rules={[{ required: true }]}>
            <Input placeholder="多个值使用逗号分隔" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>
            应用筛选
          </Button>
        </Form>
      </Drawer>
    </div>
  );
};

export default DashboardWorkspace;
