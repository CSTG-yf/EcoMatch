import { Alert, Button, Dropdown, Empty, Input, Menu, Spin, message } from 'antd';
import classNames from 'classnames';
import {
  useEffect,
  useState,
  forwardRef,
  ForwardRefRenderFunction,
  useImperativeHandle,
  useRef,
} from 'react';
import ConversationModal from '../components/ConversationModal';
import { deleteConversation, getAllConversations, saveConversation } from '../service';
import styles from './style.module.less';
import { AgentType, ConversationDetailType } from '../type';
import moment from 'moment';
import { CloseOutlined, DeleteOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import {
  findConversationAgent,
  findConversationByChatId,
  getConversationDisplayTitle,
  matchesConversationSearch,
} from '../conversationState';

type Props = {
  agentList: AgentType[];
  currentAgent?: AgentType;
  currentConversation?: ConversationDetailType;
  historyVisible?: boolean;
  closable?: boolean;
  onSelectConversation: (
    conversation: ConversationDetailType,
    agent: AgentType,
    sendMsgParams?: any,
    isAdd?: boolean
  ) => void;
  onRequestAgentSelection: () => void;
  onCloseConversation: () => void;
  onConversationDeleted: (chatId: number) => void;
  onInitializationChange: (loading: boolean, error?: string) => void;
};

const Conversation: ForwardRefRenderFunction<any, Props> = (
  {
    agentList,
    currentAgent,
    currentConversation,
    historyVisible,
    closable,
    onSelectConversation,
    onRequestAgentSelection,
    onCloseConversation,
    onConversationDeleted,
    onInitializationChange,
  },
  ref
) => {
  const [conversations, setConversations] = useState<ConversationDetailType[]>([]);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editConversation, setEditConversation] = useState<ConversationDetailType>();
  const [searchValue, setSearchValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const requestIdRef = useRef(0);

  const updateData = async () => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setLoadError('');
    try {
      const { data } = await getAllConversations();
      const conversationList = data || [];
      if (requestId === requestIdRef.current) {
        setConversations(conversationList);
      }
      return conversationList;
    } catch (error) {
      if (requestId === requestIdRef.current) {
        setLoadError('历史会话加载失败，请重试');
      }
      throw error;
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  };

  const initData = async () => {
    onInitializationChange(true);
    try {
      await updateData();
      onInitializationChange(false);
    } catch (error) {
      onInitializationChange(false, '历史会话加载失败，请重试');
    }
  };

  const createConversationForAgent = async (agent: AgentType, sendMsgParams?: any) => {
    const response = await saveConversation(agent.name, agent.id);
    const chatId = Number(response?.data ?? response);
    const data = await updateData();
    const conversation = findConversationByChatId(data, chatId);
    if (!conversation) {
      throw new Error('新会话创建成功，但未能加载该会话');
    }
    if (conversation.agentId !== agent.id) {
      throw new Error('新会话绑定的助理不一致');
    }
    return {
      ...conversation,
      initialMsgParams: sendMsgParams,
      isAdd: true,
    };
  };

  const onAddConversation = async (sendMsgParams?: any) => {
    if (!currentAgent) {
      message.warning('请先选择一个智能助理');
      onRequestAgentSelection();
      return;
    }
    try {
      const conversation = await createConversationForAgent(currentAgent, sendMsgParams);
      onSelectConversation(conversation, currentAgent, sendMsgParams, true);
    } catch (error) {
      message.error('新会话创建失败，请重试');
    }
  };

  useImperativeHandle(ref, () => ({
    updateData,
    onAddConversation,
    initData,
    createConversationForAgent,
  }));

  useEffect(() => {
    initData();
  }, []);

  const onDeleteConversation = async (id: number) => {
    try {
      await deleteConversation(id);
      onConversationDeleted(id);
      await updateData();
    } catch (error) {
      message.error('删除会话失败，请重试');
    }
  };

  const onOperate = (key: string, conversation: ConversationDetailType) => {
    if (key === 'editName') {
      setEditConversation(conversation);
      setEditModalVisible(true);
    } else if (key === 'delete') {
      onDeleteConversation(conversation.chatId);
    }
  };

  const conversationClass = classNames(styles.conversation, {
    [styles.historyVisible]: historyVisible,
  });

  const convertTime = (date: string) => {
    moment.locale('zh-cn');
    const now = moment();
    const inputDate = moment(date);
    const diffMinutes = now.diff(inputDate, 'minutes');
    if (diffMinutes < 1) {
      return '刚刚';
    } else if (inputDate.isSame(now, 'day')) {
      return inputDate.format('HH:mm');
    } else if (inputDate.isSame(now.subtract(1, 'day'), 'day')) {
      return '昨天';
    }
    return inputDate.format('MM/DD');
  };

  const onSearchValueChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchValue(e.target.value);
  };

  return (
    <div className={conversationClass}>
      <div className={styles.rightSection}>
        <div className={styles.titleBar}>
          <div className={styles.title}>历史对话</div>
          <div className={styles.rightOperation}>
            <Button
              type="text"
              size="small"
              className={styles.newConversation}
              aria-label="新对话"
              title="新对话"
              icon={<PlusOutlined />}
              onClick={() => {
                onAddConversation();
              }}
            />
            {closable && (
              <Button
                type="text"
                size="small"
                className={styles.closeIcon}
                aria-label="关闭历史会话"
                icon={<CloseOutlined />}
                onClick={onCloseConversation}
              />
            )}
          </div>
        </div>
        <div className={styles.searchConversation}>
          <Input
            placeholder="搜索"
            prefix={<SearchOutlined className={styles.searchIcon} />}
            className={styles.searchTask}
            value={searchValue}
            onChange={onSearchValueChange}
            allowClear
          />
        </div>
        <div className={styles.conversationList}>
          {loadError && (
            <Alert
              type="error"
              showIcon
              message={loadError}
              action={
                <Button size="small" onClick={initData}>
                  重试
                </Button>
              }
            />
          )}
          <Spin spinning={loading}>
            {!loading && !loadError && conversations.length === 0 && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史会话" />
            )}
            {conversations
              .filter(conversation =>
                matchesConversationSearch(conversation, agentList, searchValue)
              )
              .map(item => {
                const itemAgent = findConversationAgent(item, agentList);
                const displayTitle = getConversationDisplayTitle(item, agentList);
                const conversationItemClass = classNames(styles.conversationItem, {
                  [styles.activeConversationItem]: currentConversation?.chatId === item.chatId,
                });
                return (
                  <Dropdown
                    key={item.chatId}
                    overlay={
                      <Menu
                        items={[
                          { label: '修改对话名称', key: 'editName' },
                          { label: '删除', key: 'delete' },
                        ]}
                        onClick={({ key }) => {
                          onOperate(key, item);
                        }}
                      />
                    }
                    trigger={['contextMenu']}
                  >
                    <div
                      className={conversationItemClass}
                      onClick={() => {
                        if (!itemAgent) {
                          message.error('该会话绑定的助理当前不可用');
                          return;
                        }
                        onSelectConversation(item, itemAgent);
                      }}
                    >
                      <div className={styles.conversationContent}>
                        <div className={styles.topTitleBar}>
                          <div className={styles.conversationTitleBar}>
                            <div className={styles.conversationName}>{displayTitle}</div>
                          </div>
                          <div className={styles.conversationTime}>
                            {convertTime(item.lastTime || '')}
                          </div>
                        </div>
                        <div className={styles.bottomSection}>
                          <div className={styles.subTitle}>{item.lastQuestion}</div>
                          <DeleteOutlined
                            aria-label="删除会话"
                            className={styles.deleteIcon}
                            onClick={e => {
                              e.stopPropagation();
                              onDeleteConversation(item.chatId);
                            }}
                          />
                        </div>
                      </div>
                    </div>
                  </Dropdown>
                );
              })}
          </Spin>
        </div>
      </div>
      <ConversationModal
        visible={editModalVisible}
        editConversation={editConversation}
        onClose={() => {
          setEditModalVisible(false);
        }}
        onFinish={() => {
          setEditModalVisible(false);
          updateData();
        }}
      />
    </div>
  );
};

export default forwardRef(Conversation);
