import LeftAvatar from '../CopilotAvatar';
import Message from '../Message';
import styles from './style.module.less';
import { AgentType } from '../../type';
import { isMobile } from '../../../utils/utils';

type Props = {
  currentAgent?: AgentType;
  onSelectQuestion: (value: string) => void;
};

const BANK_PRESET_QUESTIONS = [
  '江苏省B市农商行在2025-03-31的各项贷款余额是多少？',
  '江苏省H市农商行在2026-03-31的不良贷款率、拨备覆盖率、逾期贷款率和资本充足率分别是多少？',
  '江苏省I市农商行在2026-01-31的存贷比是多少？',
  '江苏省M市农商行在2025-12-31的人均利润是多少？',
  '江苏省D市农商行的各项贷款余额从2024-12-31到2025-05-31变化了多少？',
  '2026-02-28江苏省J市和江苏省L市农商行谁的不良贷款率更低？',
  '2026-03-31江苏省K市农商行的不良贷款率高于全省均值多少？',
  '2025-09-30全省各家农商行的不良贷款率排名前三的是哪些？',
  '2025-12-31全省各家农商行中，贷款余额超过全省均值的有几家？',
  '2025-12-31江苏省B市农商行的拨备覆盖率是否超过150%？',
  '分析江苏省D市农商行的各项存款余额从2025年一季度末到2026年一季度末的逐季变化。',
  '2025年全年，江苏省G市农商行的成本收入比日均值、最高日和最低日分别是多少？',
];

const AgentTip: React.FC<Props> = ({ currentAgent, onSelectQuestion }) => {
  if (!currentAgent) {
    return null;
  }
  return (
    <div className={styles.agentTip}>
      {!isMobile && <LeftAvatar />}
      <Message position="left" bubbleClassName={styles.agentTipMsg}>
        <div className={styles.title}>
          您好，智能助理【{currentAgent.name}】将与您对话。
        </div>
        <div className={styles.content}>
          {currentAgent.examples?.length > 0 && (
            <div className={styles.examples}>
              <div className={styles.sectionTitle}>您也可以这样问：</div>
              {currentAgent.examples.map(example => (
                <button
                  type="button"
                  key={example}
                  className={styles.example}
                  onClick={() => {
                    onSelectQuestion(example);
                  }}
                >
                  {example}
                </button>
              ))}
            </div>
          )}
          <div className={styles.examples}>
            <div className={styles.sectionTitle}>可以直接测试这些问题：</div>
            {BANK_PRESET_QUESTIONS.map((question, index) => (
              <button
                type="button"
                key={question}
                className={styles.example}
                onClick={() => {
                  onSelectQuestion(question);
                }}
              >
                {index + 1}. {question}
              </button>
            ))}
          </div>
          {!currentAgent.examples?.length && currentAgent.description && (
            <div className={styles.description}>{currentAgent.description}</div>
          )}
        </div>
      </Message>
    </div>
  );
};

export default AgentTip;
