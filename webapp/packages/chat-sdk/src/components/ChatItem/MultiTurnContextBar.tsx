import { DeleteOutlined, RedoOutlined } from '@ant-design/icons';
import { Alert, Button, Space, Tag, Tooltip } from 'antd';
import { MultiTurnContextType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import { buildContextAdjustmentQuestion, contextEntries, ContextEntry } from './contextModel';

type Props = {
  context?: MultiTurnContextType;
  question: string;
  onSendMsg?: (question: string) => void;
};

const OPERATION_LABELS: Record<string, string> = {
  NONE: '未使用历史条件',
  APPEND: '继承并补充',
  REMOVE: '撤销条件',
  REPLACE: '替换条件',
  DRILL_DOWN: '继续下钻',
  RESET: '已清空上下文',
};

const MultiTurnContextBar: React.FC<Props> = ({ context, question, onSendMsg }) => {
  const entries = contextEntries(context);
  if (!context || (!entries.length && !context.expired && context.operation !== 'RESET')) {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const adjust = (action: 'remove' | 'reset' | 'reinterpret', entry?: ContextEntry) =>
    onSendMsg?.(buildContextAdjustmentQuestion(question, action, entry));

  return (
    <section className={`${prefixCls}-context`} aria-label="本轮继承上下文">
      <div className={`${prefixCls}-context-heading`}>
        <strong>
          本轮上下文 {context.usedRounds || 0}/{context.maxRounds || 10} 轮
        </strong>
        <Tag>{OPERATION_LABELS[context.operation || 'NONE'] || context.operation}</Tag>
        {context.truncated && <Tag color="warning">较早内容已压缩</Tag>}
        <Space size={2} className={`${prefixCls}-context-actions`}>
          <Tooltip title="清空继承条件并重新查询">
            <Button
              type="text"
              size="small"
              icon={<DeleteOutlined />}
              aria-label="清空上下文"
              onClick={() => adjust('reset')}
            />
          </Tooltip>
          <Tooltip title="不使用历史条件重新理解当前问题">
            <Button
              type="text"
              size="small"
              icon={<RedoOutlined />}
              aria-label="重新解释"
              onClick={() => adjust('reinterpret')}
            />
          </Tooltip>
        </Space>
      </div>
      {context.expired ? (
        <Alert type="warning" showIcon message="历史上下文已超过 30 分钟，本轮不会继承旧条件" />
      ) : (
        <div className={`${prefixCls}-context-tags`}>
          {entries.map(entry => (
            <Tag
              key={`${entry.kind}-${entry.value}`}
              closable
              onClose={event => {
                event.preventDefault();
                adjust('remove', entry);
              }}
            >
              <span>{entry.label}</span>
              {entry.value}
            </Tag>
          ))}
        </div>
      )}
      {context.rewrittenQuery && context.rewrittenQuery !== question && (
        <div className={`${prefixCls}-context-rewrite`}>
          <span>结合上下文理解为</span>
          <strong>{context.rewrittenQuery}</strong>
        </div>
      )}
    </section>
  );
};

export default MultiTurnContextBar;
