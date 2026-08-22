import { updateMessageContainerScroll, isMobile, uuid, setToken } from '../utils/utils';
import {
  ForwardRefRenderFunction,
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import MessageContainer from './MessageContainer';
import styles from './style.module.less';
import { ConversationDetailType, MessageItem, MessageTypeEnum, AgentType } from './type';
import { queryAgentList } from './service';
import { useThrottleFn } from 'ahooks';
import Conversation from './Conversation';
import ChatFooter from './ChatFooter';
import classNames from 'classnames';
import { cloneDeep, isBoolean } from 'lodash';
import MobileAgents from './MobileAgents';
import { HistoryMsgItemType, MsgDataType, SendMsgParamsType } from '../common/type';
import { getHistoryMsg } from '../service';
import ShowCase from '../ShowCase';
import { jsonParse } from '../utils/utils';
import { Alert, Button, ConfigProvider, Drawer, Modal, Spin } from 'antd';
import { buildContinuationDraft, mergeHistoryMessages } from './conversationState';
import locale from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import { DashboardQuerySource } from '../common/type';

dayjs.locale('zh-cn');

type Props = {
  token?: string;
  agentIds?: number[];
  initialAgentId?: number;
  defaultAgentName?: string;
  chatVisible?: boolean;
  noInput?: boolean;
  isDeveloper?: boolean;
  integrateSystem?: string;
  isCopilot?: boolean;
  onCurrentAgentChange?: (agent?: AgentType) => void;
  onReportMsgEvent?: (msg: string, valid: boolean) => void;
  onSaveToDashboard?: (source: DashboardQuerySource) => void;
  onExportQuery?: (source: DashboardQuerySource) => void;
};

const Chat: ForwardRefRenderFunction<any, Props> = (
  {
    token,
    agentIds,
    initialAgentId,
    defaultAgentName,
    chatVisible,
    noInput,
    isDeveloper,
    integrateSystem,
    isCopilot,
    onCurrentAgentChange,
    onReportMsgEvent,
    onSaveToDashboard,
    onExportQuery,
  },
  ref
) => {
  const [messageList, setMessageList] = useState<MessageItem[]>([]);
  const [inputMsg, setInputMsg] = useState('');
  const [pageNo, setPageNo] = useState(1);
  const [hasNextPage, setHasNextPage] = useState(false);
  const [historyInited, setHistoryInited] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [historyFailedPage, setHistoryFailedPage] = useState(1);
  const [currentConversation, setCurrentConversation] = useState<ConversationDetailType>();
  const [conversationInitializing, setConversationInitializing] = useState(false);
  const [conversationError, setConversationError] = useState('');
  const [mobileHistoryVisible, setMobileHistoryVisible] = useState(false);
  // 桌面端固定展示；移动端由底部入口打开全屏历史面板。
  const historyVisible = !isMobile || mobileHistoryVisible;
  const [agentList, setAgentList] = useState<AgentType[]>([]);
  const [currentAgent, setCurrentAgent] = useState<AgentType>();
  const [mobileAgentsVisible, setMobileAgentsVisible] = useState(false);
  const [showCaseVisible, setShowCaseVisible] = useState(false);

  const [isSimpleMode, setIsSimpleMode] = useState<boolean>(false);
  const [isDebugMode, setIsDebugMode] = useState<boolean>(true);

  const conversationRef = useRef<any>();
  const chatFooterRef = useRef<any>();
  const historyRequestRef = useRef(0);

  useImperativeHandle(ref, () => ({
    sendCopilotMsg,
  }));

  const sendCopilotMsg = (params: SendMsgParamsType) => {
    const { agentId, msg, modelId } = params;
    if (currentAgent?.id !== agentId) {
      setMessageList([]);
      const agent = agentList.find(item => item.id === agentId) || ({} as AgentType);
      updateCurrentAgent({ ...agent, initialSendMsgParams: params });
    } else {
      onSendMsg(msg, messageList, modelId, params);
    }
  };

  const updateAgentConfigMode = (agent: AgentType) => {
    const toolConfig = jsonParse(agent?.toolConfig, {});
    const { simpleMode, debugMode } = toolConfig;
    if (isBoolean(simpleMode)) {
      setIsSimpleMode(simpleMode);
    } else {
      setIsSimpleMode(false);
    }
    if (isBoolean(debugMode)) {
      setIsDebugMode(debugMode);
    } else {
      setIsDebugMode(true);
    }
  };

  const updateCurrentAgent = (agent?: AgentType) => {
    historyRequestRef.current += 1;
    setCurrentConversation(undefined);
    setMessageList([]);
    setConversationError('');
    setCurrentAgent(agent);
    onCurrentAgentChange?.(agent);
    if (agent) {
      updateAgentConfigMode(agent);
    }
    if (!isCopilot) {
      // Copilot 浮窗是独立问数面，不得污染主对话页持久化的 agent 选择，
      // 否则主对话页会被浮窗的默认选择（列表第一位）抢走。
      localStorage.setItem('AGENT_ID', `${agent?.id}`);
      window.history.replaceState({}, '', `${window.location.pathname}?agentId=${agent?.id}`);
    }
  };

  const initAgentList = async () => {
    const res = await queryAgentList();
    const agentListValue = (res.data || []).filter(
      item => item.status === 1 && (agentIds === undefined || agentIds.includes(item.id))
    );
    setAgentList(agentListValue);
    if (agentListValue.length > 0) {
      const explicitAgent = initialAgentId
        ? agentListValue.find(item => item.id === initialAgentId)
        : undefined;
      const preferredAgent = defaultAgentName
        ? agentListValue.find(item => item.name === defaultAgentName)
        : undefined;
      const persistedAgentId = localStorage.getItem('AGENT_ID');
      const persistedAgent = persistedAgentId
        ? agentListValue.find(item => item.id === +persistedAgentId)
        : undefined;

      // 显式深链仍可用于兼容旧功能；普通入口始终优先进入银行问数基座，
      // 旧版本遗留的 AGENT_ID 不得把用户带回闲聊或样例助理。
      updateCurrentAgent(explicitAgent || preferredAgent || persistedAgent || agentListValue[0]);
    }
  };

  useEffect(() => {
    initAgentList();
  }, []);

  useEffect(() => {
    if (token) {
      setToken(token);
    }
  }, [token]);

  useEffect(() => {
    if (chatVisible) {
      inputFocus();
      updateMessageContainerScroll();
    }
  }, [chatVisible]);

  useEffect(() => {
    if (!currentConversation) {
      return;
    }
    historyRequestRef.current += 1;
    setMessageList([]);
    setHistoryLoading(false);
    setHistoryError('');
    setHistoryInited(false);
    setHasNextPage(false);
    setPageNo(1);
    const { initialMsgParams, isAdd } = currentConversation;
    if (isAdd) {
      inputFocus();
      if (initialMsgParams) {
        onSendMsg(initialMsgParams.msg, [], initialMsgParams.modelId, initialMsgParams);
        return;
      }
      sendHelloRsp();
      return;
    }
    updateHistoryMsg(1);
  }, [currentConversation]);

  useEffect(() => {
    if (historyInited) {
      const messageContainerEle = document.getElementById('messageContainer');
      messageContainerEle?.addEventListener('scroll', handleScroll);
    }
    return () => {
      const messageContainerEle = document.getElementById('messageContainer');
      messageContainerEle?.removeEventListener('scroll', handleScroll);
    };
  }, [historyInited]);

  const sendHelloRsp = (agent?: AgentType) => {
    if (noInput) {
      return;
    }
    setMessageList([
      {
        id: uuid(),
        type: MessageTypeEnum.AGENT_LIST,
        msg: agent?.name || currentAgent?.name || agentList?.[0]?.name,
      },
    ]);
  };

  const convertHistoryMsg = (list: HistoryMsgItemType[]) => {
    return list.map((item: HistoryMsgItemType) => ({
      id: item.questionId,
      questionId: item.questionId,
      type: MessageTypeEnum.QUESTION,
      msg: item.queryText,
      parseInfos: item.parseInfos,
      parseTimeCost: item.parseTimeCost,
      msgData: { ...(item.queryResult || {}), similarQueries: item.similarQueries },
      score: item.score,
      agentId: currentAgent?.id,
    }));
  };

  const updateHistoryMsg = async (page: number) => {
    if (!currentConversation) {
      return false;
    }
    const requestId = ++historyRequestRef.current;
    const chatId = currentConversation.chatId;
    const firstVisibleMessageId = page > 1 ? messageList[0]?.id : undefined;
    setHistoryLoading(true);
    setHistoryError('');
    try {
      const res = await getHistoryMsg(page, chatId, 3);
      if (requestId !== historyRequestRef.current) {
        return false;
      }
      const { hasNextPage: nextPageAvailable, list } = res?.data || {
        hasNextPage: false,
        list: [],
      };
      const historyMessages = convertHistoryMsg(list);
      setMessageList(current => mergeHistoryMessages(current, historyMessages, page));
      setHasNextPage(nextPageAvailable);
      if (page === 1) {
        if (list.length === 0) {
          sendHelloRsp();
        }
        updateMessageContainerScroll();
        setHistoryInited(true);
        inputFocus();
      } else if (firstVisibleMessageId !== undefined) {
        setTimeout(() => {
          document.getElementById(`${firstVisibleMessageId}`)?.scrollIntoView();
        });
      }
      return true;
    } catch (error) {
      if (requestId === historyRequestRef.current) {
        setHistoryFailedPage(page);
        setHistoryError(page === 1 ? '历史消息加载失败，请重试' : '更早的消息加载失败，请重试');
      }
      return false;
    } finally {
      if (requestId === historyRequestRef.current) {
        setHistoryLoading(false);
      }
    }
  };

  const { run: handleScroll } = useThrottleFn(
    e => {
      if (e.target.scrollTop === 0 && hasNextPage && !historyLoading) {
        const nextPage = pageNo + 1;
        updateHistoryMsg(nextPage).then(success => {
          if (success) {
            setPageNo(nextPage);
          }
        });
      }
    },
    {
      leading: true,
      trailing: true,
      wait: 200,
    }
  );

  const inputFocus = () => {
    if (!isMobile) {
      chatFooterRef.current?.inputFocus();
    }
  };

  const inputBlur = () => {
    chatFooterRef.current?.inputBlur();
  };

  const onSendMsg = async (
    msg?: string,
    list?: MessageItem[],
    modelId?: number,
    sendMsgParams?: SendMsgParamsType
  ) => {
    const currentMsg = msg || inputMsg;
    if (currentMsg.trim() === '') {
      setInputMsg('');
      return;
    }

    const msgAgent = agentList.find(item => currentMsg.indexOf(item.name) === 1);
    const certainAgent = currentMsg[0] === '/' && msgAgent;
    const agentIdValue = certainAgent ? msgAgent.id : undefined;
    const agent = agentList.find(item => item.id === sendMsgParams?.agentId);

    if (agent || certainAgent) {
      updateCurrentAgent(agent || msgAgent);
    }
    const msgs = [
      ...(list || messageList),
      {
        id: uuid(),
        msg: currentMsg,
        msgValue: certainAgent
          ? currentMsg.replace(`/${certainAgent.name}`, '').trim()
          : currentMsg,
        modelId: modelId === -1 ? undefined : modelId,
        agentId: agent?.id || agentIdValue || currentAgent?.id,
        type: MessageTypeEnum.QUESTION,
        filters: sendMsgParams?.filters,
      },
    ];
    setMessageList(msgs);
    updateMessageContainerScroll();
    setInputMsg('');
  };

  const onInputMsgChange = (value: string) => {
    const inputMsgValue = value || '';
    setInputMsg(inputMsgValue);
  };

  const saveConversationToLocal = (conversation: ConversationDetailType) => {
    if (conversation) {
      if (conversation.chatId !== -1) {
        localStorage.setItem('CONVERSATION_ID', `${conversation.chatId}`);
      }
    } else {
      localStorage.removeItem('CONVERSATION_ID');
    }
  };

  const onSelectConversation = (
    conversation: ConversationDetailType,
    sendMsgParams?: SendMsgParamsType,
    isAdd?: boolean
  ) => {
    setCurrentConversation({
      ...conversation,
      initialMsgParams: sendMsgParams,
      isAdd,
    });
    saveConversationToLocal(conversation);
    if (isMobile) {
      setMobileHistoryVisible(false);
    }
  };

  const onMsgDataLoaded = (
    data: MsgDataType,
    questionId: string | number,
    question: string,
    valid: boolean,
    isRefresh?: boolean
  ) => {
    onReportMsgEvent?.(question, valid);
    if (!isMobile) {
      conversationRef?.current?.updateData(currentAgent?.id);
    }
    if (!data) {
      return;
    }
    const msgs = cloneDeep(messageList);
    const msg = msgs.find(item => item.id === questionId);
    if (msg) {
      msg.msgData = data;
      setMessageList(msgs);
    }
    if (!isRefresh) {
      updateMessageContainerScroll(`${questionId}`);
    }
  };

  const onAddConversation = () => {
    conversationRef.current?.onAddConversation();
    inputFocus();
  };

  const onSelectAgent = (agent: AgentType) => {
    if (agent.id === currentAgent?.id) {
      return;
    }
    if (messageList.length === 1 && messageList[0].type === MessageTypeEnum.AGENT_LIST) {
      setMessageList([]);
    }
    updateCurrentAgent(agent);
    updateMessageContainerScroll();
  };

  const sendMsg = (msg: string, modelId?: number) => {
    onSendMsg(msg, messageList, modelId);
    if (isMobile) {
      inputBlur();
    }
  };

  const onCloseConversation = () => {
    if (isMobile) {
      setMobileHistoryVisible(false);
    }
  };

  const chatClass = classNames(styles.chat, {
    [styles.mobile]: isMobile,
    [styles.historyVisible]: historyVisible,
  });

  return (
    <ConfigProvider locale={locale}>
      <div className={chatClass}>
        <div className={styles.chatSection}>
          {/* 桌面端固定展示，移动端通过底部入口打开全屏历史面板。 */}
          <Conversation
            currentAgent={currentAgent}
            currentConversation={currentConversation}
            historyVisible={historyVisible}
            closable={!!isMobile}
            onSelectConversation={onSelectConversation}
            onCloseConversation={onCloseConversation}
            onInitializationChange={(loading, error) => {
              setConversationInitializing(loading);
              setConversationError(error || '');
            }}
            ref={conversationRef}
          />
          <div className={styles.chatApp}>
            {!currentConversation && (conversationInitializing || conversationError) && (
              <div className={styles.conversationState}>
                {conversationInitializing ? (
                  <Spin tip="正在加载会话" />
                ) : (
                  <Alert
                    type="error"
                    showIcon
                    message={conversationError}
                    action={
                      <Button size="small" onClick={() => conversationRef.current?.initData()}>
                        重试
                      </Button>
                    }
                  />
                )}
              </div>
            )}
            {currentConversation && (
              <div className={styles.chatBody}>
                <div className={styles.chatContent}>
                  <MessageContainer
                    id="messageContainer"
                    isSimpleMode={isSimpleMode}
                    isDebugMode={isDebugMode}
                    messageList={messageList}
                    chatId={currentConversation?.chatId}
                    historyVisible={historyVisible}
                    historyLoading={historyLoading}
                    historyError={historyError}
                    currentAgent={currentAgent}
                    chatVisible={chatVisible}
                    isDeveloper={isDeveloper}
                    integrateSystem={integrateSystem}
                    onSaveToDashboard={onSaveToDashboard}
                    onExportQuery={onExportQuery}
                    onMsgDataLoaded={onMsgDataLoaded}
                    onSendMsg={onSendMsg}
                    onRetryHistory={() => {
                      updateHistoryMsg(historyFailedPage).then(success => {
                        if (success && historyFailedPage > pageNo) {
                          setPageNo(historyFailedPage);
                        }
                      });
                    }}
                    onContinueQuestion={question => {
                      setInputMsg(buildContinuationDraft(question));
                      inputFocus();
                      updateMessageContainerScroll();
                    }}
                  />
                  {!noInput && (
                    <ChatFooter
                      inputMsg={inputMsg}
                      chatId={currentConversation?.chatId}
                      agentList={agentList}
                      currentAgent={currentAgent}
                      onToggleHistoryVisible={() => {
                        setMobileAgentsVisible(false);
                        setMobileHistoryVisible(visible => !visible);
                      }}
                      onOpenAgents={() => {
                        setMobileHistoryVisible(false);
                        setMobileAgentsVisible(true);
                      }}
                      onInputMsgChange={onInputMsgChange}
                      onSendMsg={sendMsg}
                      onAddConversation={onAddConversation}
                      onSelectAgent={onSelectAgent}
                      onOpenShowcase={() => {
                        setShowCaseVisible(!showCaseVisible);
                      }}
                      ref={chatFooterRef}
                    />
                  )}
                </div>
              </div>
            )}
          </div>
          {currentAgent &&
            (isMobile ? (
              <Drawer
                title="showcase"
                placement="bottom"
                height="95%"
                open={showCaseVisible}
                className={styles.showCaseDrawer}
                destroyOnClose
                onClose={() => {
                  setShowCaseVisible(false);
                }}
              >
                <ShowCase agentId={currentAgent.id} onSendMsg={onSendMsg} />
              </Drawer>
            ) : (
              <Modal
                title="showcase"
                width="98%"
                open={showCaseVisible}
                centered
                footer={null}
                wrapClassName={styles.showCaseModal}
                destroyOnClose
                onCancel={() => {
                  setShowCaseVisible(false);
                }}
              >
                <ShowCase
                  height="calc(100vh - 140px)"
                  agentId={currentAgent.id}
                  onSendMsg={onSendMsg}
                />
              </Modal>
            ))}
        </div>
        <MobileAgents
          open={mobileAgentsVisible}
          agentList={agentList}
          currentAgent={currentAgent}
          onSelectAgent={onSelectAgent}
          onClose={() => {
            setMobileAgentsVisible(false);
          }}
        />
      </div>
    </ConfigProvider>
  );
};

export default forwardRef(Chat);
