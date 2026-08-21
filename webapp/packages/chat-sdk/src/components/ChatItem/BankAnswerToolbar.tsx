import {
  BugOutlined,
  CommentOutlined,
  DashboardOutlined,
  DislikeOutlined,
  DownloadOutlined,
  FileImageOutlined,
  FileTextOutlined,
  LikeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { message, Tooltip } from 'antd';
import classNames from 'classnames';
import { useContext, useState } from 'react';
import {
  ChatContextType,
  DashboardQuerySource,
  MsgDataType,
} from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import { updateQAFeedback } from '../../service';
import { exportTextFile } from '../../utils/utils';
import { ChartItemContext } from './index';
import { buildDashboardQuerySource, canSaveDashboardResult } from './dashboardModel';
import { buildExportLogText } from './answerCardModel';
import { QueryWorkflowStage } from './workflow';
import TechnicalDiagnosticsModal from './TechnicalDiagnosticsModal';

type Props = {
  msg: string;
  queryId: number;
  scoreValue?: number;
  isParserError?: boolean;
  isSimpleMode?: boolean;
  data?: MsgDataType;
  parseInfo?: ChatContextType;
  workflowStage: QueryWorkflowStage;
  parseError?: string;
  executeError?: string;
  executeErrorMsg?: string;
  llmReq?: any;
  llmResp?: any;
  agentId?: number;
  onContinueQuestion?: (question: string) => void;
  onRefresh?: () => void;
  onExportData?: () => void;
  onSaveToDashboard?: (source: DashboardQuerySource) => void;
};

const BankAnswerToolbar: React.FC<Props> = ({
  msg,
  queryId,
  scoreValue,
  isParserError = false,
  isSimpleMode = false,
  data,
  parseInfo,
  workflowStage,
  parseError,
  executeError,
  executeErrorMsg,
  llmReq,
  llmResp,
  agentId,
  onContinueQuestion,
  onRefresh,
  onExportData,
  onSaveToDashboard,
}) => {
  const [score, setScore] = useState(scoreValue || 0);
  const [exportLoading, setExportLoading] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const prefixCls = `${PREFIX_CLS}-item`;
  const toolbarCls = `${prefixCls}-answer-toolbar`;
  const { call } = useContext(ChartItemContext);

  // 点赞/点踩后端会同步写入记忆的管理员评估结果（正确/错误）
  const like = () => {
    setScore(5);
    updateQAFeedback(queryId, 5);
  };

  const dislike = () => {
    setScore(1);
    updateQAFeedback(queryId, 1);
  };

  const saveCapability = canSaveDashboardResult(data);

  const hasLogSource =
    !!llmReq ||
    !!parseInfo?.sqlInfo?.parsedS2SQL ||
    !!parseInfo?.sqlInfo?.correctedS2SQL ||
    !!parseInfo?.sqlInfo?.querySQL;

  const onExportLog = () => {
    const text = buildExportLogText({
      question: msg,
      queryMode: parseInfo?.queryMode,
      llmReq,
      llmResp,
      sqlInfo: parseInfo?.sqlInfo,
      executeErrorMsg,
    });
    exportTextFile(text, `supersonic-debug-${agentId}-${queryId}.log`);
  };

  const renderButton = (
    key: string,
    title: string,
    icon: React.ReactNode,
    onClick?: () => void,
    options?: { disabled?: boolean; active?: boolean; loading?: boolean }
  ) => (
    <Tooltip key={key} title={title}>
      <button
        type="button"
        aria-label={title}
        disabled={options?.disabled || options?.loading}
        className={classNames(`${toolbarCls}-button`, {
          [`${toolbarCls}-button-active`]: options?.active,
        })}
        onClick={onClick}
      >
        {icon}
      </button>
    </Tooltip>
  );

  return (
    <div className={toolbarCls} role="toolbar" aria-label="回答操作">
      <div className={`${toolbarCls}-group`}>
        {onContinueQuestion &&
          renderButton('continue', '继续追问', <CommentOutlined />, () =>
            onContinueQuestion(msg)
          )}
        {onRefresh &&
          !isParserError &&
          renderButton('refresh', '重新查询', <ReloadOutlined />, onRefresh)}
        {onExportData &&
          !isParserError &&
          renderButton('export-data', '导出数据', <DownloadOutlined />, () => {
            setExportLoading(true);
            onExportData();
            setTimeout(() => setExportLoading(false), 1000);
          })}
        {!isSimpleMode &&
          !isParserError &&
          renderButton('export-image', '导出图片', <FileImageOutlined />, () =>
            call('downloadChartAsImage')
          )}
        {onSaveToDashboard &&
          renderButton(
            'save-dashboard',
            saveCapability.enabled
              ? '保存到看板'
              : saveCapability.reason || '该条消息暂不支持保存到看板',
            <DashboardOutlined />,
            () => {
              if (!saveCapability.enabled) {
                message.error(saveCapability.reason || '该条消息暂不支持该操作');
                return;
              }
              onSaveToDashboard(
                buildDashboardQuerySource({
                  question: msg,
                  context: parseInfo,
                  data,
                })
              );
            },
            { disabled: !saveCapability.enabled }
          )}
      </div>
      <div className={`${toolbarCls}-group ${toolbarCls}-group-right`}>
        {parseInfo &&
          renderButton('diagnostics', '技术详情', <BugOutlined />, () => setDiagnosticsOpen(true))}
        {hasLogSource &&
          renderButton('export-log', '导出日志', <FileTextOutlined />, onExportLog)}
        {renderButton('like', '正确', <LikeOutlined />, like, { active: score === 5 })}
        {renderButton('dislike', '错误', <DislikeOutlined />, dislike, { active: score === 1 })}
      </div>
      <TechnicalDiagnosticsModal
        open={diagnosticsOpen}
        question={msg}
        parseInfo={parseInfo}
        workflowStage={workflowStage}
        parseError={parseError}
        executeError={executeError}
        llmReq={llmReq}
        llmResp={llmResp}
        onClose={() => setDiagnosticsOpen(false)}
      />
    </div>
  );
};

export default BankAnswerToolbar;
