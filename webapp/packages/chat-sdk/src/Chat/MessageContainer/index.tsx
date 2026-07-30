import Text from '../components/Text';
import { memo, useCallback, useEffect, useState } from 'react';
import { isEqual } from 'lodash';
import { AgentType, MessageItem, MessageTypeEnum } from '../type';
import { isMobile, updateMessageContainerScroll } from '../../utils/utils';
import styles from './style.module.less';
import AgentTip from '../components/AgentTip';
import classNames from 'classnames';
import { MsgDataType } from '../../common/type';
import ChatItem from '../../components/ChatItem';
import { Alert, Button, Spin } from 'antd';

type Props = {
  id: string;
  chatId: number;
  messageList: MessageItem[];
  historyVisible: boolean;
  currentAgent?: AgentType;
  chatVisible?: boolean;
  isDeveloper?: boolean;
  integrateSystem?: string;
  isSimpleMode?: boolean;
  isDebugMode?: boolean;
  historyLoading?: boolean;
  historyError?: string;
  onMsgDataLoaded: (
    data: MsgDataType,
    questionId: string | number,
    question: string,
    valid: boolean,
    isRefresh?: boolean
  ) => void;
  onSendMsg: (value: string) => void;
  onRetryHistory: () => void;
  onContinueQuestion: (question: string) => void;
};

const MessageContainer: React.FC<Props> = ({
  id,
  chatId,
  messageList,
  historyVisible,
  currentAgent,
  chatVisible,
  isDeveloper,
  integrateSystem,
  isSimpleMode,
  isDebugMode,
  historyLoading,
  historyError,
  onMsgDataLoaded,
  onSendMsg,
  onRetryHistory,
  onContinueQuestion,
}) => {
  const [triggerResize, setTriggerResize] = useState(false);
  const onResize = useCallback(() => {
    setTriggerResize(true);
    setTimeout(() => {
      setTriggerResize(false);
    }, 0);
  }, []);

  useEffect(() => {
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
    };
  }, []);

  useEffect(() => {
    onResize();
  }, [historyVisible, chatVisible]);

  const messageContainerClass = classNames(styles.messageContainer, { [styles.mobile]: isMobile });
  return (
    <div id={id} className={messageContainerClass}>
      <div className={styles.messageList}>
        {(historyLoading || historyError) && (
          <div className={styles.historyState}>
            {historyLoading ? (
              <Spin size="small" tip="正在加载历史消息" />
            ) : (
              <Alert
                type="error"
                showIcon
                message={historyError}
                action={
                  <Button size="small" onClick={onRetryHistory}>
                    重试
                  </Button>
                }
              />
            )}
          </div>
        )}
        {messageList.map((msgItem: MessageItem, index: number) => {
          const {
            id: msgId,
            questionId,
            modelId,
            agentId,
            type,
            msg,
            msgValue,
            score,
            identityMsg,
            parseInfos,
            parseTimeCost,
            msgData,
            filters,
          } = msgItem;

          return (
            <div key={msgId} id={`${msgId}`} className={styles.messageItem}>
              {type === MessageTypeEnum.TEXT && <Text position="left" data={msg} />}
              {type === MessageTypeEnum.AGENT_LIST && (
                <AgentTip currentAgent={currentAgent} onSendMsg={onSendMsg} />
              )}
              {type === MessageTypeEnum.QUESTION && (
                <>
                  <Text position="right" data={msg} />
                  {identityMsg && <Text position="left" data={identityMsg} />}
                  <ChatItem
                    questionId={questionId}
                    currentAgent={currentAgent}
                    isSimpleMode={isSimpleMode}
                    isDebugMode={isDebugMode}
                    msg={msgValue || msg || ''}
                    parseInfos={parseInfos}
                    parseTimeCostValue={parseTimeCost}
                    msgData={msgData}
                    conversationId={chatId}
                    modelId={modelId}
                    agentId={agentId}
                    score={score}
                    filter={filters}
                    triggerResize={triggerResize}
                    isDeveloper={isDeveloper}
                    integrateSystem={integrateSystem}
                    onMsgDataLoaded={(data: MsgDataType, valid: boolean, isRefresh) => {
                      onMsgDataLoaded(data, msgId, msgValue || msg || '', valid, isRefresh);
                    }}
                    onUpdateMessageScroll={updateMessageContainerScroll}
                    onSendMsg={onSendMsg}
                    onContinueQuestion={onContinueQuestion}
                    isLastMessage={index === messageList.length - 1}
                  />
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

function areEqual(prevProps: Props, nextProps: Props) {
  if (
    prevProps.id === nextProps.id &&
    isEqual(prevProps.messageList, nextProps.messageList) &&
    prevProps.historyVisible === nextProps.historyVisible &&
    prevProps.currentAgent === nextProps.currentAgent &&
    prevProps.chatVisible === nextProps.chatVisible &&
    prevProps.isSimpleMode === nextProps.isSimpleMode &&
    prevProps.historyLoading === nextProps.historyLoading &&
    prevProps.historyError === nextProps.historyError
  ) {
    return true;
  }
  return false;
}

export default memo(MessageContainer, areEqual);
