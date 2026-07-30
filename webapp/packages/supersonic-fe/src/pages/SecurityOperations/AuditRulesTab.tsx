import { EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ActionType, ProColumns, ProTable } from '@ant-design/pro-components';
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  message,
} from 'antd';
import React, { useRef, useState } from 'react';
import { normalizeApiData } from './model';
import { createAuditRule, getAuditRules, updateAuditRule } from './service';
import { AuditRule, AuditRuleRequest } from './types';

type Props = {
  canWrite: boolean;
};

const RULE_TYPE_OPTIONS = [
  'HIGH_FREQUENCY_QUERY',
  'BULK_EXPORT',
  'REPEATED_AUTH_DENIAL',
  'OFF_HOURS_ACCESS',
  'SENSITIVE_RESOURCE_ACCESS',
].map((value) => ({ label: value, value }));

const AuditRulesTab: React.FC<Props> = ({ canWrite }) => {
  const actionRef = useRef<ActionType>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AuditRule>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<AuditRuleRequest>();

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({
      severity: 'MEDIUM',
      enabled: true,
      thresholdValue: 1,
      windowSeconds: 60,
      configJson: '{}',
      version: 0,
    } as AuditRuleRequest);
    setModalOpen(true);
  };

  const openEdit = (rule: AuditRule) => {
    setEditing(rule);
    form.setFieldsValue({
      ruleCode: rule.ruleCode,
      ruleName: rule.ruleName,
      ruleType: rule.ruleType,
      thresholdValue: rule.thresholdValue,
      windowSeconds: rule.windowSeconds,
      workHoursStart: rule.workHoursStart,
      workHoursEnd: rule.workHoursEnd,
      severity: rule.severity,
      enabled: rule.enabled,
      configJson: rule.configJson || '{}',
      version: rule.version,
    });
    setModalOpen(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        await updateAuditRule(editing.id, values);
      } else {
        await createAuditRule(values);
      }
      message.success(editing ? '规则已更新' : '规则已创建');
      setModalOpen(false);
      actionRef.current?.reload();
    } catch {
      message.error('规则保存失败，请检查版本和输入');
    } finally {
      setSaving(false);
    }
  };

  const columns: ProColumns<AuditRule>[] = [
    { title: '规则编码', dataIndex: 'ruleCode', width: 210 },
    { title: '规则名称', dataIndex: 'ruleName', width: 180 },
    { title: '类型', dataIndex: 'ruleType', width: 210 },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 100,
      render: (_, row) => (
        <Tag
          color={row.severity === 'CRITICAL' ? 'red' : row.severity === 'HIGH' ? 'orange' : 'gold'}
        >
          {row.severity}
        </Tag>
      ),
    },
    {
      title: '阈值 / 窗口',
      search: false,
      render: (_, row) =>
        `${row.thresholdValue || '-'} / ${row.windowSeconds ? `${row.windowSeconds} 秒` : '-'}`,
    },
    {
      title: '工作时间',
      search: false,
      width: 150,
      render: (_, row) =>
        row.workHoursStart && row.workHoursEnd
          ? `${row.workHoursStart} - ${row.workHoursEnd}`
          : '-',
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (_, row) => (
        <Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag>
      ),
    },
    { title: '更新人', dataIndex: 'updatedBy', width: 120, search: false },
    {
      title: '操作',
      valueType: 'option',
      width: 70,
      render: (_, row) =>
        canWrite
          ? [
              <Tooltip title="编辑规则" key="edit">
                <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(row)} />
              </Tooltip>,
            ]
          : [],
    },
  ];

  return (
    <>
      <ProTable<AuditRule>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        pagination={false}
        options={false}
        request={async () => ({
          data: normalizeApiData<AuditRule[]>(await getAuditRules()) || [],
          success: true,
        })}
        toolBarRender={() =>
          [
            <Tooltip title="刷新" key="refresh">
              <Button icon={<ReloadOutlined />} onClick={() => actionRef.current?.reload()} />
            </Tooltip>,
            canWrite ? (
              <Button key="create" type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建规则
              </Button>
            ) : null,
          ].filter(Boolean)
        }
      />

      <Modal
        title={editing ? '编辑审计规则' : '新建审计规则'}
        width={640}
        open={modalOpen}
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space align="start" size={16} style={{ width: '100%' }}>
            <Form.Item
              name="ruleCode"
              label="规则编码"
              rules={[
                { required: true },
                { pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: '使用大写字母、数字和下划线' },
              ]}
            >
              <Input disabled={Boolean(editing)} style={{ width: 280 }} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true }, { max: 128 }]}>
            <Input />
          </Form.Item>
          <Space align="start" size={16} style={{ width: '100%' }}>
            <Form.Item name="ruleType" label="规则类型" rules={[{ required: true }]}>
              <Select options={RULE_TYPE_OPTIONS} style={{ width: 280 }} />
            </Form.Item>
            <Form.Item name="severity" label="严重度" rules={[{ required: true }]}>
              <Select
                style={{ width: 160 }}
                options={['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((value) => ({
                  label: value,
                  value,
                }))}
              />
            </Form.Item>
          </Space>
          <Space align="start" size={16}>
            <Form.Item name="thresholdValue" label="触发阈值" rules={[{ required: true }]}>
              <InputNumber min={1} max={1000000} precision={0} style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="windowSeconds" label="统计窗口（秒）">
              <InputNumber min={1} max={2592000} precision={0} style={{ width: 180 }} />
            </Form.Item>
          </Space>
          <Form.Item
            noStyle
            shouldUpdate={(previous, current) => previous.ruleType !== current.ruleType}
          >
            {({ getFieldValue }) =>
              getFieldValue('ruleType') === 'OFF_HOURS_ACCESS' ? (
                <Space align="start" size={16}>
                  <Form.Item
                    name="workHoursStart"
                    label="工作开始时间"
                    rules={[{ required: true }, { pattern: /^([01]\d|2[0-3]):[0-5]\d$/ }]}
                  >
                    <Input placeholder="07:00" style={{ width: 180 }} />
                  </Form.Item>
                  <Form.Item
                    name="workHoursEnd"
                    label="工作结束时间"
                    rules={[{ required: true }, { pattern: /^([01]\d|2[0-3]):[0-5]\d$/ }]}
                  >
                    <Input placeholder="22:00" style={{ width: 180 }} />
                  </Form.Item>
                </Space>
              ) : null
            }
          </Form.Item>
          <Form.Item
            name="configJson"
            label="扩展配置 JSON"
            rules={[
              { max: 4096 },
              {
                validator: async (_, value) => {
                  if (!value) return;
                  try {
                    const parsed = JSON.parse(value);
                    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
                      throw new Error();
                    }
                  } catch {
                    throw new Error('请输入 JSON 对象');
                  }
                },
              },
            ]}
          >
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item name="version" hidden>
            <InputNumber />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default AuditRulesTab;
