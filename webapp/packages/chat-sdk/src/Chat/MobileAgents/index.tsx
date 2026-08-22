import { Drawer } from 'antd';
import classNames from 'classnames';
import AssistantAvatar from '../../components/AssistantAvatar';
import { AgentType } from '../type';
import styles from './style.module.less';

type Props = {
  open: boolean;
  agentList: AgentType[];
  currentAgent?: AgentType;
  onSelectAgent: (agent: AgentType) => void;
  onClose: () => void;
};

const MobileAgents: React.FC<Props> = ({
  open,
  agentList,
  currentAgent,
  onSelectAgent,
  onClose,
}) => {
  return (
    <Drawer
      title="智能助理"
      placement="bottom"
      open={open}
      height="85%"
      className={styles.mobileAgents}
      onClose={onClose}
    >
      <div className={styles.agentListContent}>
        {agentList.map((agent) => {
          const agentItemClass = classNames(styles.agentItem, {
            [styles.active]: currentAgent?.id === agent.id,
          });
          return (
            <div
              key={agent.id}
              className={agentItemClass}
              onClick={() => {
                onSelectAgent(agent);
                onClose();
              }}
            >
              <div className={styles.agentTitleBar}>
                <AssistantAvatar size={32} className={styles.avatar} />
                <div className={styles.agentName}>{agent.name}</div>
              </div>
              <div className={styles.agentDesc}>{agent.description}</div>
            </div>
          );
        })}
      </div>
    </Drawer>
  );
};

export default MobileAgents;
