import {
  BankIntentResultType,
  ChatContextType,
  DateInfoType,
  DashboardQuerySource,
  EntityInfoType,
  FilterItemType,
  MsgDataType,
  MultiTurnContextType,
  ParseStateEnum,
  ParseTimeCostType,
  RangeValue,
} from '../../common/type';
import { createContext, useEffect, useRef, useState } from 'react';
import { chatExecute, chatParse, queryData, switchEntity, getExecuteSummary } from '../../service';
import { PARSE_ERROR_TIP, PREFIX_CLS, SEARCH_EXCEPTION_TIP } from '../../common/constants';
import { message, Spin } from 'antd';
import AssistantAvatar from '../AssistantAvatar';
import ExpandParseTip from './ExpandParseTip';
import ExecuteItem from './ExecuteItem';
import { isMobile } from '../../utils/utils';
import classNames from 'classnames';
import { AgentType } from '../../Chat/type';
import dayjs, { Dayjs } from 'dayjs';
import { exportCsvFile } from '../../utils/utils';
import { useMethodRegister } from '../../hooks';
import BankAnswerWorkflow from './BankAnswerWorkflow';
import BankAnswerToolbar from './BankAnswerToolbar';
import MultiTurnContextBar from './MultiTurnContextBar';
import { QueryWorkflowStage, stageFromRequestError, stageFromResponseCode } from './workflow';
import { buildDashboardQuerySource, canSaveDashboardResult } from './dashboardModel';
import { shouldAwaitClarification } from './contextModel';

const SUMMARY_POLL_INTERVAL_MS = 500;
// 总结轮询上限 2 分钟：解释阶段走 LLM，复杂问题可能较慢；
// 到达上限不代表查询失败（execute 已拿到数据），只代表解释未就绪
const SUMMARY_POLL_MAX_ATTEMPTS = 240;

type Props = {
  msg: string;
  conversationId?: number;
  questionId?: number;
  modelId?: number;
  agentId?: number;
  score?: number;
  filter?: any[];
  parseInfos?: ChatContextType[];
  parseTimeCostValue?: ParseTimeCostType;
  msgData?: MsgDataType;
  triggerResize?: boolean;
  isDeveloper?: boolean;
  integrateSystem?: string;
  executeItemNode?: React.ReactNode;
  renderCustomExecuteNode?: boolean;
  isSimpleMode?: boolean;
  isDebugMode?: boolean;
  currentAgent?: AgentType;
  isLastMessage?: boolean;
  onMsgDataLoaded?: (data: MsgDataType, valid: boolean, isRefresh?: boolean) => void;
  onUpdateMessageScroll?: () => void;
  onSendMsg?: (msg: string) => void;
  onContinueQuestion?: (question: string) => void;
  onSaveToDashboard?: (source: DashboardQuerySource) => void;
  onExportQuery?: (source: DashboardQuerySource) => void;
};

export const ChartItemContext = createContext({
  register: (...args: any[]) => {},
  call: (...args: any[]) => {},
});

