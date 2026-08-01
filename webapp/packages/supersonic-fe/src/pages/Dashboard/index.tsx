import {
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  PoweroffOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useState } from 'react';
import type { DashboardQueryDraft } from 'supersonic-chat-sdk';
import { getDomainList } from '../SemanticModel/service';
import DashboardWorkspace from './DashboardWorkspace';
import {
  DASHBOARD_DRAFT_STORAGE_KEY,
  createComponentFromDraft,
  emptyDashboardConfig,
} from './model';
import {
  copyDashboard,
  createDashboard,
  deleteDashboard,
  disableDashboard,
  listDashboards,
  publishDashboard,
} from './service';
import type { Dashboard, DashboardAccessScope, DashboardStatus } from './types';
import styles from './style.less';

type Domain = { id: number; name: string; bizName?: string };

const unwrap = <T,>(response: Result<T>): T => {
  if (Number(response?.code) !== 200) {
    throw new Error(response?.msg || '请求失败');
  }
  return response.data;
};

const statusLabels: Record<DashboardStatus, { text: string; color: string }> = {
  DRAFT: { text: '草稿', color: 'default' },
  PUBLISHED: { text: '已发布', color: 'success' },
  DISABLED: { text: '已停用', color: 'warning' },
};

const accessLabels: Record<DashboardAccessScope, string> = {
  PRIVATE: '仅自己',
  ORGANIZATION: '本机构',
  DOMAIN: '主题域',
};

