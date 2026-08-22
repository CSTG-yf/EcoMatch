import { Alert, Button, Dropdown, Empty, Input, Menu, Spin, message } from 'antd';
import classNames from 'classnames';
import {
  useEffect,
  useState,
  forwardRef,
  ForwardRefRenderFunction,
  useImperativeHandle,
  memo,
  useRef,
} from 'react';
import ConversationModal from '../components/ConversationModal';
import { deleteConversation, getAllConversations, saveConversation } from '../service';
import styles from './style.module.less';
import { AgentType, ConversationDetailType } from '../type';
import { DEFAULT_CONVERSATION_NAME } from '../constants';
import moment from 'moment';
import { DeleteOutlined, SearchOutlined } from '@ant-design/icons';
import { selectInitialConversation } from '../conversationState';

type Props = {
  currentAgent?: AgentType;
  currentConversation?: ConversationDetailType;
  historyVisible?: boolean;
  onSelectConversation: (
    conversation: ConversationDetailType,
    sendMsgParams?: any,
    isAdd?: boolean
  ) => void;
  onCloseConversation: () => void;
  onInitializationChange: (loading: boolean, error?: string) => void;
};

const Conversation: ForwardRefRenderFunction<any, Props> = (
  {
    currentAgent,
    currentConversation,
    historyVisible,
    onSelectConversation,
    onCloseConversation,
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

  useImperativeHandle(ref, () => ({
    updateData,
    onAddConversation,
    initData,
  }));

  const updateData = async (agentId?: number) => {
    const resolvedAgentId = agentId || currentAgent?.id;
    if (!resolvedAgentId) {
      setConversations([]);
      return [];
    }
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setLoadError('');
    try {
      const { data } = await getAllConversations(resolvedAgentId);
      const conversationList = (data || []).slice(0, 200);
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
      const data = await updateData();
      if (data.length > 0) {
        const conversation = selectInitialConversation(
          data,
          localStorage.getItem('CONVERSATION_ID')
        );
        if (conversation) {
          onSelectConversation(conversation);
        }
      } else {
        await onAddConversation();
      }
    } catch (error) {
      onInitializationChange(false, '会话初始化失败，请重试');
      return;
    }
    onInitializationChange(false);
  };

  useEffect(() => {
    if (currentAgent) {
      setConversations([]);
      setSearchValue('');
      if (currentAgent.initialSendMsgParams) {
        onAddConversation(currentAgent.initialSendMsgParams);
      } else {
        initData();
      }
    }
  }, [currentAgent]);

  const addConversation = async (sendMsgParams?: any) => {
    const agentId = sendMsgParams?.agentId || currentAgent?.id;
    if (!agentId) {
      message.warning('请先选择一个智能助理');
      return [];
    }
    await saveConversation(DEFAULT_CONVERSATION_NAME, agentId);
    return updateData(agentId);
  };

  const onDeleteConversation = async (id: number) => {
    try {
      await deleteConversation(id);
      await initData();
    } catch (error) {
      message.error('删除会话失败，请重试');
    }
  };

  const onAddConversation = async (sendMsgParams?: any) => {
    const data = await addConversation(sendMsgParams);
    if (data.length > 0) {
      onSelectConversation(data[0], sendMsgParams, true);
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
            <div
              className={styles.newConversation}
              onClick={() => {
                onAddConversation();
              }}
            >
              新对话
            </div>
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
              .filter(
                conversation =>
                  searchValue === '' ||
                  conversation.chatName.toLowerCase().includes(searchValue.toLowerCase())
              )
              .map(item => {
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
                        onSelectConversation(item);
                      }}
                    >
                      <div className={styles.conversationContent}>
                        <div className={styles.topTitleBar}>
                          <div className={styles.conversationTitleBar}>
                            <div className={styles.conversationName}>{item.chatName}</div>
                            {currentConversation?.chatId === item.chatId && (
                              <div className={styles.currentConversation}>当前对话</div>
                            )}
                          </div>
                          <div className={styles.conversationTime}>
                            {convertTime(item.lastTime || '')}
                          </div>
                        </div>
                        <div className={styles.bottomSection}>
                          <div className={styles.subTitle}>{item.lastQuestion}</div>
                          <DeleteOutlined
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

function areEqual(prevProps: Props, nextProps: Props) {
  if (
    prevProps.currentAgent?.id === nextProps.currentAgent?.id &&
    prevProps.currentConversation?.chatId === nextProps.currentConversation?.chatId &&
    prevProps.historyVisible === nextProps.historyVisible &&
    prevProps.onInitializationChange === nextProps.onInitializationChange
  ) {
    return true;
  }
  return false;
}

export default memo(forwardRef(Conversation), areEqual);
