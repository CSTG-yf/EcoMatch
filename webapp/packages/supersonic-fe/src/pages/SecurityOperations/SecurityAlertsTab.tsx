import {
  CheckCircleOutlined,
  EyeOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { ActionType, ProColumns, ProTable } from '@ant-design/pro-components';
import {
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  message,
} from 'antd';
import dayjs from 'dayjs';
import React, { useRef, useState } from 'react';
import {
  nextAlertStatuses,
  normalizeApiData,
  normalizePage,
  requiresDispositionComment,
} from './model';
import { getSecurityAlert, getSecurityAlerts, updateSecurityAlertStatus } from './service';
import { AlertDetail, AuditEvent, SecurityAlert } from './types';

const SEVERITY_COLORS: Record<string, string> = {
  LOW: 'default',
  MEDIUM: 'gold',
  HIGH: 'orange',
  CRITICAL: 'red',
};

const STATUS_COLORS: Record<string, string> = {
  NEW: 'error',
  ACKNOWLEDGED: 'processing',
  RESOLVED: 'success',
  CLOSED: 'default',
  DISMISSED: 'default',
};

type Props = {
  canWrite: boolean;
};

const SecurityAlertsTab: React.FC<Props> = ({ canWrite }) => {
  const actionRef = useRef<ActionType>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<AlertDetail>();
  const [dispositionOpen, setDispositionOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const loadDetail = async (alertId: string) => {
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      const response = await getSecurityAlert(alertId);
      setDetail(normalizeApiData<AlertDetail>(response));
    } catch {
      message.error('告警详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const openDisposition = () => {
    const next = nextAlertStatuses(detail?.alert.status);
    if (!next.length) {
      return;
    }
    form.setFieldsValue({ status: next[0], comment: '' });
    setDispositionOpen(true);
  };

  const submitDisposition = async () => {
    if (!detail?.alert) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await updateSecurityAlertStatus(detail.alert.alertId, {
        status: values.status,
        comment: values.comment,
        version: detail.alert.version,
      });
      message.success('告警状态已更新');
      setDispositionOpen(false);
      await loadDetail(detail.alert.alertId);
      actionRef.current?.reload();
    } catch {
      message.error('告警处置失败，请刷新后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ProColumns<SecurityAlert>[] = [
    {
      title: '最近发生',
      dataIndex: 'lastSeen',
      valueType: 'dateTime',
      width: 180,
      search: false,
      sorter: false,
    },
    {
      title: '时间范围',
      dataIndex: 'timeRange',
      valueType: 'dateTimeRange',
      hideInTable: true,
      transform: (value) => ({
        startTime: value?.[0] ? dayjs(value[0]).valueOf() : undefined,
        endTime: value?.[1] ? dayjs(value[1]).valueOf() : undefined,
      }),
    },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 105,
      valueType: 'select',
      valueEnum: {
        LOW: '低',
        MEDIUM: '中',
        HIGH: '高',
        CRITICAL: '严重',
      },
      render: (_, row) => (
        <Tag color={SEVERITY_COLORS[row.severity] || 'default'}>{row.severity}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 135,
      valueType: 'select',
      valueEnum: {
        NEW: '待处理',
        ACKNOWLEDGED: '已确认',
        RESOLVED: '已解决',
        CLOSED: '已关闭',
        DISMISSED: '已忽略',
      },
      render: (_, row) => <Tag color={STATUS_COLORS[row.status] || 'default'}>{row.status}</Tag>,
    },
    { title: '规则', dataIndex: 'ruleCode', width: 190 },
    { title: '标题', dataIndex: 'title', search: false, ellipsis: true },
    { title: '用户', dataIndex: 'userName', width: 130 },
    {
      title: '机构',
      dataIndex: 'organizationId',
      width: 140,
      search: false,
      ellipsis: true,
    },
    {
      title: '次数',
      dataIndex: 'occurrenceCount',
      width: 80,
      search: false,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 70,
      fixed: 'right',
      render: (_, row) => [
        <Tooltip title="查看与处置" key="detail">
          <Button type="text" icon={<EyeOutlined />} onClick={() => loadDetail(row.alertId)} />
        </Tooltip>,
      ],
    },
  ];

  return (
    <>
      <ProTable<SecurityAlert>
        rowKey="alertId"
        actionRef={actionRef}
        columns={columns}
        scroll={{ x: 1250 }}
        request={async (params) =>
          normalizePage<SecurityAlert>(
            await getSecurityAlerts({
              ...params,
              current: params.current,
              pageSize: params.pageSize,
            }),
          )
        }
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        options={false}
        toolBarRender={() => [
          <Tooltip title="刷新" key="refresh">
            <Button icon={<ReloadOutlined />} onClick={() => actionRef.current?.reload()} />
          </Tooltip>,
        ]}
      />
      <Drawer
        title="安全告警详情"
        width={760}
        open={detailOpen}
        loading={detailLoading}
        onClose={() => setDetailOpen(false)}
        extra={
          canWrite && nextAlertStatuses(detail?.alert.status).length > 0 ? (
            <Button type="primary" icon={<SafetyCertificateOutlined />} onClick={openDisposition}>
              处置告警
            </Button>
          ) : null
        }
      >
        {detail?.alert && (
          <Space direction="vertical" size={22} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="告警 ID" span={2}>
                {detail.alert.alertId}
              </Descriptions.Item>
              <Descriptions.Item label="严重度">
                <Tag color={SEVERITY_COLORS[detail.alert.severity]}>{detail.alert.severity}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLORS[detail.alert.status]}>{detail.alert.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="规则">{detail.alert.ruleCode}</Descriptions.Item>
              <Descriptions.Item label="累计次数">{detail.alert.occurrenceCount}</Descriptions.Item>
              <Descriptions.Item label="用户">{detail.alert.userName || '-'}</Descriptions.Item>
              <Descriptions.Item label="机构">
                {detail.alert.organizationId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="资源" span={2}>
                {[detail.alert.resourceType, detail.alert.resourceId].filter(Boolean).join(' / ') ||
                  '-'}
              </Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>
                {detail.alert.description || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="首次发生">
                {dayjs(detail.alert.firstSeen).format('YYYY-MM-DD HH:mm:ss')}
              </Descriptions.Item>
              <Descriptions.Item label="最近发生">
                {dayjs(detail.alert.lastSeen).format('YYYY-MM-DD HH:mm:ss')}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <h3>证据事件</h3>
              <Table<AuditEvent>
                rowKey="eventId"
                size="small"
                pagination={false}
                dataSource={detail.evidence || []}
                columns={[
                  {
                    title: '时间',
                    dataIndex: 'eventTime',
                    width: 170,
                    render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
                  },
                  { title: '事件', dataIndex: 'eventType' },
                  { title: '结果', dataIndex: 'outcome', width: 100 },
                  { title: '原因', dataIndex: 'reasonCode', width: 150 },
                ]}
              />
            </div>

            <div>
              <h3>处置记录</h3>
              {detail.actions?.length ? (
                <Timeline
                  items={detail.actions.map((action) => ({
                    dot: <CheckCircleOutlined />,
                    children: (
                      <div>
                        <strong>
                          {action.fromStatus} → {action.toStatus}
                        </strong>
                        <div>
                          {action.operatorName} ·{' '}
                          {dayjs(action.createdAt).format('YYYY-MM-DD HH:mm:ss')}
                        </div>
                        {action.comment && <div>{action.comment}</div>}
                      </div>
                    ),
                  }))}
                />
              ) : (
                <span>暂无处置记录</span>
              )}
            </div>
          </Space>
        )}
      </Drawer>

      <Modal
        title="处置安全告警"
        open={dispositionOpen}
        confirmLoading={submitting}
        onOk={submitDisposition}
        onCancel={() => setDispositionOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="status" label="目标状态" rules={[{ required: true }]}>
            <Select
              options={nextAlertStatuses(detail?.alert.status).map((status) => ({
                label: status,
                value: status,
              }))}
            />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(previous, current) => previous.status !== current.status}
          >
            {({ getFieldValue }) => {
              const target = getFieldValue('status');
              return (
                <Form.Item
                  name="comment"
                  label="处置说明"
                  rules={[
                    {
                      required: Boolean(target && requiresDispositionComment(target)),
                      message: '该状态必须填写处置说明',
                    },
                    { max: 1000, message: '处置说明不能超过 1000 个字符' },
                  ]}
                >
                  <Input.TextArea rows={4} showCount maxLength={1000} />
                </Form.Item>
              );
            }}
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default SecurityAlertsTab;
