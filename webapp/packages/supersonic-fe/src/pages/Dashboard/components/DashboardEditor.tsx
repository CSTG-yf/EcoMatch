import {
  Alert,
  Button,
  Divider,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Tag,
  Typography,
  message,
  Grid,
} from 'antd';
import {
  ArrowDownOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  ArrowUpOutlined,
  CopyOutlined,
  DeleteOutlined,
  PauseCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
  SendOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import {
  applyDashboardGlobalFilters,
  canEditDashboard,
  classifyDashboardError,
  moveComponent,
  parseDashboardConfig,
  serializeDashboardConfig,
} from '../model';
import {
  copyDashboard,
  createDashboard,
  disableDashboard,
  getDashboard,
  publishDashboard,
  refreshDashboardQuery,
  updateDashboard,
} from '../service';
import { Dashboard, DashboardAccessScope, DashboardComponent, DashboardConfig } from '../types';
import DashboardCard from './DashboardCard';
import styles from '../style.less';

type Props = {
  dashboard: Dashboard;
  onBack: () => void;
  onUpdated: (dashboard: Dashboard) => void;
  onCopied: (dashboard: Dashboard) => void;
};

const unwrapDashboard = (response: any): Dashboard => {
  if (response?.code != null && Number(response.code) !== 200) {
    throw response;
  }
  return (response?.data || response) as Dashboard;
};

const statusLabel = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
};

