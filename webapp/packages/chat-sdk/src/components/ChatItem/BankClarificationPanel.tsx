import { Button, Checkbox, Radio, Space, Tooltip } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { BankIntentResultType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import {
  buildClarifiedQuestion,
  clarificationComplete,
  requiresMultipleSelection,
} from './contextModel';

type Props = {
  intent?: BankIntentResultType;
  question: string;
  onApply?: (question: string) => void;
};

const BankClarificationPanel: React.FC<Props> = ({ intent, question, onApply }) => {
  const [selections, setSelections] = useState<Record<string, string[]>>({});
  const clarifications = intent?.clarifications || [];

  useEffect(() => {
    setSelections({});
  }, [question, intent?.normalizedText]);

  if (!intent?.clarificationRequired || !clarifications.length) {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const complete = clarificationComplete(clarifications, selections);

  return (
    <section className={`${prefixCls}-clarification`} aria-label="问题澄清">
      <div className={`${prefixCls}-clarification-heading`}>
        <QuestionCircleOutlined />
        <strong>需要补充条件后再查询</strong>
      </div>
      <div className={`${prefixCls}-clarification-groups`}>
        {clarifications.map((clarification, index) => {
          const key = String(index);
          const options = clarification.options || [];
          const multiple = requiresMultipleSelection(clarification);
          return (
            <div
              className={`${prefixCls}-clarification-group`}
              key={`${clarification.type}-${key}`}
            >
              <div>
                <span>{clarification.question || '请选择查询条件'}</span>
                {clarification.reason && (
                  <Tooltip title={clarification.reason}>
                    <QuestionCircleOutlined />
                  </Tooltip>
                )}
              </div>
              {multiple ? (
                <Checkbox.Group
                  options={options}
                  value={selections[key] || []}
                  onChange={values =>
                    setSelections(current => ({ ...current, [key]: values as string[] }))
                  }
                />
              ) : (
                <Radio.Group
                  value={selections[key]?.[0]}
                  onChange={event =>
                    setSelections(current => ({ ...current, [key]: [event.target.value] }))
                  }
                >
                  <Space wrap>
                    {options.map(option => (
                      <Radio key={option} value={option}>
                        {option}
                      </Radio>
                    ))}
                  </Space>
                </Radio.Group>
              )}
            </div>
          );
        })}
      </div>
      <Button
        type="primary"
        size="small"
        disabled={!complete}
        onClick={() =>
          onApply?.(
            buildClarifiedQuestion(
              intent.normalizedText || intent.originalText || question,
              clarifications,
              selections
            )
          )
        }
      >
        按所选条件查询
      </Button>
    </section>
  );
};

export default BankClarificationPanel;
