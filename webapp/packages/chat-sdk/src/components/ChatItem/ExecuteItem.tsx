import { Space, Spin } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, InfoCircleOutlined } from '@ant-design/icons';
import { PREFIX_CLS } from '../../common/constants';
import { MsgDataType } from '../../common/type';
import ChatMsg from '../ChatMsg';
import WebPage from '../ChatMsg/WebPage';
import Loading from './Loading';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { solarizedlight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import React, { ReactNode, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import BusinessInsightPanel from './BusinessInsightPanel';
import PlainTextAnswerCard from './PlainTextAnswerCard';

type Props = {
  queryId?: number;
  question: string;
  queryMode?: string;
  executeLoading: boolean;
  entitySwitchLoading?: boolean;
  chartIndex: number;
  executeTip?: string;
  executeErrorMsg?: string;
  executeItemNode?: ReactNode;
  renderCustomExecuteNode?: boolean;
  data?: MsgDataType;
  triggerResize?: boolean;
  isDeveloper?: boolean;
  isSimpleMode?: boolean;
};

const ExecuteItem: React.FC<Props> = ({
  queryId,
  question,
  queryMode,
  executeLoading,
  entitySwitchLoading = false,
  executeTip,
  executeErrorMsg,
  executeItemNode,
  renderCustomExecuteNode,
  data,
  triggerResize,
  isDeveloper,
  isSimpleMode,
}) => {
  const prefixCls = `${PREFIX_CLS}-item`;
  const [showErrMsg, setShowErrMsg] = useState<boolean>(false);
  const titlePrefix = queryMode === 'PLAIN_TEXT' || queryMode === 'WEB_SERVICE' ? '问答' : '数据';

  const getNodeTip = (title: ReactNode, tip?: string | ReactNode, failed?: boolean) => {
    return (
      <>
        <div className={`${prefixCls}-title-bar`}>
          {failed ? (
            <CloseCircleFilled className={`${prefixCls}-step-error-icon`} />
          ) : (
            <CheckCircleFilled className={`${prefixCls}-step-icon`} />
          )}
          <div className={`${prefixCls}-step-title`}>
            {title}
            {!tip && <Loading />}
          </div>
        </div>
        {tip && <div className={`${prefixCls}-content-container`}>{tip}</div>}
      </>
    );
  };

  if (executeLoading) {
    return getNodeTip(`${titlePrefix}查询中`);
  }

  if (executeTip) {
    return getNodeTip(
      <>
        <span>{titlePrefix}查询失败</span>
        {executeErrorMsg && (
          <Space>
            <InfoCircleOutlined style={{ marginLeft: 5, color: 'red' }} />
            <a
              onClick={() => {
                setShowErrMsg(!showErrMsg);
              }}
            >
              {!showErrMsg ? '查看' : '收起'}
            </a>
          </Space>
        )}
        {!!data?.queryTimeCost && isDeveloper && (
          <span className={`${prefixCls}-title-tip`}>(耗时: {data.queryTimeCost}ms)</span>
        )}
      </>,

      <>
        {showErrMsg && (
          <SyntaxHighlighter className={`${prefixCls}-code`} language="sql" style={solarizedlight}>
            {executeErrorMsg}
          </SyntaxHighlighter>
        )}
      </>,
      true
    );
  }

  if (!data) {
    return null;
  }

  return (
    <>
      <div
        className={`${prefixCls}-content-container ${
          isSimpleMode ? `${prefixCls}-content-container-simple` : ''
        }`}
        style={{ borderLeft: 'none' }}
      >
        <Spin spinning={entitySwitchLoading}>
          {data.queryAuthorization?.message && (
            <div className={`${prefixCls}-auth-tip`}>提示：{data.queryAuthorization.message}</div>
          )}
          {data.queryMode !== 'PLAIN_TEXT' && (
            <>
              <BusinessInsightPanel
                explanation={data.businessExplanation}
                recommendation={data.recommendedChart}
              />
              {data.textSummary && !data.businessExplanation?.summary && (
                <p className={`${prefixCls}-step-title ${prefixCls}-summary-text`}>
                  <span style={{ marginRight: 5 }}>总结:</span>
                  <ReactMarkdown>{data.textSummary}</ReactMarkdown>
                </p>
              )}
            </>
          )}

          {renderCustomExecuteNode && executeItemNode ? (
            executeItemNode
          ) : data?.queryMode === 'PLAIN_TEXT' ? (
            <PlainTextAnswerCard text={data.textResult} />
          ) : data?.queryMode === 'WEB_SERVICE' ? (
            data?.textResult
          ) : data?.queryMode === 'WEB_PAGE' ? (
            <WebPage id={queryId!} data={data} />
          ) : (
            <ChatMsg
              isSimpleMode={isSimpleMode}
              queryId={queryId}
              question={question}
              data={data}
              triggerResize={triggerResize}
            />
          )}
        </Spin>
      </div>
    </>
  );
};

export default ExecuteItem;
