import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useModel } from '@umijs/max';
import { Tabs, Tag } from 'antd';
import React from 'react';
import AuditEventsTab from './AuditEventsTab';
import AuditRulesTab from './AuditRulesTab';
import SecurityAlertsTab from './SecurityAlertsTab';
import { isSecurityWriter } from './model';
import styles from './style.less';

const SecurityOperations: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const canWrite = isSecurityWriter(currentUser);
  const organization =
    currentUser?.attributes?.organizationId ||
    currentUser?.attributes?.organizationCode ||
    currentUser?.orgName;

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <div className={styles.titleLine}>
            <SafetyCertificateOutlined />
            <h1>安全运营</h1>
          </div>
          <div className={styles.scope}>
            <span>{currentUser?.staffName || currentUser?.name || '-'}</span>
            <span>{organization || '全局范围'}</span>
          </div>
        </div>
        <Tag color={canWrite ? 'blue' : 'default'}>{canWrite ? '安全管理员' : '只读审计'}</Tag>
      </header>
      <section className={styles.content}>
        <Tabs
          destroyInactiveTabPane={false}
          items={[
            {
              key: 'events',
              label: '审计事件',
              children: <AuditEventsTab />,
            },
            {
              key: 'alerts',
              label: '安全告警',
              children: <SecurityAlertsTab canWrite={canWrite} />,
            },
            {
              key: 'rules',
              label: '异常规则',
              children: <AuditRulesTab canWrite={canWrite} />,
            },
          ]}
        />
      </section>
    </main>
  );
};

export default SecurityOperations;