const DashboardList: React.FC = () => {
  const [domains, setDomains] = useState<Domain[]>([]);
  const [domainId, setDomainId] = useState<number>();
  const [status, setStatus] = useState<DashboardStatus>();
  const [rows, setRows] = useState<Dashboard[]>([]);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [sourceDraft, setSourceDraft] = useState<DashboardQueryDraft>();
  const [form] = Form.useForm();

  const loadDomains = async () => {
    try {
      const data = unwrap<Domain[]>(await getDomainList());
      setDomains(data || []);
      setDomainId((current) => current || data?.[0]?.id);
    } catch (error: any) {
      message.error(error?.message || '主题域加载失败');
    }
  };

  const loadList = useCallback(async () => {
    if (!domainId) {
      setRows([]);
      return;
    }
    setLoading(true);
    try {
      const page = unwrap(await listDashboards({ domainId, status, pageNum, pageSize }));
      setRows(page?.list || []);
      setTotal(page?.total || 0);
    } catch (error: any) {
      message.error(error?.message || '看板列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [domainId, status, pageNum, pageSize]);

  useEffect(() => {
    loadDomains();
    const raw = sessionStorage.getItem(DASHBOARD_DRAFT_STORAGE_KEY);
    if (raw) {
      try {
        const draft = JSON.parse(raw) as DashboardQueryDraft;
        setSourceDraft(draft);
        setCreateOpen(true);
        form.setFieldsValue({
          domainId: draft.domainId,
          name: draft.title,
          description: draft.question,
          accessScope: 'PRIVATE',
        });
      } catch {
        sessionStorage.removeItem(DASHBOARD_DRAFT_STORAGE_KEY);
      }
    }
  }, []);

  useEffect(() => {
    if (sourceDraft?.domainId && domains.some((item) => item.id === sourceDraft.domainId)) {
      form.setFieldValue('domainId', sourceDraft.domainId);
    } else if (domains.length && !form.getFieldValue('domainId')) {
      form.setFieldValue('domainId', domains[0].id);
    }
  }, [domains, sourceDraft]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const create = async () => {
    const values = await form.validateFields();
    const config = emptyDashboardConfig();
    if (sourceDraft) {
      config.components = [createComponentFromDraft(sourceDraft)];
    }
    try {
      const dashboard = unwrap(
        await createDashboard({
          ...values,
          config: JSON.stringify(config),
        }),
      );
      sessionStorage.removeItem(DASHBOARD_DRAFT_STORAGE_KEY);
      setCreateOpen(false);
      setSourceDraft(undefined);
      form.resetFields();
      history.push(`/dashboard/${dashboard.id}/edit`);
    } catch (error: any) {
      message.error(error?.message || '看板创建失败');
    }
  };

  const mutate = async (action: () => Promise<Result<any>>, success: string) => {
    try {
      unwrap(await action());
      message.success(success);
      await loadList();
    } catch (error: any) {
      message.error(error?.message || '操作失败');
      await loadList();
    }
  };

  const columns = [
    {
      title: '看板',
      dataIndex: 'name',
      key: 'name',
      render: (value: string, row: Dashboard) => (
        <button
          className={styles.nameButton}
          onClick={() => history.push(`/dashboard/${row.id}/view`)}
        >
          <strong>{value}</strong>
          {row.description && <span>{row.description}</span>}
        </button>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: DashboardStatus) => (
        <Tag color={statusLabels[value].color}>{statusLabels[value].text}</Tag>
      ),
    },
    {
      title: '访问范围',
      dataIndex: 'accessScope',
      width: 110,
      render: (value: DashboardAccessScope) => accessLabels[value] || value,
    },
    { title: '所有者', dataIndex: 'owner', width: 130, ellipsis: true },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 230,
      render: (_: unknown, row: Dashboard) => (
        <Space size={2}>
          <Tooltip title="查看">
            <Button
              type="text"
              icon={<EyeOutlined />}
              onClick={() => history.push(`/dashboard/${row.id}/view`)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              type="text"
              icon={<EditOutlined />}
              onClick={() => history.push(`/dashboard/${row.id}/edit`)}
            />
          </Tooltip>
          <Tooltip title="复制">
            <Button
              type="text"
              icon={<CopyOutlined />}
              onClick={() => mutate(() => copyDashboard(row.id), '看板已复制')}
            />
          </Tooltip>
          {row.status === 'DRAFT' && (
            <Tooltip title="发布">
              <Button
                type="text"
                icon={<SendOutlined />}
                onClick={() => mutate(() => publishDashboard(row.id, row.version), '看板已发布')}
              />
            </Tooltip>
          )}
          {row.status === 'PUBLISHED' && (
            <Popconfirm
              title="停用该看板？"
              onConfirm={() => mutate(() => disableDashboard(row.id, row.version), '看板已停用')}
            >
              <Tooltip title="停用">
                <Button type="text" icon={<PoweroffOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
          <Popconfirm
            title="删除该看板？"
            onConfirm={() => mutate(() => deleteDashboard(row.id), '看板已删除')}
          >
            <Tooltip title="删除">
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <h1>分析看板</h1>
          <span>{total} 个看板</span>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setSourceDraft(undefined);
            form.resetFields();
            form.setFieldsValue({ domainId, accessScope: 'PRIVATE' });
            setCreateOpen(true);
          }}
        >
          新建看板
        </Button>
      </div>
      <div className={styles.toolbar}>
        <Select
          value={domainId}
          placeholder="选择主题域"
          style={{ width: 240 }}
          showSearch
          optionFilterProp="label"
          options={domains.map((item) => ({ label: item.name || item.bizName, value: item.id }))}
          onChange={(value) => {
            setDomainId(value);
            setPageNum(1);
          }}
        />
        <Select
          allowClear
          value={status}
          placeholder="全部状态"
          style={{ width: 140 }}
          options={Object.entries(statusLabels).map(([value, item]) => ({
            value,
            label: item.text,
          }))}
          onChange={(value) => {
            setStatus(value);
            setPageNum(1);
          }}
        />
      </div>
      <div className={styles.tableBand}>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          loading={loading}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无看板" />,
          }}
          pagination={{
            current: pageNum,
            pageSize,
            total,
            showSizeChanger: true,
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
            },
          }}
        />
      </div>

      <Modal
        title={sourceDraft ? '保存问数结果' : '新建看板'}
        open={createOpen}
        okText="创建并编辑"
        onOk={create}
        onCancel={() => {
          setCreateOpen(false);
          setSourceDraft(undefined);
          sessionStorage.removeItem(DASHBOARD_DRAFT_STORAGE_KEY);
          form.resetFields();
        }}
      >
        <Form form={form} layout="vertical" initialValues={{ accessScope: 'PRIVATE' }}>
          <Form.Item name="domainId" label="主题域" rules={[{ required: true }]}>
            <Select
              options={domains.map((item) => ({
                label: item.name || item.bizName,
                value: item.id,
              }))}
            />
          </Form.Item>
          <Form.Item name="name" label="看板名称" rules={[{ required: true, max: 120 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 1000 }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="accessScope" label="访问范围" rules={[{ required: true }]}>
            <Select
              options={Object.entries(accessLabels).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

const DashboardPage: React.FC = () => {
  const location = useLocation();
  if (/\/dashboard\/\d+\/edit$/.test(location.pathname)) {
    return <DashboardWorkspace editable />;
  }
  if (/\/dashboard\/\d+\/view$/.test(location.pathname)) {
    return <DashboardWorkspace editable={false} />;
  }
  return <DashboardList />;
};

export default DashboardPage;
