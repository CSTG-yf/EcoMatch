import IconFont from '../../components/IconFont';
import { getTextWidth, groupByColumn, isMobile } from '../../utils/utils';
import { AutoComplete, Empty, Popover, Select, Spin, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import classNames from 'classnames';
import { debounce } from 'lodash';
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { ForwardRefRenderFunction } from 'react';
import { SemanticTypeEnum, SEMANTIC_TYPE_MAP, HOLDER_TAG } from '../constants';
import { AgentType, ModelType } from '../type';
import { searchRecommend } from '../../service';
import styles from './style.module.less';
import { useComposing } from '../../hooks/useComposing';

type Props = {
  inputMsg: string;
  chatId?: number;
  currentAgent?: AgentType;
  agentList: AgentType[];
  agentLoading?: boolean;
  agentError?: string;
  disabled?: boolean;
  onToggleHistoryVisible: () => void;
  onOpenAgents: () => void;
  onInputMsgChange: (value: string) => void;
  onSendMsg: (msg: string, dataSetId?: number) => void;
  onSelectAgent: (agent: AgentType) => void;
};

const { OptGroup, Option } = Select;
let isPinyin = false;
let isSelect = false;

const compositionStartEvent = () => {
  isPinyin = true;
};

const compositionEndEvent = () => {
  isPinyin = false;
};

const ChatFooter: ForwardRefRenderFunction<any, Props> = (
  {
    inputMsg,
    chatId,
    currentAgent,
    agentList,
    agentLoading,
    agentError,
    disabled,
    onToggleHistoryVisible,
    onOpenAgents,
    onInputMsgChange,
    onSendMsg,
    onSelectAgent,
  },
  ref
) => {
  const [modelOptions, setModelOptions] = useState<(ModelType | AgentType)[]>([]);
  const [stepOptions, setStepOptions] = useState<Record<string, any[]>>({});
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  const [agentSelectorOpen, setAgentSelectorOpen] = useState(false);
  const inputRef = useRef<any>();
  const fetchRef = useRef(0);

  const inputFocus = () => {
    if (!disabled) {
      inputRef.current?.focus();
    }
  };

  const inputBlur = () => {
    inputRef.current?.blur();
  };

  useImperativeHandle(ref, () => ({
    inputFocus,
    inputBlur,
    openAgentSelector: () => setAgentSelectorOpen(true),
  }));

  const initEvents = () => {
    const autoCompleteEl = document.getElementById('chatInput');
    if (autoCompleteEl) {
      autoCompleteEl.addEventListener('compositionstart', compositionStartEvent);
      autoCompleteEl.addEventListener('compositionend', compositionEndEvent);
    }
  };

  const removeEvents = () => {
    const autoCompleteEl = document.getElementById('chatInput');
    if (autoCompleteEl) {
      autoCompleteEl.removeEventListener('compositionstart', compositionStartEvent);
      autoCompleteEl.removeEventListener('compositionend', compositionEndEvent);
    }
  };

  useEffect(() => {
    initEvents();
    return () => {
      removeEvents();
    };
  }, []);

  const getStepOptions = (recommends: any[]) => {
    const data = groupByColumn(recommends, 'dataSetName');
    return isMobile && recommends.length > 6
      ? Object.keys(data)
          .slice(0, 4)
          .reduce((result, key) => {
            result[key] = data[key].slice(
              0,
              Object.keys(data).length > 2 ? 2 : Object.keys(data).length > 1 ? 3 : 6
            );
            return result;
          }, {})
      : data;
  };

  const processMsg = (msg: string) => {
    let msgValue = msg;
    let dataSetId: number | undefined;
    if (msg?.[0] === '/') {
      const agent = agentList.find(item => msg.includes(`/${item.name}`));
      msgValue = agent ? msg.replace(`/${agent.name}`, '') : msg;
    }
    return { msgValue, dataSetId };
  };

  const debounceGetWordsFunc = useCallback(() => {
    const getAssociateWords = async (msg: string, chatId?: number, currentAgent?: AgentType) => {
      if (isPinyin) {
        return;
      }
      if (msg === '' || (msg.length === 1 && msg[0] === '@')) {
        return;
      }
      fetchRef.current += 1;
      const fetchId = fetchRef.current;
      const { msgValue, dataSetId } = processMsg(msg);
      const res = await searchRecommend(msgValue.trim(), chatId, dataSetId, currentAgent?.id);
      if (fetchId !== fetchRef.current) {
        return;
      }
      const recommends = msgValue ? res.data || [] : [];
      const stepOptionList = recommends.map((item: any) => item.subRecommend);
      if (stepOptionList.length > 0 && stepOptionList.every((item: any) => item !== null)) {
        setStepOptions(getStepOptions(recommends));
      } else {
        setStepOptions({});
      }
      setOpen(recommends.length > 0);
    };
    return debounce(getAssociateWords, 200);
  }, []);

  const [debounceGetWords] = useState<any>(debounceGetWordsFunc);

  useEffect(() => {
    if (disabled) {
      debounceGetWords.cancel();
      fetchRef.current += 1;
      setOpen(false);
      setModelOptions([]);
      setStepOptions({});
      return;
    }
    if (inputMsg.length === 1 && inputMsg[0] === '/') {
      setOpen(true);
      setModelOptions(agentList);
      setStepOptions({});
      return;
    }
    if (modelOptions.length > 0) {
      setTimeout(() => {
        setModelOptions([]);
      }, 50);
    }
    if (!isSelect && currentAgent && chatId) {
      debounceGetWords(inputMsg, chatId, currentAgent);
    } else {
      isSelect = false;
    }
    if (!inputMsg) {
      debounceGetWords.cancel();
      fetchRef.current += 1;
      setStepOptions({});
    }
  }, [inputMsg, disabled, chatId, currentAgent?.id]);

  useEffect(() => {
    if (!focused) {
      setOpen(false);
    }
  }, [focused]);

  useEffect(() => {
    const autoCompleteDropdown = document.querySelector(
      `.${styles.autoCompleteDropdown}`
    ) as HTMLElement;
    if (!autoCompleteDropdown) {
      return;
    }
    const textWidth = getTextWidth(inputMsg);
    if (Object.keys(stepOptions).length > 0) {
      autoCompleteDropdown.style.marginLeft = `${textWidth}px`;
    } else {
      setTimeout(() => {
        autoCompleteDropdown.style.marginLeft = `0px`;
      }, 200);
    }
  }, [stepOptions]);

  const sendMsg = (value: string) => {
    if (disabled || !value.trim()) {
      return;
    }
    const option = Object.keys(stepOptions)
      .reduce((result: any[], item) => {
        result = result.concat(stepOptions[item]);
        return result;
      }, [])
      .find(item =>
        Object.keys(stepOptions).length === 1
          ? item.recommend === value
          : `${item.dataSetName || ''}${item.recommend}` === value
      );

    if (option && isSelect) {
      onSendMsg(option.recommend, option.dataSetIds);
    } else {
      onSendMsg(value.trim(), option?.dataSetId);
    }
  };

  const autoCompleteDropdownClass = classNames(styles.autoCompleteDropdown, {
    [styles.mobile]: isMobile,
    [styles.modelOptions]: modelOptions.length > 0,
  });

  const onSelect = (value: string) => {
    isSelect = true;
    if (modelOptions.length > 0) {
      const agent = agentList.find(item => value.includes(item.name));
      if (agent) {
        if (agent.id !== currentAgent?.id) {
          onSelectAgent(agent);
        }
        onInputMsgChange('');
      }
    } else {
      onInputMsgChange(value.replace(HOLDER_TAG, ''));
    }
    setOpen(false);
    setTimeout(() => {
      isSelect = false;
    }, 200);
  };

  const chatFooterClass = classNames(styles.chatFooter, {
    [styles.mobile]: isMobile,
  });

  const modelOptionNodes = modelOptions.map(model => {
    return (
      <Option key={model.id} value={`/${model.name} `} className={styles.searchOption}>
        {model.name}
      </Option>
    );
  });

  const associateOptionNodes = Object.keys(stepOptions).map(key => {
    return (
      <OptGroup key={key} label={key}>
        {stepOptions[key].map(option => {
          let optionValue =
            Object.keys(stepOptions).length === 1
              ? option.recommend
              : `${option.dataSetName || ''}${option.recommend}`;
          if (inputMsg[0] === '/') {
            const agent = agentList.find(item => inputMsg.includes(item.name));
            optionValue = agent ? `/${agent.name} ${option.recommend}` : optionValue;
          }
          return (
            <Option
              key={`${option.recommend}${option.dataSetName ? `_${option.dataSetName}` : ''}`}
              value={`${optionValue}${HOLDER_TAG}`}
              className={styles.searchOption}
            >
              <div className={styles.optionContent}>
                {option.schemaElementType && (
                  <Tag
                    className={styles.semanticType}
                    color={
                      option.schemaElementType === SemanticTypeEnum.DIMENSION ||
                      option.schemaElementType === SemanticTypeEnum.MODEL
                        ? 'blue'
                        : option.schemaElementType === SemanticTypeEnum.VALUE
                        ? 'geekblue'
                        : 'cyan'
                    }
                  >
                    {SEMANTIC_TYPE_MAP[option.schemaElementType] ||
                      option.schemaElementType ||
                      '维度'}
                  </Tag>
                )}
                {option.subRecommend}
              </div>
            </Option>
          );
        })}
      </OptGroup>
    );
  });

  const fixWidthBug = () => {
    setTimeout(() => {
      const dropdownDom = document.querySelector(
        '.' + styles.autoCompleteDropdown + ' .rc-virtual-list-holder-inner'
      );

      if (!dropdownDom) {
        fixWidthBug();
      } else {
        // 获取popoverDom样式
        const popoverDomStyle = window.getComputedStyle(dropdownDom);
        // 在获取popoverDom中增加样式 width: fit-content
        dropdownDom.setAttribute('style', `${popoverDomStyle.cssText};width: fit-content`);
        // 获取popoverDom的宽度
        const popoverDomWidth = dropdownDom.clientWidth;
        // 将popoverDom的宽度赋值给他的父元素
        const offset = 20; // 预增加20px的宽度，预留空间给虚拟渲染出来的元素
        dropdownDom.parentElement!.style.width = popoverDomWidth + offset + 'px';
      }
    });
  };

  useEffect(() => {
    if (modelOptionNodes.length || associateOptionNodes.length) fixWidthBug();
  }, [modelOptionNodes.length, associateOptionNodes.length]);

  const { isComposing } = useComposing(document.getElementById('chatInput'));
  const agentSelectorContent = (
    <div className={styles.agentSelectorContent}>
      {agentLoading ? (
        <Spin size="small" />
      ) : agentError ? (
        <div className={styles.agentState}>{agentError}</div>
      ) : agentList.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用助理" />
      ) : (
        agentList.map(agent => (
          <button
            type="button"
            key={agent.id}
            className={classNames(styles.agentOption, {
              [styles.agentOptionActive]: currentAgent?.id === agent.id,
            })}
            onClick={() => {
              onSelectAgent(agent);
              setAgentSelectorOpen(false);
            }}
          >
            <strong>{agent.name}</strong>
            {agent.description && <span>{agent.description}</span>}
          </button>
        ))
      )}
    </div>
  );

  return (
    <div className={chatFooterClass}>
      {isMobile && (
        <div className={styles.tools}>
          <button
            type="button"
            className={styles.toolItem}
            aria-label="历史对话"
            title="历史对话"
            onClick={onToggleHistoryVisible}
          >
            <IconFont type="icon-lishi" className={styles.toolIcon} />
            <span className={styles.toolLabel}>历史对话</span>
          </button>
          <button
            type="button"
            className={styles.toolItem}
            aria-label="智能助理"
            title="智能助理"
            onClick={onOpenAgents}
          >
            <IconFont type="icon-zhinengzhuli" className={styles.toolIcon} />
            <span className={styles.toolLabel}>智能助理</span>
          </button>
        </div>
      )}
      <div className={styles.composer}>
        <div className={styles.composerInputWrapper}>
          <AutoComplete
            className={styles.composerInput}
            placeholder={disabled ? '请先选择助理' : '请输入您的问题'}
            value={inputMsg}
            disabled={disabled}
            onChange={(value: string) => {
              onInputMsgChange(value);
            }}
            onSelect={onSelect}
            autoFocus={!isMobile && !disabled}
            ref={inputRef}
            id="chatInput"
            onKeyDown={e => {
              if (e.code === 'Enter' || e.code === 'NumpadEnter') {
                const chatInputEl: any = document.getElementById('chatInput');
                const agent = agentList.find(
                  item => chatInputEl.value[0] === '/' && chatInputEl.value.includes(item.name)
                );
                if (agent) {
                  onSelectAgent(agent);
                  onInputMsgChange('');
                  return;
                }
                if (!isSelect && !isComposing) {
                  sendMsg(chatInputEl.value);
                  setOpen(false);
                }
              }
            }}
            onFocus={() => {
              setFocused(true);
            }}
            onBlur={() => {
              setFocused(false);
            }}
            dropdownClassName={autoCompleteDropdownClass}
            listHeight={500}
            allowClear
            open={!disabled && open}
            defaultActiveFirstOption={false}
            getPopupContainer={triggerNode => triggerNode.parentNode}
          >
            {modelOptions.length > 0 ? modelOptionNodes : associateOptionNodes}
          </AutoComplete>
          <div className={styles.composerActions}>
            <Popover
              content={agentSelectorContent}
              title="选择助理"
              trigger="click"
              open={agentSelectorOpen}
              onOpenChange={setAgentSelectorOpen}
              placement="topLeft"
            >
              <button type="button" className={styles.agentSelector} aria-label="选择助理">
                <PlusOutlined />
                <span>选择助理</span>
              </button>
            </Popover>
            <span className={styles.agentStatus}>
              {currentAgent ? `【${currentAgent.name}】将与您对话` : '请选择助理后开始对话'}
            </span>
          </div>
          <button
            type="button"
            aria-label="发送"
            disabled={disabled || !inputMsg.trim()}
            className={classNames(styles.sendBtn, {
              [styles.sendBtnActive]: !disabled && inputMsg.trim().length > 0,
            })}
            onClick={() => {
              sendMsg(inputMsg);
            }}
          >
            <IconFont type="icon-ios-send" />
          </button>
        </div>
      </div>
    </div>
  );
};

export default forwardRef(ChatFooter);