const DashboardEditor: React.FC<Props> = ({ dashboard, onBack, onUpdated, onCopied }) => {
  const screens = Grid.useBreakpoint();
  const mobile = !screens.md;
  const [draft, setDraft] = useState<Dashboard>(dashboard);
  const [config, setConfig] = useState<DashboardConfig>(() =>
    parseDashboardConfig(dashboard.config),
  );
  const [selectedId, setSelectedId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [saveState, setSaveState] = useState<'clean' | 'dirty' | 'saved'>('clean');
  const [conflict, setConflict] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [runtime, setRuntime] = useState<Record<string, any>>({});
  const [runtimeErrors, setRuntimeErrors] = useState<Record<string, string>>({});
  const [refreshingIds, setRefreshingIds] = useState<string[]>([]);
  const readOnly = !canEditDashboard(draft.status);

  useEffect(() => {
    setDraft(dashboard);
    setConfig(parseDashboardConfig(dashboard.config));
    setSelectedId(undefined);
    setConflict(false);
    setSaveState('clean');
  }, [dashboard.id, dashboard.version]);

  const selected = useMemo(
    () => config.components.find((item) => item.id === selectedId),
    [config.components, selectedId],
  );

  const updateConfig = (updater: (current: DashboardConfig) => DashboardConfig) => {
    if (readOnly) {
      return;
    }
    setConfig((current) => updater(current));
    setSaveState('dirty');
  };

  const updateComponent = (component: DashboardComponent) =>
    updateConfig((current) => ({
      ...current,
      components: current.components.map((item) => (item.id === component.id ? component : item)),
    }));

  const refreshComponent = async (component: DashboardComponent) => {
    setRefreshingIds((ids) => [...ids, component.id]);
    try {
      const response: any = await refreshDashboardQuery(
        applyDashboardGlobalFilters(component.query, config.globalFilters),
      );
      if (response?.code != null && Number(response.code) !== 200) {
        throw response;
      }
      setRuntime((current) => ({ ...current, [component.id]: response?.data || response }));
      setRuntimeErrors((current) => {
        const next = { ...current };
        delete next[component.id];
        return next;
      });
    } catch (error: any) {
      setRuntimeErrors((current) => ({
        ...current,
        [component.id]: error?.msg || error?.message || '请稍后重试',
      }));
    } finally {
      setRefreshingIds((ids) => ids.filter((id) => id !== component.id));
    }
  };

  const refreshAll = () => config.components.forEach(refreshComponent);

  useEffect(() => {
    if (config.components.length > 0) {
      refreshAll();
    }
  }, [draft.id]);

  useEffect(() => {
    if (config.refreshIntervalSeconds <= 0 || config.components.length === 0) {
      return undefined;
    }
    const timer = window.setInterval(
      () => config.components.forEach(refreshComponent),
      config.refreshIntervalSeconds * 1000,
    );
    return () => window.clearInterval(timer);
  }, [draft.id, config.refreshIntervalSeconds, config.components, config.globalFilters]);

  const save = async () => {
    setSaving(true);
    setConflict(false);
    try {
      const response = await updateDashboard(draft.id, {
        version: draft.version,
        name: draft.name.trim(),
        description: draft.description,
        accessScope: draft.accessScope,
        config: serializeDashboardConfig(config),
      });
      const updated = unwrapDashboard(response);
      setDraft(updated);
      setConfig(parseDashboardConfig(updated.config));
      setSaveState('saved');
      onUpdated(updated);
      message.success('看板已保存');
    } catch (error: any) {
      if (classifyDashboardError(error) === 'CONFLICT') {
        setConflict(true);
      } else {
        message.error(error?.msg || error?.message || '保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const reloadLatest = async () => {
    try {
      const latest = unwrapDashboard(await getDashboard(draft.id));
      setDraft(latest);
      setConfig(parseDashboardConfig(latest.config));
      setConflict(false);
      setSaveState('clean');
      onUpdated(latest);
    } catch (error: any) {
      message.error(error?.msg || '重新加载失败');
    }
  };

  const copyServerDashboard = async () => {
    try {
      const copied = unwrapDashboard(await copyDashboard(draft.id));
      onCopied(copied);
      message.success('已复制为新草稿');
    } catch (error: any) {
      message.error(error?.msg || '复制失败');
    }
  };

  const copyLocalDraft = async () => {
    try {
      const copied = unwrapDashboard(
        await createDashboard({
          domainId: draft.domainId,
          name: `${draft.name.trim()}（副本）`.slice(0, 120),
          description: draft.description,
          accessScope: draft.accessScope,
          config: serializeDashboardConfig(config),
        }),
      );
      onCopied(copied);
      setConflict(false);
      message.success('本地更改已复制为新草稿');
    } catch (error: any) {
      message.error(error?.msg || '复制本地草稿失败');
    }
  };

  const changeStatus = async (action: 'publish' | 'disable') => {
    try {
      const response =
        action === 'publish'
          ? await publishDashboard(draft.id, draft.version)
          : await disableDashboard(draft.id, draft.version);
      const updated = unwrapDashboard(response);
      setDraft(updated);
      onUpdated(updated);
      message.success(action === 'publish' ? '看板已发布' : '看板已停用');
    } catch (error: any) {
      if (classifyDashboardError(error) === 'CONFLICT') {
        setConflict(true);
      } else {
        message.error(error?.msg || '状态更新失败');
      }
    }
  };

  const settings = (
    <div className={styles.inspector}>
      <Typography.Title level={5}>{selected ? '组件配置' : '看板配置'}</Typography.Title>
      {selected ? (
        <>
          <Form layout="vertical">
            <Form.Item label="组件标题">
              <Input
                disabled={readOnly}
                value={selected.title}
                maxLength={120}
                onChange={(event) => updateComponent({ ...selected, title: event.target.value })}
              />
            </Form.Item>
            <Form.Item label="展示方式">
              <Select
                disabled={readOnly}
                value={selected.type}
                options={[
                  { label: '图表', value: 'chart' },
                  { label: '明细表', value: 'table' },
                  { label: '指标卡', value: 'number' },
                ]}
                onChange={(type) => updateComponent({ ...selected, type })}
              />
            </Form.Item>
            <Form.Item label="图表类型">
              <Select
                disabled={readOnly}
                value={selected.visualization.chartType}
                options={[
                  { label: '表格', value: 'table' },
                  { label: '柱状图', value: 'column' },
                  { label: '折线图', value: 'line' },
                  { label: '饼图', value: 'pie' },
                ]}
                onChange={(chartType) =>
                  updateComponent({
                    ...selected,
                    visualization: { ...selected.visualization, chartType },
                  })
                }
              />
            </Form.Item>
          </Form>
          <Typography.Text type="secondary">布局调整</Typography.Text>
          <div className={styles.layoutControls}>
            <Button
              aria-label="左移"
              disabled={readOnly}
              icon={<ArrowLeftOutlined />}
              onClick={() => updateComponent(moveComponent(selected, { x: selected.layout.x - 1 }))}
            />
            <Button
              aria-label="上移"
              disabled={readOnly}
              icon={<ArrowUpOutlined />}
              onClick={() => updateComponent(moveComponent(selected, { y: selected.layout.y - 1 }))}
            />
            <Button
              aria-label="下移"
              disabled={readOnly}
              icon={<ArrowDownOutlined />}
              onClick={() => updateComponent(moveComponent(selected, { y: selected.layout.y + 1 }))}
            />
            <Button
              aria-label="右移"
              disabled={readOnly}
              icon={<ArrowRightOutlined />}
              onClick={() => updateComponent(moveComponent(selected, { x: selected.layout.x + 1 }))}
            />
          </div>
          <Space className={styles.sizeControls}>
            <span>宽</span>
            <InputNumber
              min={2}
              max={12}
              disabled={readOnly}
              value={selected.layout.w}
              onChange={(value) =>
                updateComponent(moveComponent(selected, { w: Number(value || 2) }))
              }
            />
            <span>高</span>
            <InputNumber
              min={2}
              max={20}
              disabled={readOnly}
              value={selected.layout.h}
              onChange={(value) =>
                updateComponent(moveComponent(selected, { h: Number(value || 2) }))
              }
            />
          </Space>
          <Divider />
          <Button
            danger
            disabled={readOnly}
            icon={<DeleteOutlined />}
            onClick={() => {
              updateConfig((current) => ({
                ...current,
                components: current.components.filter((item) => item.id !== selected.id),
              }));
              setSelectedId(undefined);
            }}
          >
            删除组件
          </Button>
        </>
      ) : (
        <Form layout="vertical">
          <Form.Item label="看板名称">
            <Input
              disabled={readOnly}
              value={draft.name}
              maxLength={120}
              onChange={(event) => {
                setDraft({ ...draft, name: event.target.value });
                setSaveState('dirty');
              }}
            />
          </Form.Item>
          <Form.Item label="描述">
            <Input.TextArea
              disabled={readOnly}
              value={draft.description}
              maxLength={1000}
              rows={3}
              onChange={(event) => {
                setDraft({ ...draft, description: event.target.value });
                setSaveState('dirty');
              }}
            />
          </Form.Item>
          <Form.Item label="访问范围">
            <Select<DashboardAccessScope>
              disabled={readOnly}
              value={draft.accessScope}
              options={[
                { label: '仅自己', value: 'PRIVATE' },
                { label: '本机构', value: 'ORGANIZATION' },
                { label: '主题域成员', value: 'DOMAIN' },
              ]}
              onChange={(accessScope) => {
                setDraft({ ...draft, accessScope });
                setSaveState('dirty');
              }}
            />
          </Form.Item>
          <Form.Item label="自动刷新（秒，0 为关闭）">
            <InputNumber
              disabled={readOnly}
              min={0}
              max={86400}
              value={config.refreshIntervalSeconds}
              onChange={(value) =>
                updateConfig((current) => ({
                  ...current,
                  refreshIntervalSeconds: Number(value || 0),
                }))
              }
            />
          </Form.Item>
          <Divider>全局筛选</Divider>
          {config.globalFilters.map((filter, index) => (
            <Space key={`${filter.field}-${index}`} className={styles.filterRow}>
              <Input
                disabled={readOnly}
                aria-label={`筛选字段${index + 1}`}
                placeholder="语义字段"
                value={filter.field}
                onChange={(event) =>
                  updateConfig((current) => ({
                    ...current,
                    globalFilters: current.globalFilters.map((item, itemIndex) =>
                      itemIndex === index ? { ...item, field: event.target.value } : item,
                    ),
                  }))
                }
              />
              <Input
                disabled={readOnly}
                aria-label={`筛选值${index + 1}`}
                placeholder="值"
                value={filter.value}
                onChange={(event) =>
                  updateConfig((current) => ({
                    ...current,
                    globalFilters: current.globalFilters.map((item, itemIndex) =>
                      itemIndex === index ? { ...item, value: event.target.value } : item,
                    ),
                  }))
                }
              />
              <Button
                aria-label={`删除筛选${index + 1}`}
                icon={<DeleteOutlined />}
                disabled={readOnly}
                onClick={() =>
                  updateConfig((current) => ({
                    ...current,
                    globalFilters: current.globalFilters.filter(
                      (_, itemIndex) => itemIndex !== index,
                    ),
                  }))
                }
              />
            </Space>
          ))}
          <Button
            disabled={readOnly}
            onClick={() =>
              updateConfig((current) => ({
                ...current,
                globalFilters: [...current.globalFilters, { field: '', operator: 'EQ', value: '' }],
              }))
            }
          >
            添加筛选
          </Button>
        </Form>
      )}
    </div>
  );

  return (
    <div className={styles.editorPage}>
      <header className={styles.editorHeader}>
        <Space wrap>
          <Button onClick={onBack}>返回列表</Button>
          <Typography.Title level={4} className={styles.editorTitle}>
            {draft.name}
          </Typography.Title>
          <Tag color={draft.status === 'PUBLISHED' ? 'green' : 'default'}>
            {statusLabel[draft.status]}
          </Tag>
          <Typography.Text type="secondary">v{draft.version}</Typography.Text>
          <Typography.Text type={saveState === 'dirty' ? 'warning' : 'secondary'}>
            {saveState === 'dirty' ? '有未保存更改' : saveState === 'saved' ? '已保存' : ''}
          </Typography.Text>
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={refreshAll}>
            刷新数据
          </Button>
          <Button icon={<CopyOutlined />} onClick={copyServerDashboard}>
            复制
          </Button>
          {draft.status === 'DRAFT' ? (
            <Popconfirm
              title="确认发布当前看板？"
              description="发布前请确认至少包含一个有效组件和正确的访问范围。"
              onConfirm={() => changeStatus('publish')}
            >
              <Button
                icon={<SendOutlined />}
                disabled={
                  config.components.length === 0 || !draft.name.trim() || saveState === 'dirty'
                }
              >
                发布
              </Button>
            </Popconfirm>
          ) : draft.status === 'PUBLISHED' ? (
            <Popconfirm title="确认停用当前看板？" onConfirm={() => changeStatus('disable')}>
              <Button icon={<PauseCircleOutlined />}>停用</Button>
            </Popconfirm>
          ) : (
            <Tag>停用后只读</Tag>
          )}
          {mobile && (
            <Button icon={<SettingOutlined />} onClick={() => setSettingsOpen(true)}>
              配置
            </Button>
          )}
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={readOnly}
            onClick={save}
          >
            保存
          </Button>
        </Space>
      </header>

      {conflict && (
        <Alert
          type="warning"
          showIcon
          message="看板已被其他操作更新"
          description="本地更改仍保留。你可以重新加载服务端最新版本，或复制为新看板。"
          action={
            <Space>
              <Button size="small" onClick={reloadLatest}>
                重新加载
              </Button>
              <Button size="small" type="primary" onClick={copyLocalDraft}>
                复制为新看板
              </Button>
            </Space>
          }
        />
      )}

      <div className={styles.editorBody}>
        <aside className={styles.componentRail}>
          <Typography.Text strong>组件</Typography.Text>
          <Typography.Text type="secondary">{config.components.length}/100</Typography.Text>
          {config.components.map((component) => (
            <Button
              key={component.id}
              type={component.id === selectedId ? 'primary' : 'text'}
              block
              onClick={() => {
                setSelectedId(component.id);
                if (mobile) {
                  setSettingsOpen(true);
                }
              }}
            >
              {component.title}
            </Button>
          ))}
          <Button
            type={!selectedId ? 'primary' : 'text'}
            block
            onClick={() => setSelectedId(undefined)}
          >
            看板设置
          </Button>
        </aside>
        <main className={styles.canvasScroller}>
          <div className={styles.canvas}>
            {config.components.length === 0 ? (
              <Alert
                type="info"
                showIcon
                message="暂无组件"
                description="前往问答对话，完成一次查询后点击“保存到看板”。"
              />
            ) : (
              config.components.map((component) => (
                <DashboardCard
                  key={component.id}
                  component={component}
                  editable={!readOnly}
                  selected={component.id === selectedId}
                  loading={refreshingIds.includes(component.id)}
                  error={runtimeErrors[component.id]}
                  result={runtime[component.id]}
                  onSelect={() => {
                    setSelectedId(component.id);
                    if (mobile) {
                      setSettingsOpen(true);
                    }
                  }}
                />
              ))
            )}
          </div>
        </main>
        {!mobile && <aside className={styles.inspectorPanel}>{settings}</aside>}
      </div>

      <Drawer
        title="看板配置"
        width="min(92vw, 420px)"
        open={mobile && settingsOpen}
        onClose={() => setSettingsOpen(false)}
      >
        {settings}
      </Drawer>
    </div>
  );
};

export default DashboardEditor;
