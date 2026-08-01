import { isMobile } from '../../utils/utils';
import {
  DislikeOutlined,
  LikeOutlined,
  DownloadOutlined,
  RedoOutlined,
  FileJpgOutlined,
  DashboardOutlined,
} from '@ant-design/icons';
import { Button } from 'antd';
import { CLS_PREFIX } from '../../common/constants';
import { useContext, useState } from 'react';
import classNames from 'classnames';
import { updateQAFeedback } from '../../service';
import { useMethodRegister } from '../../hooks';
import { ChartItemContext } from '../ChatItem';
import { DashboardQueryDraft, buildDashboardQueryDraft } from '../../dashboardDraft';
import { MsgDataType } from '../../common/type';

type Props = {
  queryId: number;
  scoreValue?: number;
  isLastMessage?: boolean;
  isParserError?: boolean;
  isSimpleMode?: boolean;
  onExportData?: () => void;
  onReExecute?: (queryId: number) => void;
  question?: string;
  data?: MsgDataType;
  onSaveToDashboard?: (draft: DashboardQueryDraft) => void;
};

const Tools: React.FC<Props> = ({
  queryId,
  scoreValue,
  isLastMessage,
  isParserError = false,
  isSimpleMode = false,
  onExportData,
  onReExecute,
  question,
  data,
  onSaveToDashboard,
}) => {
  const [score, setScore] = useState(scoreValue || 0);
  const [exportLoading, setExportLoading] = useState<boolean>(false);
  const prefixCls = `${CLS_PREFIX}-tools`;

  const like = () => {
    setScore(5);
    updateQAFeedback(queryId, 5);
  };

  const dislike = () => {
    setScore(1);
    updateQAFeedback(queryId, 1);
  };

  const likeClass = classNames(`${prefixCls}-like`, {
    [`${prefixCls}-feedback-active`]: score === 5,
  });
  const dislikeClass = classNames(`${prefixCls}-dislike`, {
    [`${prefixCls}-feedback-active`]: score === 1,
  });

  const { call } = useContext(ChartItemContext);

  return (
    <div className={prefixCls}>
      {!isMobile && (
        <div className={`${prefixCls}-feedback`}>
          {/* <div>这个回答正确吗？</div> */}

          <div className={`${prefixCls}-feedback-left`}>
            {!isParserError && (
              <>
                <Button
                  size="small"
                  onClick={() => {
                    setExportLoading(true);
                    onExportData?.();
                    setTimeout(() => {
                      setExportLoading(false);
                    }, 1000);
                  }}
                  type="text"
                  loading={exportLoading}
                >
                  <DownloadOutlined />
                  <span className={`${prefixCls}-font-style`}>导出数据</span>
                </Button>
                {!isSimpleMode && (
                  <Button
                    size="small"
                    onClick={() => {
                      call('downloadChartAsImage');
                    }}
                    type="text"
                  >
                    <FileJpgOutlined />
                    <span className={`${prefixCls}-font-style`}>导出图片</span>
                  </Button>
                )}
                {!isSimpleMode && onSaveToDashboard && (
                  <Button
                    size="small"
                    type="text"
                    icon={<DashboardOutlined />}
                    onClick={() => {
                      const draft =
                        data && buildDashboardQueryDraft(question || data.question || '', data);
                      if (!draft) {
                        return;
                      }
                      onSaveToDashboard(draft);
                    }}
                  >
                    <span className={`${prefixCls}-font-style`}>保存到看板</span>
                  </Button>
                )}
                {isLastMessage && (
                  <Button
                    size="small"
                    onClick={() => {
                      onReExecute?.(queryId);
                    }}
                    type="text"
                  >
                    <RedoOutlined />
                    <span className={`${prefixCls}-font-style`}>再试一次</span>
                  </Button>
                )}
              </>
            )}
          </div>
          <div className={`${prefixCls}-feedback-left`}>
            <LikeOutlined className={likeClass} onClick={like} style={{ marginRight: 10 }} />
            <DislikeOutlined
              className={dislikeClass}
              onClick={e => {
                e.stopPropagation();
                dislike();
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default Tools;
