import {
  Alert,
  Button,
  Empty,
  List,
  Modal,
  Pagination,
  Popconfirm,
  Skeleton,
  Space,
  Tag,
  Typography,
  message,
} from 'antd';
import { ReloadOutlined, StopOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';
import { effectiveShareStatus, normalizeSharePage, remainingAccessCount } from './model';
import { listShares, revokeShare } from './service';
import { ShareEffectiveStatus, ShareIdentityPolicy, ShareRecord } from './types';
import styles from './style.less';

const statusMeta: Record<ShareEffectiveStatus, { label: string; color: string }> = {
  ACTIVE: { label: '有效', color: 'green' },
  REVOKED: { label: '已撤销', color: 'default' },
  EXPIRED: { label: '已过期', color: 'orange' },
  EXHAUSTED: { label: '次数已耗尽', color: 'red' },
};

const policyLabel: Record<ShareIdentityPolicy, string> = {
  AUTHENTICATED: '已登录用户',
  ORGANIZATION: '同机构用户',
  USERS: '指定用户',
};

const formatTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';

export type ShareManagementDialogProps = {
  open: boolean;
  onClose: () => void;
  pageSize?: number;
};

export const ShareManagementDialog: React.FC<ShareManagementDialogProps> = ({
  open,
  onClose,
  pageSize = 10,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [items, setItems] = useState<ShareRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [revokingId, setRevokingId] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const page = normalizeSharePage(await listShares({ pageNum, pageSize }));
      setItems(page.list);
      setTotal(page.total);
    } catch (requestError: any) {
      setError(String(requestError?.msg || requestError?.message || '加载分享记录失败'));
    } finally {
      setLoading(false);
    }
  }, [pageNum, pageSize]);

  useEffect(() => {
    if (open) {
      load();
    }
  }, [load, open]);

  const revoke = async (shareId: string) => {
    setRevokingId(shareId);
    try {
      await revokeShare(shareId);
      message.success('分享已撤销');
      await load();
    } catch (requestError: any) {
      message.error(String(requestError?.msg || requestError?.message || '撤销分享失败'));
    } finally {
      setRevokingId('');
    }
  };

  return (
    <Modal
      open={open}
      title="管理受控分享"
      width={820}
      footer={null}
      onCancel={onClose}
      destroyOnClose
    >
      <div className={styles.manageToolbar}>
        <Typography.Text type="secondary">分享链接仅在创建时返回一次。</Typography.Text>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load} title="刷新分享列表" />
      </div>
      {error && <Alert type="error" showIcon message="加载失败" description={error} />}
      <Skeleton loading={loading && items.length === 0} active>
        <List
          dataSource={items}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分享" />,
          }}
          renderItem={(item) => {
            const status = effectiveShareStatus(item);
            const remaining = remainingAccessCount(item);
            return (
              <List.Item className={styles.shareItem}>
                <div className={styles.shareItemBody}>
                  <div className={styles.shareItemHeader}>
                    <Typography.Text strong ellipsis title={item.dashboardName || ''}>
                      {item.dashboardName || `看板 ${item.dashboardId}`}
                    </Typography.Text>
                    <Space wrap size={6}>
                      <Tag color={statusMeta[status].color}>{statusMeta[status].label}</Tag>
                      <Tag>{policyLabel[item.identityPolicy]}</Tag>
                    </Space>
                  </div>
                  <div className={styles.shareMetadata}>
                    <span>到期：{formatTime(item.expiresAt)}</span>
                    <span>
                      访问：{item.accessCount}
                      {item.maxAccessCount == null ? ' / 不限' : ` / ${item.maxAccessCount}`}
                    </span>
                    {remaining != null && <span>剩余：{remaining}</span>}
                    <span>水印：{item.watermarkEnabled ? '开启' : '关闭'}</span>
                  </div>
                  {item.identityPolicy === 'USERS' && item.allowedUsers?.length > 0 && (
                    <Typography.Text type="secondary" ellipsis>
                      用户：{item.allowedUsers.join('、')}
                    </Typography.Text>
                  )}
                </div>
                <Popconfirm
                  title="撤销此分享？"
                  description="撤销后链接立即失效，且不可恢复。"
                  okText="撤销"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  disabled={status === 'REVOKED'}
                  onConfirm={() => revoke(item.shareId)}
                >
                  <Button
                    danger
                    type="text"
                    icon={<StopOutlined />}
                    disabled={status === 'REVOKED'}
                    loading={revokingId === item.shareId}
                  >
                    撤销
                  </Button>
                </Popconfirm>
              </List.Item>
            );
          }}
        />
      </Skeleton>
      {total > pageSize && (
        <Pagination
          current={pageNum}
          pageSize={pageSize}
          total={total}
          showSizeChanger={false}
          onChange={setPageNum}
          className={styles.pagination}
        />
      )}
    </Modal>
  );
};

export default ShareManagementDialog;
