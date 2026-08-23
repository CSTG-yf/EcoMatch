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
import { Alert, Button, ConfigProvider, Drawer, message, Modal, Spin } from 'antd';
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
  const historyVisible = !isMobile || mobileHistoryVisible;
  const [agentList, setAgentList] = useState<AgentType[]>([]);
  const [agentLoading, setAgentLoading] = useState(true);
  const [agentError, setAgentError] = useState('');
  const [agentSwitching, setAgentSwitching] = useState(false);
  const [currentAgent, setCurrentAgent] = useState<AgentType>();
  const [mobileAgentsVisible, setMobileAgentsVisible] = useState(false);
  const [showCaseVisible, setShowCaseVisible] = useState(false);

  const [isSimpleMode, setIsSimpleMode] = useState<boolean>(false);
  const [isDebugMode, setIsDebugMode] = useState<boolean>(true);

  const conversationRef = useRef<any>();
  const chatFooterRef = useRef<any>();
  const historyRequestRef = useRef(0);
  const agentSelectionRequestRef = useRef(0);
  const selectedAgentIdRef = useRef<number>();
  const pendingCopilotParamsRef = useRef<SendMsgParamsType>();

  const updateAgentConfigMode = (agent: AgentType) => {
    const toolConfig = jsonParse(agent?.toolConfig, {});
    const { simpleMode, debugMode } = toolConfig;
    setIsSimpleMode(isBoolean(simpleMode) ? simpleMode : false);
    setIsDebugMode(isBoolean(debugMode) ? debugMode : true);
  };

  const updateAgentIdInUrl = (agent: AgentType) => {
    if (isCopilot) {
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.set('agentId', `${agent.id}`);
    window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`);
  };

  const bindCurrentAgent = (agent: AgentType) => {
    selectedAgentIdRef.current = agent.id;
    setCurrentAgent(agent);
    onCurrentAgentChange?.(agent);
    updateAgentConfigMode(agent);
    updateAgentIdInUrl(agent);
  };

  const clearConversationContext = () => {
    historyRequestRef.current += 1;
    setCurrentConversation(undefined);
    setMessageList([]);
    setInputMsg('');
    setConversationError('');
  };

  const selectAgentAndCreateConversation = async (
    agent: AgentType,
    sendMsgParams?: SendMsgParamsType,
    forceCreate = false
  ) => {
    if (!forceCreate && !sendMsgParams && agent.id === selectedAgentIdRef.current) {
      return;
    }
    const requestId = ++agentSelectionRequestRef.current;
    clearConversationContext();
    bindCurrentAgent(agent);
    setAgentSwitching(true);
    try {
      const conversation = await conversationRef.current?.createConversationForAgent(
        agent,
        sendMsgParams
      );
      if (!conversation) {
        throw new Error('会话创建失败');
      }
      if (requestId !== agentSelectionRequestRef.current) {
        return;
      }
      setCurrentConversation(conversation);
      setMobileAgentsVisible(false);
      updateMessageContainerScroll();
    } catch (error) {
      if (requestId === agentSelectionRequestRef.current) {
        setConversationError('新会话创建失败，请重试');
      }
    } finally {
      if (requestId === agentSelectionRequestRef.current) {
        setAgentSwitching(false);
      }
    }
  };

  const sendCopilotMsg = (params: SendMsgParamsType) => {
    const agent = agentList.find(item => item.id === params.agentId);
    if (!agent) {
      if (agentLoading) {
        pendingCopilotParamsRef.current = params;
      } else {
        message.error('指定助理不可用或无访问权限');
      }
      return;
    }
    if (currentAgent?.id !== agent.id || !currentConversation) {
      selectAgentAndCreateConversation(agent, params);
      return;
    }
    onSendMsg(params.msg, messageList, params.modelId, params);
  };

  useImperativeHandle(ref, () => ({
    sendCopilotMsg,
  }));

  const initAgentList = async () => {
    setAgentLoading(true);
    setAgentError('');
    try {
      const res = await queryAgentList();
      const agentListValue = (res.data || []).filter(
        item => item.status === 1 && (agentIds === undefined || agentIds.includes(item.id))
      );
      setAgentList(agentListValue);
      const pendingParams = pendingCopilotParamsRef.current;
      if (pendingParams) {
        pendingCopilotParamsRef.current = undefined;
        const pendingAgent = agentListValue.find(item => item.id === pendingParams.agentId);
        if (pendingAgent) {
          await selectAgentAndCreateConversation(pendingAgent, pendingParams);
        } else {
          message.error('指定助理不可用或无访问权限');
        }
      } else if (initialAgentId) {
        const explicitAgent = agentListValue.find(item => item.id === initialAgentId);
        if (explicitAgent) {
          await selectAgentAndCreateConversation(explicitAgent);
        } else {
          message.error('指定助理不可用或无访问权限');
        }
      }
    } catch (error) {
      setAgentError('助理列表加载失败，请重试');
    } finally {
      setAgentLoading(false);
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

  const convertHistoryMsg = (list: HistoryMsgItemType[]): MessageItem[] => {
    return list.map((item: HistoryMsgItemType) => ({
      id: item.questionId,
      questionId: item.questionId,
      type: MessageTypeEnum.QUESTION,
      msg: item.queryText,
      parseInfos: item.parseInfos,
      parseTimeCost: item.parseTimeCost,
      msgData: { ...(item.queryResult || {}), similarQueries: item.similarQueries } as MsgDataType,
      score: item.score,
      agentId: currentConversation?.agentId,
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
    if (!currentAgent || !currentConversation || agentSwitching) {
      return;
    }
    const currentMsg = msg || inputMsg;
    if (currentMsg.trim() === '') {
      setInputMsg('');
      return;
    }

    const msgAgent = agentList.find(item => currentMsg.indexOf(`/${item.name}`) === 0);
    if (msgAgent) {
      setInputMsg('');
      await selectAgentAndCreateConversation(msgAgent);
      return;
    }
    if (sendMsgParams?.agentId && sendMsgParams.agentId !== currentAgent.id) {
      return;
    }
    const msgs = [
      ...(list || messageList),
      {
        id: uuid(),
        msg: currentMsg,
        msgValue: currentMsg,
        modelId: modelId === -1 ? undefined : modelId,
        agentId: currentAgent.id,
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

  const onSelectConversation = (
    conversation: ConversationDetailType,
    agent: AgentType,
    sendMsgParams?: SendMsgParamsType,
    isAdd?: boolean
  ) => {
    if (conversation.agentId !== agent.id) {
      message.error('会话绑定的助理不一致，无法恢复');
      return;
    }
    agentSelectionRequestRef.current += 1;
    clearConversationContext();
    bindCurrentAgent(agent);
    setAgentSwitching(false);
    setCurrentConversation({
      ...conversation,
      initialMsgParams: sendMsgParams,
      isAdd,
    });
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
      conversationRef?.current?.updateData();
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

  const onSelectAgent = (agent: AgentType) => {
    selectAgentAndCreateConversation(agent);
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
            agentList={agentList}
            currentAgent={currentAgent}
            currentConversation={currentConversation}
            historyVisible={historyVisible}
            closable={!!isMobile}
            onSelectConversation={onSelectConversation}
            onRequestAgentSelection={() => {
              setMobileHistoryVisible(false);
              if (isMobile) {
                setMobileAgentsVisible(true);
              } else {
                chatFooterRef.current?.openAgentSelector();
              }
            }}
            onCloseConversation={onCloseConversation}
            onInitializationChange={(loading, error) => {
              setConversationInitializing(loading);
              setConversationError(error || '');
            }}
            ref={conversationRef}
          />
          <div className={styles.chatApp}>
            <div className={styles.chatBody}>
              <div className={styles.chatContent}>
                {currentConversation ? (
                  <MessageContainer
                    id="messageContainer"
                    isSimpleMode={isSimpleMode}
                    isDebugMode={isDebugMode}
                    messageList={messageList}
                    chatId={currentConversation.chatId}
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
                ) : (
                  <div className={styles.conversationState}>
                    {agentLoading || agentSwitching || conversationInitializing ? (
                      <Spin tip={agentLoading ? '正在加载助理' : '正在创建会话'} />
                    ) : agentError || conversationError ? (
                      <Alert
                        type="error"
                        showIcon
                        message={agentError || conversationError}
                        action={
                          <Button
                            size="small"
                            onClick={() => {
                              if (agentError) {
                                initAgentList();
                              } else if (currentAgent) {
                                selectAgentAndCreateConversation(currentAgent, undefined, true);
                              } else {
                                conversationRef.current?.initData();
                              }
                            }}
                          >
                            重试
                          </Button>
                        }
                      />
                    ) : agentList.length === 0 ? (
                      <Alert type="info" showIcon message="暂无可用助理" />
                    ) : (
                      <div>请先选择助理，再开始对话</div>
                    )}
                  </div>
                )}
                {!noInput && (
                  <ChatFooter
                    inputMsg={inputMsg}
                    chatId={currentConversation?.chatId}
                    agentList={agentList}
                    agentLoading={agentLoading}
                    agentError={agentError}
                    disabled={!currentAgent || !currentConversation || agentSwitching}
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
                    onSelectAgent={onSelectAgent}
                    ref={chatFooterRef}
                  />
                )}
              </div>
            </div>
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
          loading={agentLoading}
          error={agentError}
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
