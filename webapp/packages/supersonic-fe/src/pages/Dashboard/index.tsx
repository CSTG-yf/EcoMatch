import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import { DashboardOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import {
  addDashboardQueryComponent,
  createEmptyDashboardConfig,
  normalizeDashboardPage,
  parseDashboardConfig,
} from './model';
import {
  createDashboard,
  getDashboard,
  getDashboardDomains,
  getDashboards,
  updateDashboard,
} from './service';
import { Dashboard, DashboardAccessScope, DashboardQuerySource, DashboardStatus } from './types';
import DashboardEditor from './components/DashboardEditor';
import styles from './style.less';

type CreateForm = {
  domainId: number;
  targetDashboardId?: number | 'NEW';
  name: string;
  description?: string;
  accessScope: DashboardAccessScope;
  componentTitle?: string;
  refreshIntervalSeconds?: number;
};

type DomainOption = {
  label: string;
  value: number;
};

const unwrapDashboard = (response: any): Dashboard => {
  if (response?.code != null && Number(response.code) !== 200) {
    throw response;
  }
  return (response?.data || response) as Dashboard;
};

const statusLabel: Record<DashboardStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
};

const DashboardPage = () => {
  const location = useLocation();
  const routeSource = (location.state as { source?: DashboardQuerySource } | undefined)?.source;
  const [form] = Form.useForm<CreateForm>();
  const targetDashboardId = Form.useWatch('targetDashboardId', form);
  const [dashboards, setDashboards] = useState<Dashboard[]>([]);
  const [active, setActive] = useState<Dashboard>();
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<{ forbidden: boolean; message: string }>();
  const [status, setStatus] = useState<DashboardStatus>();
  const [keyword, setKeyword] = useState('');
  const [createOpen, setCreateOpen] = useState(Boolean(routeSource));
  const [creating, setCreating] = useState(false);
  const [domainOptions, setDomainOptions] = useState<DomainOption[]>([]);
  const [selectedDomainId, setSelectedDomainId] = useState<number>();

  const loadDomains = async () => {
    try {
      const response: any = await getDashboardDomains();
      const data = response?.data || response;
      const domains = Array.isArray(data) ? data : [];
      const options = domains
        .filter((item: any) => item?.id != null)
        .map((item: any) => ({
          value: Number(item.id),
          label: item.bizName || item.name || `主题域 ${item.id}`,
        }));
      setDomainOptions(options);
      setSelectedDomainId((current) => current || options[0]?.value);
    } catch {
      setDomainOptions([]);
    }
  };

  const loadList = async () => {
    if (!selectedDomainId) {
      setDashboards([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    setListError(undefined);
    try {
      const response: any = await getDashboards({
        domainId: selectedDomainId,
        status,
        pageNum: 1,
        pageSize: 100,
      });
      if (response?.code != null && Number(response.code) !== 200) {
        throw response;
      }
      setDashboards(normalizeDashboardPage(response).data);
    } catch (error: any) {
      const code = Number(error?.code ?? error?.response?.status);
      setDashboards([]);
      setListError({
        forbidden: code === 401 || code === 403,
        message:
          error?.msg ||
          error?.message ||
          (code === 403 ? '你没有查看看板的权限' : '看板加载失败，请稍后重试'),
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDomains();
  }, []);

  useEffect(() => {
    loadList();
  }, [status, selectedDomainId]);

  useEffect(() => {
    if (routeSource) {
      setCreateOpen(true);
      form.setFieldsValue({
        domainId: selectedDomainId,
        targetDashboardId: 'NEW',
        name: `${routeSource.question.slice(0, 60)}看板`,
        componentTitle: routeSource.question.slice(0, 120),
        refreshIntervalSeconds: 0,
        accessScope: 'PRIVATE',
      });
    }
  }, [routeSource, selectedDomainId]);

  const filtered = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) {
      return dashboards;
    }
    return dashboards.filter((item) =>
      [item.name, item.description, item.owner].some((value) =>
        String(value || '')
          .toLowerCase()
          .includes(normalized),
      ),
    );
  }, [dashboards, keyword]);

  const openDashboard = async (item: Dashboard) => {
    setLoading(true);
    try {
      setActive(unwrapDashboard(await getDashboard(item.id)));
    } catch (error: any) {
      message.error(error?.msg || '看板详情加载失败');
    } finally {
      setLoading(false);
    }
  };

  const submitCreate = async () => {
    const values = await form.validateFields();
    setCreating(true);
    try {
      if (routeSource && values.targetDashboardId && values.targetDashboardId !== 'NEW') {
        const targetId = Number(values.targetDashboardId);
        const target = unwrapDashboard(await getDashboard(targetId));
        const config = addDashboardQueryComponent(
          parseDashboardConfig(target.config),
          routeSource,
          values.componentTitle || routeSource.question,
          Number(values.refreshIntervalSeconds || 0),
        );
        const updated = unwrapDashboard(
          await updateDashboard(target.id, {
            version: target.version,
            name: target.name,
            description: target.description,
            accessScope: target.accessScope,
            config,
          }),
        );
        setDashboards((current) =>
          current.map((item) => (item.id === updated.id ? updated : item)),
        );
        setActive(updated);
        setCreateOpen(false);
        form.resetFields();
        history.replace('/dashboard');
        message.success('问数结果已追加到看板草稿');
        return;
      }

      const config = routeSource
        ? addDashboardQueryComponent(
            createEmptyDashboardConfig(),
            routeSource,
            values.componentTitle || routeSource.question,
            Number(values.refreshIntervalSeconds || 0),
          )
        : createEmptyDashboardConfig();
      const created = unwrapDashboard(
        await createDashboard({
          domainId: values.domainId,
          name: values.name.trim(),
          description: values.description,
          accessScope: values.accessScope,
          config,
        }),
      );
      setDashboards((current) => [created, ...current]);
      setActive(created);
      setCreateOpen(false);
      form.resetFields();
      history.replace('/dashboard');
      message.success(routeSource ? '问数结果已保存为看板草稿' : '看板草稿已创建');
    } catch (error: any) {
      if (error?.errorFields) {
        return;
      }
      message.error(error?.msg || error?.message || '创建失败');
    } finally {
      setCreating(false);
    }
  };

  if (active) {
    return (
      <DashboardEditor
        dashboard={active}
        onBack={() => {
          setActive(undefined);
          loadList();
        }}
        onUpdated={(updated) => {
          setActive(updated);
          setDashboards((current) =>
            current.map((item) => (item.id === updated.id ? updated : item)),
          );
        }}
        onCopied={(copied) => {
          setDashboards((current) => [copied, ...current]);
          setActive(copied);
        }}
      />
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.pageHeader}>
        <div>
          <Typography.Title level={2}>分析看板</Typography.Title>
          <Typography.Text type="secondary">
            把可信问数结果组织为可复用、可发布的经营分析视图
          </Typography.Text>
        </div>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={loadList}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              form.setFieldsValue({
                domainId: selectedDomainId,
                targetDashboardId: 'NEW',
                accessScope: 'PRIVATE',
              });
              setCreateOpen(true);
            }}
          >
            新建看板
          </Button>
        </Space>
      </header>

      <section className={styles.toolbar}>
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="选择主题域"
          value={selectedDomainId}
          options={domainOptions}
          onChange={setSelectedDomainId}
        />
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索名称、描述或所有者"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Select
          allowClear
          placeholder="全部状态"
          value={status}
          options={[
            { label: '草稿', value: 'DRAFT' },
            { label: '已发布', value: 'PUBLISHED' },
            { label: '已停用', value: 'DISABLED' },
          ]}
          onChange={setStatus}
        />
      </section>

      {listError ? (
        <Alert
          type={listError.forbidden ? 'warning' : 'error'}
          showIcon
          message={listError.forbidden ? '无权访问看板' : '看板加载失败'}
          description={listError.message}
          action={
            <Button size="small" onClick={loadList}>
              重试
            </Button>
          }
        />
      ) : (
        <Spin spinning={loading}>
          {filtered.length === 0 ? (
            <div className={styles.emptyState}>
              <Empty
                image={<DashboardOutlined className={styles.emptyIcon} />}
                description="还没有可见看板"
              >
                <Space wrap>
                  <Button type="primary" onClick={() => history.push('/chat')}>
                    从问数结果创建
                  </Button>
                  <Button
                    onClick={() => {
                      form.setFieldsValue({
                        domainId: selectedDomainId,
                        accessScope: 'PRIVATE',
                      });
                      setCreateOpen(true);
                    }}
                  >
                    创建空白看板
                  </Button>
                </Space>
              </Empty>
            </div>
          ) : (
            <div className={styles.cardGrid}>
              {filtered.map((item) => {
                const config = parseDashboardConfig(item.config);
                return (
                  <Card
                    key={item.id}
                    hoverable
                    className={styles.listCard}
                    onClick={() => openDashboard(item)}
                  >
                    <div className={styles.listCardTop}>
                      <DashboardOutlined />
                      <Tag color={item.status === 'PUBLISHED' ? 'green' : 'default'}>
                        {statusLabel[item.status]}
                      </Tag>
                    </div>
                    <Typography.Title level={4} ellipsis={{ rows: 2 }}>
                      {item.name}
                    </Typography.Title>
                    <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
                      {item.description || '暂无描述'}
                    </Typography.Paragraph>
                    <div className={styles.listCardMeta}>
                      <span>{config.components.length} 个组件</span>
                      <span>{item.owner || item.updatedBy || '当前用户'}</span>
                      <span>{item.updatedAt || '尚未更新'}</span>
                    </div>
                  </Card>
                );
              })}
            </div>
          )}
        </Spin>
      )}

      <Modal
        title={routeSource ? '保存问数结果到新看板' : '创建看板草稿'}
        open={createOpen}
        confirmLoading={creating}
        okText="创建并编辑"
        onOk={submitCreate}
        onCancel={() => {
          setCreateOpen(false);
          if (routeSource) {
            history.replace('/dashboard');
          }
        }}
      >
        {routeSource && (
          <Alert
            type="info"
            showIcon
            message={routeSource.question}
            description="只保存语义查询配置，不保存物理 SQL 或凭据。"
            className={styles.createSource}
          />
        )}
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            targetDashboardId: 'NEW',
            accessScope: 'PRIVATE',
            refreshIntervalSeconds: 0,
          }}
        >
          <Form.Item
            name="domainId"
            label="主题域"
            rules={[{ required: true, message: '请选择主题域' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={domainOptions.length ? '选择有权使用的主题域' : '暂无可用主题域'}
              options={domainOptions}
              notFoundContent="未加载到可用主题域"
              onChange={setSelectedDomainId}
            />
          </Form.Item>
          {routeSource && (
            <>
              <Form.Item name="targetDashboardId" label="目标看板">
                <Select
                  options={[
                    { label: '新建看板草稿', value: 'NEW' },
                    ...dashboards
                      .filter(
                        (item) => item.domainId === selectedDomainId && item.status === 'DRAFT',
                      )
                      .map((item) => ({ label: item.name, value: item.id })),
                  ]}
                />
              </Form.Item>
              <Form.Item
                name="componentTitle"
                label="组件标题"
                rules={[
                  { required: true, whitespace: true, message: '请输入组件标题' },
                  { max: 120, message: '标题最多 120 个字符' },
                ]}
              >
                <Input />
              </Form.Item>
              <Form.Item name="refreshIntervalSeconds" label="刷新策略">
                <Select
                  options={[
                    { label: '手动刷新', value: 0 },
                    { label: '每 1 分钟', value: 60 },
                    { label: '每 5 分钟', value: 300 },
                    { label: '每 15 分钟', value: 900 },
                  ]}
                />
              </Form.Item>
            </>
          )}
          {(!routeSource || targetDashboardId === 'NEW') && (
            <>
              <Form.Item
                name="name"
                label="看板名称"
                rules={[
                  { required: true, whitespace: true, message: '请输入看板名称' },
                  { max: 120, message: '名称最多 120 个字符' },
                ]}
              >
                <Input placeholder="例如：存贷款经营日报" />
              </Form.Item>
              <Form.Item name="description" label="描述">
                <Input.TextArea maxLength={1000} rows={3} placeholder="说明看板用途和口径" />
              </Form.Item>
              <Form.Item name="accessScope" label="访问范围">
                <Select
                  options={[
                    { label: '仅自己', value: 'PRIVATE' },
                    { label: '本机构', value: 'ORGANIZATION' },
                    { label: '主题域成员', value: 'DOMAIN' },
                  ]}
                />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </div>
  );
};

export default DashboardPage;
