import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  LockOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { PREFIX_CLS } from '../../common/constants';
import { QueryWorkflowStage, WORKFLOW_STAGE_TEXT } from './workflow';

type Props = {
  stage: QueryWorkflowStage;
};

const QueryStageStatus: React.FC<Props> = ({ stage }) => {
  if (stage === 'idle') {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const icon =
    stage === 'completed' ? (
      <CheckCircleOutlined />
    ) : stage === 'clarifying' ? (
      <QuestionCircleOutlined />
    ) : stage === 'forbidden' ? (
      <LockOutlined />
    ) : stage === 'timeout' ? (
      <ClockCircleOutlined />
    ) : stage === 'failed' ? (
      <CloseCircleOutlined />
    ) : (
      <LoadingOutlined spin />
    );
  return (
    <div
      className={`${prefixCls}-workflow-status ${prefixCls}-workflow-status-${stage}`}
      role="status"
      aria-live="polite"
    >
      {icon}
      <span>{WORKFLOW_STAGE_TEXT[stage]}</span>
    </div>
  );
};

export default QueryStageStatus;
