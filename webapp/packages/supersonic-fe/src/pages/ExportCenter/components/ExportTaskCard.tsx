import {
  DeleteOutlined,
  DownloadOutlined,
  ReloadOutlined,
  RetweetOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Button, Card, Descriptions, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import {
  canDownloadTask,
  canRetryTask,
  formatDateTime,
  formatFileSize,
  maskingText,
  statusLabel,
} from '../model';
import { ExportTaskItem, ExportStatus } from '../types';
import styles from '../style.less';

const STATUS_COLORS: Record<ExportStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCEEDED: 'success',
  FAILED: 'error',
  EXPIRED: 'warning',
};

interface Props {
  task: ExportTaskItem;
  loading: boolean;
  onRefresh: (taskId: string) => void;
  onDownload: (task: ExportTaskItem) => void;
  onRetry: (task: ExportTaskItem) => void;
  onDelete: (task: ExportTaskItem) => void;
}

const ExportTaskCard = ({ task, loading, onRefresh, onDownload, onRetry, onDelete }: Props) => (
  <Card className={styles.taskCard} size="small">
    <div className={styles.taskHeader}>
      <div className={styles.taskIdentity}>
        <Space wrap size={8}>
          <Tag color={STATUS_COLORS[task.status]}>{statusLabel(task.status)}</Tag>
          <Tag>{task.format}</Tag>
          <Tag>{task.resourceType === 'QUERY' ? '问数结果' : '分析看板'}</Tag>
        </Space>
        <Typography.Text strong ellipsis={{ tooltip: task.fileName || task.taskId }}>
          {task.fileName || `导出任务 ${task.taskId.slice(0, 8)}`}
        </Typography.Text>
        <Typography.Text
          type="secondary"
          copyable={{ text: task.taskId }}
          className={styles.taskId}
        >
          {task.taskId}
        </Typography.Text>
      </div>
      <Space wrap className={styles.taskActions}>
        <Tooltip title="刷新任务状态">
          <Button
            aria-label="刷新任务状态"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => onRefresh(task.taskId)}
          />
        </Tooltip>
        {canRetryTask(task) && (
          <Button icon={<RetweetOutlined />} disabled={loading} onClick={() => onRetry(task)}>
            重试
          </Button>
        )}
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          disabled={!canDownloadTask(task)}
          loading={loading}
          onClick={() => onDownload(task)}
        >
          下载
        </Button>
        <Popconfirm
          title="删除后文件不可恢复，确定删除该导出任务？"
          okText="删除"
          cancelText="取消"
          okButtonProps={{ danger: true, loading }}
          onConfirm={() => onDelete(task)}
        >
          <Button danger icon={<DeleteOutlined />} disabled={loading}>
            删除
          </Button>
        </Popconfirm>
      </Space>
    </div>
    {task.actionError && (
      <div className={styles.inlineError} role="alert">
        {task.actionError.message}
      </div>
    )}
    {task.status === 'FAILED' && (
      <div className={styles.inlineError} role="alert">
        失败代码：{task.failureCode || 'EXPORT_GENERATION_FAILED'}。请检查数据范围后重试。
      </div>
    )}
    {task.status === 'EXPIRED' && (
      <div className={styles.expiredMessage} role="status">
        文件已超过 24 小时保留期，不能继续下载。请使用原请求重试。
      </div>
    )}
    <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 4 }} className={styles.taskDetails}>
      <Descriptions.Item label="数据行数">
        {task.rowCount?.toLocaleString() ?? '-'}
      </Descriptions.Item>
      <Descriptions.Item label="文件大小">{formatFileSize(task.fileSize)}</Descriptions.Item>
      <Descriptions.Item label="创建时间">{formatDateTime(task.createdAt)}</Descriptions.Item>
      <Descriptions.Item label="过期时间">{formatDateTime(task.expiresAt)}</Descriptions.Item>
      <Descriptions.Item label="安全处理" span={2}>
        <Space size={6}>
          <SafetyCertificateOutlined />
          {task.status === 'SUCCEEDED' ? maskingText(task.maskingSummary) : '完成后展示脱敏结果'}
        </Space>
      </Descriptions.Item>
      <Descriptions.Item label="资源标识" span={2}>
        {task.resourceId || '-'}
      </Descriptions.Item>
    </Descriptions>
  </Card>
);

export default ExportTaskCard;
