import {
  CheckOutlined,
  CloudUploadOutlined,
  HistoryOutlined,
  RollbackOutlined,
  SaveOutlined,
  StopOutlined,
} from '@ant-design/icons';
import {
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useState } from 'react';
import {
  createMetricVersion,
  deactivateMetric,
  decideApproval,
  getGovernanceDetail,
  getMetricLineage,
  publishMetricVersion,
  rollbackMetricVersion,
  submitMetricVersion,
  updateGovernance,
} from './service';
import type {
  GovernanceDetail,
  GovernanceStatus,
  MetricApproval,
  MetricLineage,
  MetricVersion,
} from './types';
import styles from './style.less';

const { Paragraph, Text } = Typography;
const { RangePicker } = DatePicker;

interface Props {
  metricId?: number;
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
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

const parseSnapshot = (snapshot?: string) => {
  try {
    return JSON.stringify(JSON.parse(snapshot || '{}'), null, 2);
  } catch {
    return snapshot || '-';
  }
};

const IdTags: React.FC<{ values?: number[]; emptyText?: string }> = ({
  values,
  emptyText = '无',
}) =>
  values?.length ? (
    <Space size={[4, 6]} wrap>
      {values.map((value) => (
        <Tag key={value}>{value}</Tag>
      ))}
    </Space>
  ) : (
    <Text type="secondary">{emptyText}</Text>
  );

const MetricGovernanceDrawer: React.FC<Props> = ({ metricId, open, onClose, onChanged }) => {
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<GovernanceDetail>();
  const [lineage, setLineage] = useState<MetricLineage>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [changeSummary, setChangeSummary] = useState('更新指标治理信息');
  const [compareVersions, setCompareVersions] = useState<number[]>([]);

  const loadDetail = async () => {
    if (!metricId) return;
    setLoading(true);
    try {
      const [detailResponse, lineageResponse] = await Promise.all([
        getGovernanceDetail(metricId),
        getMetricLineage(metricId),
      ]);
      const nextDetail = responseData(detailResponse);
      setDetail(nextDetail);
      setLineage(responseData(lineageResponse));
      const governance = nextDetail?.governance || {};
      form.setFieldsValue({
        ownerDepartment: governance.ownerDepartment,
        sourceSystem: governance.sourceSystem,
        businessDefinition: governance.businessDefinition,
        effectiveRange:
          governance.effectiveFrom && governance.effectiveTo
            ? [dayjs(governance.effectiveFrom), dayjs(governance.effectiveTo)]
            : undefined,
      });
    } catch (error: any) {
      message.error(error.message || '治理详情加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) loadDetail();
    if (!open) {
      setDetail(undefined);
      setLineage(undefined);
      setCompareVersions([]);
    }
  }, [open, metricId]);

  const save = async () => {
    if (!metricId) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      responseData(
        await updateGovernance(metricId, {
          ownerDepartment: values.ownerDepartment,
          sourceSystem: values.sourceSystem,
          businessDefinition: values.businessDefinition,
          effectiveFrom: values.effectiveRange?.[0]?.format('YYYY-MM-DD'),
          effectiveTo: values.effectiveRange?.[1]?.format('YYYY-MM-DD'),
          changeSummary,
        }),
      );
      message.success('治理信息已保存');
      await loadDetail();
      onChanged();
    } catch (error: any) {
      message.error(error.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const runAction = async (action: () => Promise<any>, successText: string) => {
    try {
      responseData(await action());
      message.success(successText);
      await loadDetail();
      onChanged();
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const confirmAction = (title: string, action: () => Promise<any>, successText: string) => {
    Modal.confirm({
      title,
      content: changeSummary || '未填写变更说明',
      okText: '确认',
      cancelText: '取消',
      onOk: () => runAction(action, successText),
    });
  };

  const versions = detail?.versions || [];
  const compareItems = useMemo(
    () => versions.filter((item) => compareVersions.includes(item.versionNo)),
    [versions, compareVersions],
  );
  const governance = detail?.governance;
  const metric = detail?.metric;
  const currentStatus = governance?.governanceStatus;

  const versionColumns = [
    { title: '版本', dataIndex: 'versionNo', width: 80, render: (value: number) => `V${value}` },
    { title: '变更说明', dataIndex: 'changeSummary' },
    {
      title: '审批状态',
      dataIndex: 'approvalStatus',
      width: 110,
      render: (value: string) => <Tag>{value || '-'}</Tag>,
    },
    { title: '创建人', dataIndex: 'createdBy', width: 110 },
    { title: '创建时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_: any, record: MetricVersion) => (
        <Space size={4}>
          {record.versionNo === governance?.currentVersion && currentStatus === 'DRAFT' && (
            <Button
              type="link"
              size="small"
              onClick={() =>
                confirmAction(
                  `提交 V${record.versionNo} 审批？`,
                  () => submitMetricVersion(record.metricId, record.id, changeSummary),
                  '已提交审批',
                )
              }
            >
              提交审批
            </Button>
          )}
          {record.versionNo === governance?.currentVersion && currentStatus === 'APPROVED' && (
            <Button
              type="link"
              size="small"
              icon={<CloudUploadOutlined />}
              onClick={() =>
                confirmAction(
                  `发布 V${record.versionNo}？`,
                  () => publishMetricVersion(record.metricId, record.id, changeSummary),
                  '版本已发布',
                )
              }
            >
              发布
            </Button>
          )}
          {record.versionNo !== governance?.currentVersion && (
            <Button
              type="link"
              size="small"
              icon={<RollbackOutlined />}
              onClick={() =>
                confirmAction(
                  `回滚到 V${record.versionNo}？`,
                  () => rollbackMetricVersion(record.metricId, record.versionNo, changeSummary),
                  '已创建回滚版本',
                )
              }
            >
              回滚
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const approvalColumns = [
    { title: '动作', dataIndex: 'action', width: 100 },
    {
      title: '状态',
      dataIndex: 'approvalStatus',
      width: 110,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    { title: '提交人', dataIndex: 'createdBy', width: 110 },
    { title: '意见', dataIndex: 'commentText' },
    {
      title: '操作',
      width: 150,
      render: (_: any, record: MetricApproval) =>
        record.approvalStatus === 'PENDING' ? (
          <Space size={4}>
            <Button
              type="link"
              size="small"
              icon={<CheckOutlined />}
              onClick={() =>
                runAction(() => decideApproval(record.id, true, changeSummary), '审批通过')
              }
            >
              通过
            </Button>
            <Button
              type="link"
              danger
              size="small"
              onClick={() =>
                runAction(() => decideApproval(record.id, false, changeSummary), '已驳回')
              }
            >
              驳回
            </Button>
          </Space>
        ) : null,
    },
  ];

  const tabItems = [
    {
      key: 'basic',
      label: '治理信息',
      children: (
        <Form form={form} layout="vertical" className={styles.detailForm}>
          <div className={styles.formGrid}>
            <Form.Item label="责任部门" name="ownerDepartment" rules={[{ required: true }]}>
              <Input placeholder="例如：计划财务部" />
            </Form.Item>
            <Form.Item label="来源系统" name="sourceSystem" rules={[{ required: true }]}>
              <Input placeholder="例如：核心业务系统" />
            </Form.Item>
          </div>
          <Form.Item label="业务口径" name="businessDefinition" rules={[{ required: true }]}>
            <Input.TextArea rows={4} maxLength={1000} showCount />
          </Form.Item>
          <Form.Item label="生效区间" name="effectiveRange" rules={[{ required: true }]}>
            <RangePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="变更说明">
            <Input
              value={changeSummary}
              onChange={(event) => setChangeSummary(event.target.value)}
            />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>
              保存治理信息
            </Button>
            <Button
              icon={<HistoryOutlined />}
              disabled={!metricId}
              onClick={() =>
                runAction(
                  () => createMetricVersion(metricId as number, changeSummary),
                  '已创建新版本',
                )
              }
            >
              创建版本
            </Button>
            <Button
              danger
              icon={<StopOutlined />}
              disabled={currentStatus === 'INACTIVE'}
              onClick={() =>
                confirmAction(
                  '停用当前指标？',
                  () => deactivateMetric(metricId as number, changeSummary),
                  '指标已停用',
                )
              }
            >
              停用
            </Button>
          </Space>
        </Form>
      ),
    },
    {
      key: 'versions',
      label: `版本 (${versions.length})`,
      children: (
        <div className={styles.versionPanel}>
          <Space className={styles.compareToolbar} wrap>
            <Text>版本比较</Text>
            <Select
              mode="multiple"
              maxCount={2}
              value={compareVersions}
              placeholder="选择两个版本"
              style={{ minWidth: 260 }}
              options={versions.map((item) => ({
                label: `V${item.versionNo}`,
                value: item.versionNo,
              }))}
              onChange={setCompareVersions}
            />
          </Space>
          {compareItems.length === 2 && (
            <div className={styles.versionCompare}>
              {compareItems.map((item) => (
                <div key={item.id} className={styles.snapshotPanel}>
                  <Text strong>V{item.versionNo}</Text>
                  <pre>{parseSnapshot(item.snapshotJson)}</pre>
                </div>
              ))}
            </div>
          )}
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={versions}
            columns={versionColumns}
            scroll={{ x: 900 }}
          />
        </div>
      ),
    },
    {
      key: 'lineage',
      label: '血缘与引用',
      children: lineage ? (
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="上游指标">
            <IdTags values={lineage.upstreamMetricIds} />
          </Descriptions.Item>
          <Descriptions.Item label="下游指标">
            <IdTags values={lineage.downstreamMetricIds} />
          </Descriptions.Item>
          <Descriptions.Item label="关联维度">
            <IdTags values={lineage.relatedDimensionIds} />
          </Descriptions.Item>
          <Descriptions.Item label="引用数据集">
            <IdTags values={lineage.referencedDataSetIds} />
          </Descriptions.Item>
          <Descriptions.Item label="机构映射">
            {lineage.organizationMappings?.length || 0} 条
          </Descriptions.Item>
        </Descriptions>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ),
    },
    {
      key: 'approvals',
      label: `审批记录 (${detail?.approvals?.length || 0})`,
      children: (
        <Table
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={detail?.approvals || []}
          columns={approvalColumns}
          scroll={{ x: 720 }}
        />
      ),
    },
  ];

  return (
    <Drawer
      width="min(860px, 100vw)"
      open={open}
      loading={loading}
      onClose={onClose}
      title={
        <Space>
          <span>{metric?.name || `指标 ${metricId || ''}`}</span>
          {currentStatus && (
            <Tag color={statusMeta[currentStatus]?.color}>{statusMeta[currentStatus]?.text}</Tag>
          )}
        </Space>
      }
    >
      {detail ? (
        <>
          <Descriptions size="small" column={3} className={styles.metricSummary}>
            <Descriptions.Item label="业务名">{metric?.bizName || '-'}</Descriptions.Item>
            <Descriptions.Item label="当前版本">
              V{governance?.currentVersion || 0}
            </Descriptions.Item>
            <Descriptions.Item label="最近维护人">{governance?.updatedBy || '-'}</Descriptions.Item>
          </Descriptions>
          <Paragraph type="secondary" ellipsis={{ rows: 2, expandable: true }}>
            {metric?.description || governance?.businessDefinition || '暂无指标说明'}
          </Paragraph>
          <Tabs items={tabItems} />
        </>
      ) : null}
    </Drawer>
  );
};

export default MetricGovernanceDrawer;
