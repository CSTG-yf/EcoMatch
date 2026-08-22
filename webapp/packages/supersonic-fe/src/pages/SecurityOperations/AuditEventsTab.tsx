import { EyeOutlined, LinkOutlined, ReloadOutlined } from '@ant-design/icons';
import { ActionType, ProColumns, ProTable } from '@ant-design/pro-components';
import { Button, Descriptions, Drawer, Space, Table, Tag, Tooltip, message } from 'antd';
import dayjs from 'dayjs';
import React, { useRef, useState } from 'react';
import { getAuditEvent, getAuditEvents, getAuditTrace } from './service';
import { normalizeApiData, normalizePage } from './model';
import { AuditEvent } from './types';

const OUTCOME_COLORS: Record<string, string> = {
  SUCCESS: 'success',
  ALLOWED: 'success',
  FAILURE: 'error',
  DENIED: 'error',
  UNKNOWN: 'default',
};

const AuditEventsTab: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedEvent, setSelectedEvent] = useState<AuditEvent>();
  const [traceEvents, setTraceEvents] = useState<AuditEvent[]>([]);

  const loadEvent = async (eventId: string, withTrace = false) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setTraceEvents([]);
    try {
      const response = await getAuditEvent(eventId);
      const event = normalizeApiData<AuditEvent>(response);
      setSelectedEvent(event);
      if (withTrace && event?.traceId) {
        const trace = await getAuditTrace(event.traceId);
        setTraceEvents(normalizeApiData<AuditEvent[]>(trace) || []);
      }
    } catch {
      message.error('审计事件加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const columns: ProColumns<AuditEvent>[] = [
    {
      title: '发生时间',
      dataIndex: 'eventTime',
      valueType: 'dateTime',
      width: 180,
      search: false,
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
      title: '事件类型',
      dataIndex: 'eventType',
      width: 190,
      valueType: 'select',
      valueEnum: {
        QUERY_STARTED: '查询开始',
        QUERY_SUCCEEDED: '查询成功',
        QUERY_FAILED: '查询失败',
        AUTH_ALLOWED: '授权通过',
        AUTH_DENIED: '授权拒绝',
        POLICY_CREATED: '策略创建',
        POLICY_UPDATED: '策略更新',
        POLICY_DISABLED: '策略停用',
        POLICY_PREVIEWED: '策略预览',
        ROW_FILTER_APPLIED: '行权限应用',
        COLUMN_ACCESS_DENIED: '列权限拒绝',
        MASK_APPLIED: '应用脱敏',
        EXPORT_STARTED: '导出开始',
        EXPORT_SUCCEEDED: '导出成功',
        EXPORT_FAILED: '导出失败',
        SHARE_CREATED: '创建分享',
        SHARE_ACCESSED: '访问分享',
        SHARE_REVOKED: '撤销分享',
        AUDIT_ACCESSED: '访问审计',
        ALERT_STATUS_CHANGED: '告警处置',
        ALERT_RULE_CHANGED: '规则变更',
      },
      render: (_, row) => <Tag>{row.eventType}</Tag>,
    },
    {
      title: '结果',
      dataIndex: 'outcome',
      width: 105,
      valueType: 'select',
      valueEnum: {
        SUCCESS: '成功',
        FAILURE: '失败',
        ALLOWED: '允许',
        DENIED: '拒绝',
        UNKNOWN: '未知',
      },
      render: (_, row) => <Tag color={OUTCOME_COLORS[row.outcome] || 'default'}>{row.outcome}</Tag>,
    },
    { title: '用户', dataIndex: 'userName', width: 130 },
    {
      title: '机构',
      dataIndex: 'organizationId',
      width: 140,
      search: false,
      ellipsis: true,
    },
    { title: '资源类型', dataIndex: 'resourceType', width: 140 },
    { title: '资源 ID', dataIndex: 'resourceId', width: 140, ellipsis: true },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 100,
      search: false,
      render: (_, row) =>
        row.durationMs === undefined || row.durationMs === null ? '-' : `${row.durationMs} ms`,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 95,
      fixed: 'right',
      render: (_, row) => [
        <Tooltip title="查看事件" key="detail">
          <Button type="text" icon={<EyeOutlined />} onClick={() => loadEvent(row.eventId)} />
        </Tooltip>,
        <Tooltip title="查看完整链路" key="trace">
          <Button
            type="text"
            icon={<LinkOutlined />}
            disabled={!row.traceId}
            onClick={() => loadEvent(row.eventId, true)}
          />
        </Tooltip>,
      ],
    },
  ];

  return (
    <>
      <ProTable<AuditEvent>
        rowKey="eventId"
        actionRef={actionRef}
        columns={columns}
        scroll={{ x: 1350 }}
        request={async (params) =>
          normalizePage<AuditEvent>(
            await getAuditEvents({
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
        title="审计事件详情"
        width={720}
        open={detailOpen}
        loading={detailLoading}
        onClose={() => setDetailOpen(false)}
      >
        {selectedEvent && (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="事件 ID" span={2}>
                {selectedEvent.eventId}
              </Descriptions.Item>
              <Descriptions.Item label="事件类型">{selectedEvent.eventType}</Descriptions.Item>
              <Descriptions.Item label="结果">{selectedEvent.outcome}</Descriptions.Item>
              <Descriptions.Item label="用户">{selectedEvent.userName || '-'}</Descriptions.Item>
              <Descriptions.Item label="机构">
                {selectedEvent.organizationId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="资源类型">
                {selectedEvent.resourceType || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="资源 ID">
                {selectedEvent.resourceId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="原因码">
                {selectedEvent.reasonCode || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="策略版本">
                {String(selectedEvent.metadata?.policyVersion ?? '-')}
              </Descriptions.Item>
              <Descriptions.Item label="脱敏摘要">
                {selectedEvent.maskingSummary || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="发生时间" span={2}>
                {dayjs(selectedEvent.eventTime).format('YYYY-MM-DD HH:mm:ss')}
              </Descriptions.Item>
              {selectedEvent.sanitizedQuestion && (
                <Descriptions.Item label="脱敏问题" span={2}>
                  {selectedEvent.sanitizedQuestion}
                </Descriptions.Item>
              )}
            </Descriptions>
            {traceEvents.length > 0 && (
              <Table<AuditEvent>
                rowKey="eventId"
                size="small"
                pagination={false}
                dataSource={traceEvents}
                columns={[
                  {
                    title: '时间',
                    dataIndex: 'eventTime',
                    width: 170,
                    render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
                  },
                  { title: '事件', dataIndex: 'eventType' },
                  {
                    title: '结果',
                    dataIndex: 'outcome',
                    width: 100,
                    render: (value) => (
                      <Tag color={OUTCOME_COLORS[value] || 'default'}>{value}</Tag>
                    ),
                  },
                ]}
              />
            )}
          </Space>
        )}
      </Drawer>
    </>
  );
};

export default AuditEventsTab;
