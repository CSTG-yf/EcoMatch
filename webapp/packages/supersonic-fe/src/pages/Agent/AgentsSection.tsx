import { PlusOutlined } from '@ant-design/icons';

// 银行问数专用系统：以"银行问数"SQL 基座为主，删除入口保持收口（防止误删基座助理）；
// 新建助理按钮按 2026-08-22 用户要求恢复展示，多助理能力底层代码与数据层始终保留。
const SINGLE_AGENT_MODE = true; // 仅控制"删除"入口
const SHOW_AGENT_CREATE = true; // "新建助理"入口恢复展示
import { Button, Popconfirm, Switch, Table } from 'antd';
import moment from 'moment';
import { useEffect, useState } from 'react';
import styles from './style.less';
import { AgentType } from './type';

type Props = {
  agents: AgentType[];
  loading: boolean;
  onSelectAgent: (agent: AgentType) => void;
  onDeleteAgent: (id: number) => void;

  onSaveAgent: (agent: AgentType, noTip?: boolean) => Promise<void>;
  onCreatBtnClick?: () => void;
};

const AgentsSection: React.FC<Props> = ({
  agents,
  onSelectAgent,
  onDeleteAgent,
  onSaveAgent,
  onCreatBtnClick,
}) => {
  const [showAgents, setShowAgents] = useState<AgentType[]>([]);

  useEffect(() => {
    setShowAgents(agents);
  }, [agents]);

  const columns = [
    {
      title: '助理名称',
      dataIndex: 'name',
      key: 'name',
      render: (value: string, agent: AgentType) => {
        return (
          <a
            onClick={() => {
              onSelectAgent(agent);
            }}
          >
            {value}
          </a>
        );
      },
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: number, agent: AgentType) => {
        return (
          <div className={styles.toggleStatus}>
            {status === 0 ? '已禁用' : <span className={styles.online}>已启用</span>}
            <span
              onClick={(e) => {
                e.stopPropagation();
              }}
            >
              <Switch
                key={agent.id}
                size="small"
                defaultChecked={status === 1}
                onChange={(value) => {
                  onSaveAgent({ ...agent, status: value ? 1 : 0 }, true);
                }}
              />
            </span>
          </div>
        );
      },
    },
    {
      title: '更新人',
      dataIndex: 'updatedBy',
      key: 'updatedBy',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      render: (value: string) => {
        return moment(value).format('YYYY-MM-DD HH:mm:ss');
      },
    },
    {
      title: '操作',
      dataIndex: 'x',
      key: 'x',
      render: (_: any, agent: AgentType) => {
        return (
          <div className={styles.operateIcons}>
            <a
              onClick={() => {
                onSelectAgent(agent);
              }}
            >
              编辑
            </a>
            {!SINGLE_AGENT_MODE && (
              <Popconfirm
                title="确定删除吗？"
                onCancel={(e) => {
                  e?.stopPropagation();
                }}
                onConfirm={() => {
                  onDeleteAgent(agent.id!);
                }}
              >
                <a>删除</a>
              </Popconfirm>
            )}
          </div>
        );
      },
    },
  ];

  return (
    <div className={styles.agentsSection}>
      <div className={styles.content}>
        <div className={styles.searchBar}>
          {SHOW_AGENT_CREATE && (
            <Button
              type="primary"
              onClick={() => {
                onCreatBtnClick?.();
              }}
            >
              <PlusOutlined />
              新建助理
            </Button>
          )}
        </div>
        <Table columns={columns} dataSource={showAgents} />
      </div>
    </div>
  );
};

export default AgentsSection;
