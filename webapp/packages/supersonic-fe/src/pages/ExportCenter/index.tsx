import {
  ClockCircleOutlined,
  FileProtectOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Input, Space, Spin, Typography } from 'antd';
import { useState } from 'react';
import { history, useLocation } from '@umijs/max';
import CreateExport from './components/CreateExport';
import ExportTaskCard from './components/ExportTaskCard';
import { EXPORT_LIMITS } from './model';
import { useExportTasks } from './useExportTasks';
import styles from './style.less';
import { ExportCreateReq } from './types';

const ExportCenter = () => {
  const location = useLocation();
  const routeRequest = (location.state as { initialRequest?: ExportCreateReq } | undefined)
    ?.initialRequest;
  const [taskId, setTaskId] = useState('');
  const {
    tasks,
    pageError,
    creating,
    activeTaskIds,
    create,
    refresh,
    addByTaskId,
    download,
    retry,
    clearError,
  } = useExportTasks();

  const restoreTask = async () => {
    const task = await addByTaskId(taskId);
    if (task) setTaskId('');
  };

  const createFromRoute = async (request: ExportCreateReq) => {
    const task = await create(request);
    if (task) {
      history.replace('/exports');
    }
    return task;
  };

  return (
    <main className={styles.page}>
      <header className={styles.pageHeader}>
        <div>
          <Typography.Title level={2}>导出中心</Typography.Title>
          <Typography.Text type="secondary">
            查看由当前账号创建的受控导出任务和文件状态
          </Typography.Text>
        </div>
        <CreateExport
          initialRequest={routeRequest}
          lockedSource={Boolean(routeRequest)}
          autoOpen={Boolean(routeRequest)}
          onCreate={routeRequest ? createFromRoute : create}
        />
      </header>

      <section className={styles.securityBand} aria-label="导出安全范围">
        <div>
          <SafetyCertificateOutlined />
          <span>按当前身份重新查询并执行权限、脱敏规则</span>
        </div>
        <div>
          <FileProtectOutlined />
          <span>最大 10,000 行 / 25 MB，PDF 最大 500 行</span>
        </div>
        <div>
          <ClockCircleOutlined />
          <span>成功文件保留 {EXPORT_LIMITS.retentionHours} 小时</span>
        </div>
      </section>

      {pageError && (
        <Alert
          className={styles.pageAlert}
          type={
            pageError.kind === 'FORBIDDEN' || pageError.kind === 'UNAUTHORIZED'
              ? 'warning'
              : 'error'
          }
          showIcon
          closable
          message={pageError.message}
          description={
            pageError.kind === 'FORBIDDEN'
              ? '任务只能由创建者访问；看板导出还要求当前账号具备该看板的访问权限。'
              : undefined
          }
          onClose={clearError}
        />
      )}

      <section className={styles.taskSection}>
        <div className={styles.sectionHeader}>
          <div>
            <Typography.Title level={4}>本次会话任务</Typography.Title>
            <Typography.Text type="secondary">等待中和生成中的任务每 2 秒自动刷新</Typography.Text>
          </div>
          <Space.Compact className={styles.restoreControl}>
            <Input
              value={taskId}
              placeholder="输入已有任务 ID"
              aria-label="已有导出任务 ID"
              onChange={(event) => setTaskId(event.target.value)}
              onPressEnter={() => void restoreTask()}
            />
            <Button
              icon={<PlusOutlined />}
              disabled={!taskId.trim()}
              loading={activeTaskIds.includes(taskId.trim())}
              onClick={() => void restoreTask()}
            >
              查询
            </Button>
          </Space.Compact>
        </div>

        <Spin spinning={creating} tip="正在创建并生成导出文件">
          {tasks.length ? (
            <div className={styles.taskList}>
              {tasks.map((task) => (
                <ExportTaskCard
                  key={task.taskId}
                  task={task}
                  loading={activeTaskIds.includes(task.taskId)}
                  onRefresh={(id) => void refresh(id)}
                  onDownload={(item) => void download(item)}
                  onRetry={(item) => void retry(item)}
                />
              ))}
            </div>
          ) : (
            <div className={styles.emptyState}>
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  <span>
                    暂无导出任务
                    <br />
                    <Typography.Text type="secondary">
                      创建任务或输入已有任务 ID 查询状态
                    </Typography.Text>
                  </span>
                }
              />
            </div>
          )}
        </Spin>
      </section>
    </main>
  );
};

export { default as CreateExport } from './components/CreateExport';
export * from './integration';
export * from './model';
export * from './service';
export * from './types';
export default ExportCenter;
