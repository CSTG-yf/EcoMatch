import { Alert, Empty, Result, Skeleton, Tag, Typography } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import React, { useEffect, useMemo, useState } from 'react';
import {
  classifyShareAccessError,
  componentErrorFor,
  componentResultFor,
  extractShareToken,
  parseSharedDashboardComponents,
} from './model';
import { accessShare } from './service';
import { ShareAccessErrorKind, ShareAccessResponse } from './types';
import SharedDashboardComponent from './SharedDashboardComponent';
import styles from './style.less';

const errorContent: Record<ShareAccessErrorKind, { title: string; detail: string; status: any }> = {
  EXPIRED: { title: '分享已过期', detail: '此分享已超过有效期。', status: 'warning' },
  REVOKED: { title: '分享已撤销', detail: '分享创建者已撤销此链接。', status: 'warning' },
  EXHAUSTED: { title: '访问次数已耗尽', detail: '此分享已达到访问次数上限。', status: 'warning' },
  FORBIDDEN: {
    title: '分享不可用',
    detail: '链接可能已过期、被撤销、次数已耗尽，或当前身份无权访问。',
    status: '403',
  },
  FAILED: { title: '暂时无法访问', detail: '服务暂时不可用，请稍后重试。', status: 'error' },
};

export type ControlledShareAccessPageProps = {
  token?: string;
  routeBase?: string;
};

export const ControlledShareAccessPage: React.FC<ControlledShareAccessPageProps> = ({
  token,
  routeBase,
}) => {
  const resolvedToken = useMemo(
    () => token || extractShareToken(window.location.pathname, routeBase || '/webapp/share'),
    [routeBase, token],
  );
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<ShareAccessResponse>();
  const [errorKind, setErrorKind] = useState<ShareAccessErrorKind>();

  useEffect(() => {
    let active = true;
    setLoading(true);
    setData(undefined);
    setErrorKind(undefined);
    accessShare(resolvedToken)
      .then((response) => {
        if (active) {
          setData(response);
        }
      })
      .catch((error) => {
        if (active) {
          setErrorKind(classifyShareAccessError(error));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [resolvedToken]);

  if (loading) {
    return (
      <main className={styles.accessPage}>
        <section className={styles.accessSurface}>
          <Skeleton active paragraph={{ rows: 8 }} />
        </section>
      </main>
    );
  }

  if (errorKind || !data) {
    const content = errorContent[errorKind || 'FAILED'];
    return (
      <main className={styles.accessPage}>
        <section className={styles.accessSurface}>
          <Result status={content.status} title={content.title} subTitle={content.detail} />
        </section>
      </main>
    );
  }

  const components = parseSharedDashboardComponents(data.dashboard.config);
  const watermark = [data.watermarkUser, data.watermarkOrganization].filter(Boolean).join(' · ');

  return (
    <main className={styles.accessPage}>
      <section className={styles.accessSurface}>
        <header className={styles.accessHeader}>
          <div>
            <Typography.Title level={2}>{data.dashboard.name}</Typography.Title>
            {data.dashboard.description && (
              <Typography.Paragraph type="secondary">
                {data.dashboard.description}
              </Typography.Paragraph>
            )}
          </div>
          <Tag icon={<SafetyCertificateOutlined />} color="blue">
            受控分享
          </Tag>
        </header>

        {watermark && (
          <Alert
            className={styles.watermarkBanner}
            type="info"
            showIcon
            message={`水印：${watermark}`}
            description={`访问时间：${new Date(data.accessedAt).toLocaleString('zh-CN', {
              hour12: false,
            })}`}
          />
        )}

        {components.length === 0 ? (
          <Empty description="分享内容为空" />
        ) : (
          <div className={styles.sharedGrid}>
            {components.map((component, index) => (
              <SharedDashboardComponent
                key={component.id || index}
                component={component}
                data={componentResultFor(data.componentData, String(component.id || ''))}
                error={componentErrorFor(data.componentErrors, String(component.id || ''))}
              />
            ))}
          </div>
        )}
        {watermark && <div className={styles.watermarkOverlay}>{watermark}</div>}
      </section>
    </main>
  );
};

export default ControlledShareAccessPage;
