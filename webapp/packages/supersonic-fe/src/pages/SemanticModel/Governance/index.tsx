import {
  ApartmentOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  FileSearchOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import {
  Badge,
  Button,
  Empty,
  Input,
  message,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useState } from 'react';
import ImportPanel from './ImportPanel';
import MetricGovernanceDrawer from './MetricGovernanceDrawer';
import {
  bootstrapGovernance,
  getGovernanceDomains,
  getGovernanceModels,
  getGovernanceTerms,
  getModelMetrics,
  listGovernanceMetrics,
  listMetricConflicts,
} from './service';
import type { GovernanceRecord, GovernanceStatus, MetricConflict } from './types';
import styles from './style.less';

const { Text, Title } = Typography;

interface ModelOption {
  id: number;
  name: string;
  domainId: number;
  domainName: string;
}

const statusMeta: Record<GovernanceStatus, { color: string; text: string }> = {
  DRAFT: { color: 'default', text: '草稿' },
  PENDING: { color: 'processing', text: '审批中' },
  APPROVED: { color: 'success', text: '已审批' },
  PUBLISHED: { color: 'green', text: '已发布' },
  REJECTED: { color: 'error', text: '已驳回' },
  INACTIVE: { color: 'warning', text: '已停用' },
};

const responseData = (response: any): any => {
  if (response?.code !== undefined) {
    if (response.code !== 200) throw new Error(response.msg || '请求失败');
    return response.data;
  }
  return response;
};

const flattenDomains = (domains: any[]): any[] =>
  (domains || []).flatMap((domain) => [domain, ...flattenDomains(domain.children || [])]);

const GovernancePage: React.FC = () => {
  const [models, setModels] = useState<ModelOption[]>([]);
  const [modelId, setModelId] = useState<number>();
  const [records, setRecords] = useState<GovernanceRecord[]>([]);
  const [conflicts, setConflicts] = useState<MetricConflict[]>([]);
  const [terms, setTerms] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [bootstrapping, setBootstrapping] = useState(false);
  const [metricKeyword, setMetricKeyword] = useState('');
  const [termKeyword, setTermKeyword] = useState('');
  const [status, setStatus] = useState<GovernanceStatus>();
  const [selectedMetricId, setSelectedMetricId] = useState<number>();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const selectedModel = models.find((item) => item.id === modelId);

  const loadModels = async () => {
    try {
      const domainResponse = responseData(await getGovernanceDomains());
      const domains = flattenDomains(domainResponse || []).filter((item) => item.hasModel);
      const modelGroups = await Promise.all(
        domains.map(async (domain) => {
          const modelResponse = responseData(await getGovernanceModels(domain.id));
          return (modelResponse || []).map((model: any) => ({
            ...model,
            domainId: domain.id,
            domainName: domain.name,
          }));
        }),
      );
      const nextModels = modelGroups.flat();
      setModels(nextModels);
      setModelId((current) => current || nextModels[0]?.id);
    } catch (error: any) {
      message.error(error.message || '语义模型加载失败');
    }
  };

  const loadData = async () => {
    if (!modelId || !selectedModel) return;
    setLoading(true);
    try {
      const [governanceResponse, metricResponse, conflictResponse, termResponse] =
        await Promise.all([
          listGovernanceMetrics(modelId),
          getModelMetrics(modelId),
          listMetricConflicts(modelId),
          getGovernanceTerms(selectedModel.domainId),
        ]);
      const governanceRows: GovernanceRecord[] = responseData(governanceResponse) || [];
      const metricData = responseData(metricResponse) || {};
      const metrics = metricData.list || metricData || [];
      const metricMap = new Map(metrics.map((metric: any) => [metric.id, metric]));
      setRecords(governanceRows.map((item) => ({ ...item, metric: metricMap.get(item.metricId) })));
      setConflicts(responseData(conflictResponse) || []);
      setTerms(responseData(termResponse) || []);
    } catch (error: any) {
      message.error(error.message || '治理数据加载失败');
      setRecords([]);
      setConflicts([]);
      setTerms([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadModels();
  }, []);

  useEffect(() => {
    loadData();
  }, [modelId]);

  const initializeGovernance = async () => {
    if (!modelId) return;
    setBootstrapping(true);
    try {
      const response = await bootstrapGovernance(modelId, {
        ownerDepartment: '待维护',
        sourceSystem: '待维护',
        businessDefinition: '待补充业务口径',
        changeSummary: '初始化指标治理档案',
      });
      const count = responseData(response);
      message.success(`已初始化 ${count || 0} 个指标治理档案`);
      await loadData();
    } catch (error: any) {
      message.error(error.message || '治理档案初始化失败');
    } finally {
      setBootstrapping(false);
    }
  };

  const openMetric = (metricId?: number) => {
    if (!metricId) return;
    setSelectedMetricId(metricId);
    setDrawerOpen(true);
  };

  const filteredRecords = useMemo(
    () =>
      records.filter((record) => {
        const text = `${record.metric?.name || ''} ${record.metric?.bizName || ''} ${
          record.ownerDepartment || ''
        }`.toLowerCase();
        return (
          (!metricKeyword || text.includes(metricKeyword.toLowerCase())) &&
          (!status || record.governanceStatus === status)
        );
      }),
    [records, metricKeyword, status],
  );

  const filteredTerms = useMemo(
    () =>
      terms.filter((term) =>
        `${term.name || ''} ${(term.alias || []).join(' ')} ${term.description || ''}`
          .toLowerCase()
          .includes(termKeyword.toLowerCase()),
      ),
    [terms, termKeyword],
  );

  const stats = useMemo(() => {
    const published = records.filter((item) => item.governanceStatus === 'PUBLISHED').length;
    const pending = records.filter((item) => item.governanceStatus === 'PENDING').length;
    return { total: records.length, published, pending, conflicts: conflicts.length };
  }, [records, conflicts]);

  const metricColumns: any[] = [
    {
      title: '指标',
      key: 'metric',
      width: 260,
      fixed: 'left',
      render: (_: any, record: GovernanceRecord) => (
        <div className={styles.metricNameCell}>
          <Text strong>{record.metric?.name || `指标 ${record.metricId}`}</Text>
          <Text type="secondary">{record.metric?.bizName || `ID ${record.metricId}`}</Text>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'governanceStatus',
      width: 110,
      render: (value: GovernanceStatus) => (
        <Tag color={statusMeta[value]?.color}>{statusMeta[value]?.text || value}</Tag>
      ),
    },
    {
      title: '版本',
      dataIndex: 'currentVersion',
      width: 80,
      render: (value: number) => `V${value || 0}`,
    },
    { title: '责任部门', dataIndex: 'ownerDepartment', width: 160, ellipsis: true },
    { title: '来源系统', dataIndex: 'sourceSystem', width: 160, ellipsis: true },
    {
      title: '业务口径',
      dataIndex: 'businessDefinition',
      width: 300,
      ellipsis: true,
    },
    {
      title: '生效日期',
      width: 210,
      render: (_: any, record: GovernanceRecord) =>
        record.effectiveFrom
          ? `${dayjs(record.effectiveFrom).format('YYYY-MM-DD')} 至 ${
              record.effectiveTo ? dayjs(record.effectiveTo).format('YYYY-MM-DD') : '长期'
            }`
          : '-',
    },
    {
      title: '操作',
      fixed: 'right',
      width: 90,
      render: (_: any, record: GovernanceRecord) => (
        <Tooltip title="查看治理详情">
          <Button type="text" icon={<EyeOutlined />} onClick={() => openMetric(record.metricId)} />
        </Tooltip>
      ),
    },
  ];

  const conflictColumns: any[] = [
    {
      title: '等级',
      dataIndex: 'severity',
      width: 100,
      render: (value: string) => <Tag color={value === 'HIGH' ? 'error' : 'warning'}>{value}</Tag>,
    },
    { title: '类型', dataIndex: 'type', width: 180 },
    { title: '冲突键', dataIndex: 'key', width: 180, ellipsis: true },
    { title: '说明', dataIndex: 'message', ellipsis: true },
    {
      title: '涉及指标',
      dataIndex: 'metricIds',
      width: 220,
      render: (values: number[]) => (
        <Space size={[4, 4]} wrap>
          {(values || []).map((value) => (
            <Button key={value} type="link" size="small" onClick={() => openMetric(value)}>
              {value}
            </Button>
          ))}
        </Space>
      ),
    },
  ];

  const termColumns: any[] = [
    { title: '术语', dataIndex: 'name', width: 220, fixed: 'left' },
    {
      title: '同义词',
      dataIndex: 'alias',
      width: 260,
      render: (values: string[]) => (values?.length ? values.join('、') : '-'),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    { title: '维护人', dataIndex: 'updatedBy', width: 120 },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ];

  const toolbar = (
    <div className={styles.tableToolbar}>
      <Space wrap>
        <Input.Search
          allowClear
          placeholder="搜索指标、业务名或责任部门"
          style={{ width: 300 }}
          onSearch={setMetricKeyword}
          onChange={(event) => !event.target.value && setMetricKeyword('')}
        />
        <Select
          allowClear
          placeholder="治理状态"
          style={{ width: 140 }}
          value={status}
          options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.text }))}
          onChange={setStatus}
        />
      </Space>
      <Space>
        <Button
          icon={<SafetyCertificateOutlined />}
          loading={bootstrapping}
          disabled={!modelId}
          onClick={initializeGovernance}
        >
          初始化治理档案
        </Button>
        <Tooltip title="刷新">
          <Button icon={<ReloadOutlined />} onClick={loadData} />
        </Tooltip>
      </Space>
    </div>
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <Title level={3}>指标与术语治理</Title>
          <Text type="secondary">统一维护指标口径、版本、审批、血缘与银行术语资源</Text>
        </div>
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="选择语义模型"
          value={modelId}
          className={styles.modelSelect}
          options={models.map((item) => ({
            value: item.id,
            label: `${item.domainName} / ${item.name}`,
          }))}
          onChange={setModelId}
        />
      </header>

      <section className={styles.statBand}>
        <div>
          <FileSearchOutlined />
          <span>治理指标</span>
          <strong>{stats.total}</strong>
        </div>
        <div>
          <CheckCircleOutlined />
          <span>已发布</span>
          <strong>{stats.published}</strong>
        </div>
        <div>
          <CloudUploadOutlined />
          <span>审批中</span>
          <strong>{stats.pending}</strong>
        </div>
        <div className={stats.conflicts ? styles.dangerStat : ''}>
          <ExclamationCircleOutlined />
          <span>待处理冲突</span>
          <strong>{stats.conflicts}</strong>
        </div>
      </section>

      {models.length === 0 ? (
        <Empty description="暂无可治理的语义模型" />
      ) : (
        <Tabs
          className={styles.workspaceTabs}
          items={[
            {
              key: 'metrics',
              label: '指标治理',
              children: (
                <>
                  {toolbar}
                  <Table
                    rowKey="metricId"
                    size="middle"
                    loading={loading}
                    dataSource={filteredRecords}
                    columns={metricColumns}
                    scroll={{ x: 1450 }}
                    pagination={{ pageSize: 15, showSizeChanger: true }}
                  />
                </>
              ),
            },
            {
              key: 'terms',
              label: `术语 (${terms.length})`,
              children: (
                <>
                  <div className={styles.tableToolbar}>
                    <Input.Search
                      allowClear
                      placeholder="搜索术语、同义词或说明"
                      style={{ width: 320 }}
                      onSearch={setTermKeyword}
                      onChange={(event) => !event.target.value && setTermKeyword('')}
                    />
                  </div>
                  <Table
                    rowKey="id"
                    size="middle"
                    loading={loading}
                    dataSource={filteredTerms}
                    columns={termColumns}
                    scroll={{ x: 1000 }}
                    pagination={{ pageSize: 15 }}
                  />
                </>
              ),
            },
            {
              key: 'conflicts',
              label: (
                <Badge count={conflicts.length} size="small" offset={[8, -2]}>
                  <span>口径冲突</span>
                </Badge>
              ),
              children: (
                <Table
                  rowKey={(record) => `${record.type}-${record.key}`}
                  size="middle"
                  loading={loading}
                  dataSource={conflicts}
                  columns={conflictColumns}
                  locale={{
                    emptyText: (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未发现口径冲突" />
                    ),
                  }}
                  pagination={{ pageSize: 15 }}
                />
              ),
            },
            {
              key: 'import',
              label: (
                <Space size={6}>
                  <ApartmentOutlined />
                  批量导入
                </Space>
              ),
              children: (
                <ImportPanel
                  modelId={modelId}
                  onImported={async () => {
                    await initializeGovernance();
                  }}
                />
              ),
            },
          ]}
        />
      )}

      <MetricGovernanceDrawer
        metricId={selectedMetricId}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        onChanged={loadData}
      />
    </div>
  );
};

export default GovernancePage;