const ChatItem: React.FC<Props> = ({
  msg,
  conversationId,
  questionId,
  modelId,
  agentId,
  score,
  filter,
  triggerResize,
  parseInfos,
  parseTimeCostValue,
  msgData,
  isDeveloper,
  isDebugMode,
  integrateSystem,
  executeItemNode,
  renderCustomExecuteNode,
  isSimpleMode,
  currentAgent,
  onMsgDataLoaded,
  onUpdateMessageScroll,
  onSendMsg,
  onContinueQuestion,
  onSaveToDashboard,
  onExportQuery,
}) => {
  const [parseLoading, setParseLoading] = useState(false);
  const [parseTimeCost, setParseTimeCost] = useState<ParseTimeCostType>();
  const [parseInfo, setParseInfo] = useState<ChatContextType>();
  const [parseInfoOptions, setParseInfoOptions] = useState<ChatContextType[]>([]);
  const [preParseInfoOptions, setPreParseInfoOptions] = useState<ChatContextType[]>([]);
  const [parseTip, setParseTip] = useState('');
  const [executeMode, setExecuteMode] = useState(false);
  const [preParseMode, setPreParseMode] = useState(false);
  const [showExpandParseTip, setShowExpandParseTip] = useState(false);
  const [executeLoading, setExecuteLoading] = useState(false);
  const [executeTip, setExecuteTip] = useState('');
  const [executeErrorMsg, setExecuteErrorMsg] = useState('');
  const [data, setData] = useState<MsgDataType>();
  const [entitySwitchLoading, setEntitySwitchLoading] = useState(false);
  const [dimensionFilters, setDimensionFilters] = useState<FilterItemType[]>([]);
  const [dateInfo, setDateInfo] = useState<DateInfoType>({} as DateInfoType);
  const [entityInfo, setEntityInfo] = useState<EntityInfoType>({} as EntityInfoType);
  const [dataCache, setDataCache] = useState<Record<number, { tip: string; data?: MsgDataType }>>(
    {}
  );
  const [isParserError, setIsParseError] = useState<boolean>(false);
  const [multiTurnContext, setMultiTurnContext] = useState<MultiTurnContextType>();
  const [bankIntentResult, setBankIntentResult] = useState<BankIntentResultType>();
  const [workflowStage, setWorkflowStage] = useState<QueryWorkflowStage>('idle');
  const [totalWallMs, setTotalWallMs] = useState<number>();
  const queryStartRef = useRef(0);
  const summaryPollToken = useRef(0);

  const prefixCls = `${PREFIX_CLS}-item`;

  const updateData = (res: Result<MsgDataType>) => {
    let tip: string = '';
    let data: MsgDataType | undefined = undefined;
    const { queryColumns, queryResults, queryState, queryMode, response, chatContext, errorMsg } =
      res.data || {};
    setExecuteErrorMsg(errorMsg);
    if (res.code === 400 || res.code === 401 || res.code === 403 || res.code === 412) {
      tip = res.msg;
    } else if (res.code !== 200) {
      tip = SEARCH_EXCEPTION_TIP;
    } else if (queryState !== 'SUCCESS') {
      tip = response && typeof response === 'string' ? response : SEARCH_EXCEPTION_TIP;
    } else if (
      (queryColumns && queryColumns.length > 0 && queryResults) ||
      queryMode === 'WEB_PAGE' ||
      queryMode === 'WEB_SERVICE' ||
      queryMode === 'PLAIN_TEXT'
    ) {
      data = res.data;
      tip = '';
    }
    if (chatContext) {
      setDataCache({ ...dataCache, [chatContext!.id!]: { tip, data } });
    }
    if (data) {
      setData(data);
      setExecuteTip('');
      return true;
    }
    setExecuteTip(tip || SEARCH_EXCEPTION_TIP);
    return false;
  };

  const pollExecuteSummary = async (
    baseData: MsgDataType,
    queryId: number,
    attempt: number,
    token: number
  ) => {
    if (token !== summaryPollToken.current) {
      return;
    }
    if (attempt >= SUMMARY_POLL_MAX_ATTEMPTS) {
      // 轮询超时只说明解释未生成，查询本身已成功，按完成处理
      setWorkflowStage('completed');
      return;
    }
    try {
      const response: any = await getExecuteSummary(queryId);
      if (token !== summaryPollToken.current) {
        return;
      }
      if (response?.code !== 200 || !response?.data) {
        setWorkflowStage(stageFromResponseCode(response?.code));
        return;
      }
      if (response.data.queryMode == null) {
        setData({ ...baseData, textSummary: response.data.textSummary });
        window.setTimeout(
          () => pollExecuteSummary(baseData, queryId, attempt + 1, token),
          SUMMARY_POLL_INTERVAL_MS
        );
        return;
      }
      setData(response.data);
      setWorkflowStage('completed');
    } catch (error) {
      setWorkflowStage(stageFromRequestError(error));
    }
  };

  const onExecute = async (
    parseInfoValue: ChatContextType,
    parseInfos?: ChatContextType[],
    isSwitchParseInfo?: boolean,
    isRefresh = false
  ) => {
    summaryPollToken.current += 1;
    const pollToken = summaryPollToken.current;
    setWorkflowStage('executing');
    setExecuteMode(true);
    if (isSwitchParseInfo) {
      setEntitySwitchLoading(true);
    } else {
      setExecuteLoading(true);
    }
    try {
      const res: any = await chatExecute(msg, conversationId!, parseInfoValue, agentId, true);
      const valid = updateData(res);
      onMsgDataLoaded?.(
        {
          ...res.data,
          parseInfos,
          queryId: parseInfoValue.queryId,
        },
        valid,
        isRefresh
      );
      const queryId = parseInfoValue.queryId;
      if (!valid) {
        setWorkflowStage(stageFromResponseCode(res?.code));
      } else if (queryId != undefined && res.data.queryState != 'INVALID') {
        setWorkflowStage('explaining');
        window.setTimeout(
          () => pollExecuteSummary(res.data, queryId, 0, pollToken),
          SUMMARY_POLL_INTERVAL_MS
        );
      } else {
        setWorkflowStage('completed');
      }
    } catch (error) {
      const tip = SEARCH_EXCEPTION_TIP;
      setExecuteTip(SEARCH_EXCEPTION_TIP);
      setDataCache({ ...dataCache, [parseInfoValue!.id!]: { tip } });
      setWorkflowStage(stageFromRequestError(error));
    }
    if (isSwitchParseInfo) {
      setEntitySwitchLoading(false);
    } else {
      setExecuteLoading(false);
    }
  };

  const updateDimensionFitlers = (filters: FilterItemType[]) => {
    setDimensionFilters(
      filters.sort((a, b) => {
        if (a.name < b.name) {
          return -1;
        }
        if (a.name > b.name) {
          return 1;
        }
        return 0;
      })
    );
  };

  const sendMsg = async () => {
    queryStartRef.current = performance.now();
    setTotalWallMs(undefined);
    setWorkflowStage('parsing');
    setBankIntentResult(undefined);
    setIsParseError(false);
    setParseLoading(true);
    try {
      const parseData: any = await chatParse({
        queryText: msg,
        chatId: conversationId,
        modelId,
        agentId,
        filters: filter,
      });
      const { code, data } = parseData || {};
      const {
        state,
        selectedParses,
        candidateParses,
        queryId,
        parseTimeCost,
        errorMsg,
        multiTurnContext,
        bankIntentResult: responseBankIntentResult,
      } = data || {};
      setMultiTurnContext(multiTurnContext);
      setBankIntentResult(responseBankIntentResult);
      const parses = selectedParses?.concat(candidateParses || []) || [];
      if (shouldAwaitClarification(responseBankIntentResult)) {
        const clarificationParseInfo = {
          queryId,
          properties: {},
        } as ChatContextType;
        setParseInfoOptions([]);
        setParseInfo(clarificationParseInfo);
        setParseTimeCost(parseTimeCost);
        setEntityInfo({} as EntityInfoType);
        setDimensionFilters([]);
        setDateInfo({} as DateInfoType);
        setParseTip('');
        setIsParseError(false);
        setExecuteMode(false);
        setWorkflowStage('clarifying');
        onUpdateMessageScroll?.();
        return;
      }
      if (
        code !== 200 ||
        state === ParseStateEnum.FAILED ||
        !parses.length ||
        (!parses[0]?.properties?.type && !parses[0]?.queryMode)
      ) {
        setParseTip(state === ParseStateEnum.FAILED && errorMsg ? errorMsg : PARSE_ERROR_TIP);
        setParseInfo({ queryId } as any);
        setIsParseError(true);
        setWorkflowStage(stageFromResponseCode(code));
        return;
      }
      onUpdateMessageScroll?.();
      const parseInfos = parses.slice(0, 5).map((item: any) => ({
        ...item,
        queryId,
      }));
      if (parseInfos.length > 1) {
        setPreParseInfoOptions(parseInfos);
        setShowExpandParseTip(true);
        setPreParseMode(true);
      }
      setParseInfoOptions(parseInfos || []);
      const parseInfoValue = parseInfos[0];
      if (!(currentAgent?.enableFeedback === 1 && parseInfos.length > 1)) {
        setParseInfo(parseInfoValue);
      }
      setParseTimeCost(parseTimeCost);
      setEntityInfo(parseInfoValue.entityInfo || {});
      updateDimensionFitlers(parseInfoValue?.dimensionFilters || []);
      setDateInfo(parseInfoValue?.dateInfo);
      if (parseInfos.length === 1) {
        onExecute(parseInfoValue, parseInfos);
      }
    } catch (error) {
      setParseTip(PARSE_ERROR_TIP);
      setIsParseError(true);
      setWorkflowStage(stageFromRequestError(error));
    } finally {
      setParseLoading(false);
    }
  };

  const initChatItem = (msg, msgData) => {
    if (msgData) {
      const historyBankIntentResult = msgData.bankIntentResult;
      setBankIntentResult(historyBankIntentResult);
      if (shouldAwaitClarification(historyBankIntentResult)) {
        const clarificationParseInfo = {
          queryId: msgData.queryId,
          properties: {},
        } as ChatContextType;
        setParseInfoOptions([]);
        setParseInfo(clarificationParseInfo);
        setParseTimeCost(parseTimeCostValue);
        setExecuteMode(false);
        setParseTip('');
        setIsParseError(false);
        setWorkflowStage('clarifying');
        return;
      }
      const parseInfoOptionsValue =
        parseInfos && parseInfos.length > 0
          ? parseInfos.map(item => ({ ...item, queryId: msgData.queryId }))
          : [{ ...msgData.chatContext, queryId: msgData.queryId }];
      const parseInfoValue = parseInfoOptionsValue[0];
      setParseInfoOptions(parseInfoOptionsValue);
      setParseInfo(parseInfoValue);
      setParseTimeCost(parseTimeCostValue);
      updateDimensionFitlers(parseInfoValue.dimensionFilters || []);
      setDateInfo(parseInfoValue.dateInfo);
      setExecuteMode(true);
      // 历史消息也可能是失败的查询，按实际结果决定终态，不能一律显示完成
      const valid = updateData({ code: 200, data: msgData, msg: 'success' });
      setWorkflowStage(valid ? 'completed' : 'failed');
    } else if (msg) {
      sendMsg();
    }
  };

  useEffect(() => {
    if (data !== undefined || executeTip !== '' || parseLoading) {
      return;
    }
    initChatItem(msg, msgData);
  }, [msg, msgData]);

  useEffect(
    () => () => {
      summaryPollToken.current += 1;
    },
    []
  );

  // 终态时定格前端实测的全链路墙钟耗时（历史消息没有，回退用后端 parseTime）
  useEffect(() => {
    const terminal = ['completed', 'failed', 'forbidden', 'timeout'].includes(workflowStage);
    if (terminal && queryStartRef.current > 0 && totalWallMs === undefined) {
      setTotalWallMs(Math.round(performance.now() - queryStartRef.current));
    }
  }, [workflowStage]);

  const onSwitchEntity = async (entityId: string) => {
    setEntitySwitchLoading(true);
    const res = await switchEntity(entityId, data?.chatContext?.modelId, conversationId || 0);
    setEntitySwitchLoading(false);
    setData(res.data);
    const { chatContext, entityInfo } = res.data || {};
    const chatContextValue = { ...(chatContext || {}), queryId: parseInfo?.queryId };
    setParseInfo(chatContextValue);
    setEntityInfo(entityInfo);
    updateDimensionFitlers(chatContextValue?.dimensionFilters || []);
    setDateInfo(chatContextValue?.dateInfo);
    setDataCache({ ...dataCache, [chatContextValue.id!]: { tip: '', data: res.data } });
  };

  const onFiltersChange = (dimensionFilters: FilterItemType[]) => {
    setDimensionFilters(dimensionFilters);
  };

  const onDateInfoChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    if (dates && dates[0] && dates[1]) {
      const [start, end] = dates;
      setDateInfo({
        ...(dateInfo || {}),
        startDate: dayjs(start).format('YYYY-MM-DD'),
        endDate: dayjs(end).format('YYYY-MM-DD'),
        dateMode: 'BETWEEN',
        unit: 0,
      });
    }
  };

  const handlePresetClick = (range: RangeValue) => {
    setDateInfo({
      ...(dateInfo || {}),
      startDate: dayjs(range[0]).format('YYYY-MM-DD'),
      endDate: dayjs(range[1]).format('YYYY-MM-DD'),
      dateMode: 'BETWEEN',
      unit: 0,
    });
  };

  const onRefresh = async (parseInfoValue?: ChatContextType) => {
    setEntitySwitchLoading(true);
    const { dimensions, metrics, id, queryId } = parseInfoValue || parseInfo || {};
    const chatContextValue = {
      dimensions,
      metrics,
      dateInfo,
      dimensionFilters,
      parseId: id,
      queryId,
    };
    const res: any = await queryData(chatContextValue);
    setEntitySwitchLoading(false);
    if (res.code === 200) {
      const resChatContext = res.data?.chatContext;
      const contextValue = { ...(resChatContext || chatContextValue), queryId };
      const dataValue = {
        ...res.data,
        chatContext: contextValue,
        parseInfos: parseInfoOptions,
        queryId,
      };
      onMsgDataLoaded?.(dataValue, true, true);
      setData(dataValue);
      setParseInfo(contextValue);
      setDataCache({ ...dataCache, [id!]: { tip: '', data: dataValue } });
    }
  };

  const onExpandSelectParseInfo = async (parseInfoValue: ChatContextType) => {
    setParseInfo(parseInfoValue);
    setPreParseMode(false);
    const { id: parseId, queryId } = parseInfoValue;
    setParseLoading(true);
    const { code, data }: any = await chatParse({
      queryText: msg,
      chatId: conversationId,
      modelId,
      agentId,
      filters: filter,
      parseId,
      queryId,
      parseInfo: parseInfoValue,
    });
    setParseLoading(false);
    if (code === 200) {
      setParseTimeCost(data.parseTimeCost);
      const parseInfo = data.selectedParses[0];
      parseInfo.queryId = data.queryId;
      setParseInfoOptions([parseInfo]);
      setParseInfo(parseInfo);
      updateDimensionFitlers(parseInfo.dimensionFilters || []);
      setDateInfo(parseInfo.dateInfo);
      if (parseInfo.entityInfo) {
        setEntityInfo(parseInfo.entityInfo);
      }
      onExecute(parseInfo, [parseInfo], true, true);
    }
  };

  const actualQueryText = parseInfo?.properties?.CONTEXT?.queryText ?? msg;

  const onExportData = () => {
    if (onExportQuery) {
      const capability = canSaveDashboardResult(data);
      if (!capability.enabled) {
        message.error(capability.reason || '该条消息暂不支持导出');
        return;
      }
      onExportQuery(
        buildDashboardQuerySource({
          question: actualQueryText,
          context: parseInfo,
          data,
        })
      );
      return;
    }
    const { queryColumns, queryResults } = data || {};
    if (!!queryResults && !!queryColumns) {
      const exportData = queryResults.map(item => {
        return queryColumns.reduce((result, column) => {
          result[column.name || column.nameEn] = item[column.nameEn];
          return result;
        }, {});
      });
      if (exportData.length === 0) {
        message.error('该条消息暂不支持该操作');
        return;
      }
      exportCsvFile(exportData);
    }
  };

  const contentClass = classNames(`${prefixCls}-content`, {
    [`${prefixCls}-content-mobile`]: isMobile,
  });

  const { llmReq, llmResp } = parseInfo?.properties?.CONTEXT || {};

  const { register, call } = useMethodRegister(() => message.error('该条消息暂不支持该操作'));

  return (
    <ChartItemContext.Provider value={{ register, call }}>
      <div className={prefixCls}>
        {!isMobile && <AssistantAvatar size={32} className={`${prefixCls}-avatar`} />}
        <div className={isMobile ? `${prefixCls}-mobile-msg-card` : ''}>
          <div className={`${prefixCls}-time`}>
            {parseTimeCost?.parseStartTime
              ? dayjs(parseTimeCost.parseStartTime).format('M月D日 HH:mm')
              : ''}
          </div>
          <div className={contentClass}>
            <MultiTurnContextBar context={multiTurnContext} question={msg} onSendMsg={onSendMsg} />
            {parseInfo?.queryMode !== 'PLAIN_TEXT' && (
              <BankAnswerWorkflow
                question={msg}
                parseInfo={parseInfo}
                parseTimeCost={parseTimeCost}
                totalTimeCost={totalWallMs}
                data={data}
                workflowStage={workflowStage}
                intent={bankIntentResult}
                onApplyClarification={question => onSendMsg?.(question)}
                parseTip={parseTip}
                isSimpleMode={isSimpleMode}
                isDeveloper={isDeveloper}
                isDebugMode={isDebugMode}
                dimensionFilters={dimensionFilters}
                dateInfo={dateInfo}
                entityInfo={entityInfo}
                agentId={agentId}
                integrateSystem={integrateSystem}
                onFiltersChange={onFiltersChange}
                onSwitchEntity={onSwitchEntity}
                onDateInfoChange={onDateInfoChange}
                handlePresetClick={handlePresetClick}
              />
            )}
            <>
              {currentAgent?.enableFeedback === 1 && !questionId && showExpandParseTip && (
                <div style={{ marginBottom: 10 }}>
                  <ExpandParseTip
                    isSimpleMode={isSimpleMode}
                    parseInfoOptions={preParseInfoOptions}
                    agentId={agentId}
                    integrateSystem={integrateSystem}
                    parseTimeCost={parseTimeCost?.parseTime}
                    isDeveloper={isDeveloper}
                    onSelectParseInfo={onExpandSelectParseInfo}
                    onSwitchEntity={onSwitchEntity}
                    onFiltersChange={onFiltersChange}
                    onDateInfoChange={onDateInfoChange}
                    onRefresh={onRefresh}
                    handlePresetClick={handlePresetClick}
                  />
                </div>
              )}
            </>

            {executeMode && workflowStage !== 'clarifying' && (
              <Spin spinning={entitySwitchLoading}>
                <div style={{ minHeight: 50 }}>
                  <ExecuteItem
                    isSimpleMode={isSimpleMode}
                    queryId={parseInfo?.queryId}
                    question={actualQueryText}
                    queryMode={parseInfo?.queryMode}
                    executeLoading={executeLoading}
                    executeTip={executeTip}
                    executeErrorMsg={executeErrorMsg}
                    chartIndex={0}
                    data={data}
                    triggerResize={triggerResize}
                    executeItemNode={executeItemNode}
                    isDeveloper={isDeveloper}
                    renderCustomExecuteNode={renderCustomExecuteNode}
                  />
                </div>
              </Spin>
            )}
            {(parseTip !== '' || (executeMode && !executeLoading)) &&
              workflowStage !== 'clarifying' &&
              parseInfo?.queryMode !== 'PLAIN_TEXT' && (
                <BankAnswerToolbar
                  msg={msg}
                  queryId={parseInfo?.queryId || 0}
                  scoreValue={score}
                  isParserError={isParserError}
                  isSimpleMode={isSimpleMode}
                  isDeveloper={isDeveloper}
                  data={data}
                  parseInfo={parseInfo}
                  workflowStage={workflowStage}
                  parseError={parseTip}
                  executeError={executeErrorMsg}
                  executeErrorMsg={executeErrorMsg}
                  llmReq={llmReq}
                  llmResp={llmResp}
                  agentId={agentId}
                  onContinueQuestion={onContinueQuestion}
                  onRefresh={executeMode && !executeTip ? () => onRefresh() : undefined}
                  onExportData={() => {
                    onExportData();
                  }}
                  onSaveToDashboard={onSaveToDashboard}
                />
              )}
          </div>
        </div>
      </div>
    </ChartItemContext.Provider>
  );
};

export default ChatItem;
