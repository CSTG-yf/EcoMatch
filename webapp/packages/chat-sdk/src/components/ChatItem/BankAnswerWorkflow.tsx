import {
  CheckCircleFilled,
  CheckCircleOutlined,
  CloseCircleFilled,
  CloseCircleOutlined,
  CopyOutlined,
  DownOutlined,
  LoadingOutlined,
  LockOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { Col, DatePicker, message, Row } from 'antd';
import classNames from 'classnames';
import { ReactNode, useState } from 'react';
import { CopyToClipboard } from 'react-copy-to-clipboard';
import dayjs, { Dayjs } from 'dayjs';
import quarterOfYear from 'dayjs/plugin/quarterOfYear';
import {
  ChatContextType,
  DateInfoType,
  EntityInfoType,
  FilterItemType,
  MsgDataType,
  ParseTimeCostType,
} from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import { isMobile } from '../../utils/utils';
import FilterItem from './FilterItem';
import { QueryWorkflowStage, WORKFLOW_STAGE_TEXT } from './workflow';
import {
  confidenceLevel,
  filterSummaries,
  semanticNames,
  sqlValidationLabel,
} from './trustModel';
import {
  COMPARISON_LABELS,
  INTENT_LABELS,
  mappingConfidenceText,
  modelRequirements,
  requirementRangeText,
  resultPresentation,
  schemaMappingText,
  sqlVersions,
  stageStatuses,
  stageTimeCost,
  AnswerCardStageKey,
} from './answerCardModel';

import 'dayjs/locale/zh-cn';

dayjs.extend(quarterOfYear);
dayjs.locale('zh-cn');

const { RangePicker } = DatePicker;

type RangeValue = [Dayjs, Dayjs];
type RangeKeys = '近7日' | '近14日' | '近30日' | '本周' | '本月' | '上月' | '本季度' | '本年';

type Props = {
  question: string;
  parseInfo?: ChatContextType;
  parseTimeCost?: ParseTimeCostType;
  /** 前端实测的全链路墙钟耗时（历史消息没有，回退用 parseTime） */
  totalTimeCost?: number;
  data?: MsgDataType;
  workflowStage: QueryWorkflowStage;
  parseTip?: string;
  isSimpleMode?: boolean;
  dimensionFilters?: FilterItemType[];
  dateInfo?: DateInfoType;
  entityInfo?: EntityInfoType;
  agentId?: number;
  integrateSystem?: string;
  onFiltersChange?: (filters: FilterItemType[]) => void;
  onSwitchEntity?: (entityId: string) => void;
  onDateInfoChange?: (dates: [Dayjs | null, Dayjs | null] | null) => void;
  handlePresetClick?: (range: RangeValue) => void;
};

type DetailItem = {
  label: string;
  value: ReactNode;
  lowConfidence?: boolean;
};

const WORKFLOW_OPEN_STATES: QueryWorkflowStage[] = [
  'parsing',
  'clarifying',
  'executing',
  'explaining',
  'failed',
  'forbidden',
  'timeout',
];

const DATE_PRESETS: Record<RangeKeys, RangeValue> = {
  近7日: [dayjs().subtract(7, 'day'), dayjs()],
  近14日: [dayjs().subtract(14, 'day'), dayjs()],
  近30日: [dayjs().subtract(30, 'day'), dayjs()],
  本周: [dayjs().startOf('week'), dayjs().endOf('week')],
  本月: [dayjs().startOf('month'), dayjs().endOf('month')],
  上月: [
    dayjs().subtract(1, 'month').startOf('month'),
    dayjs().subtract(1, 'month').endOf('month'),
  ],
  本季度: [dayjs().startOf('quarter'), dayjs().endOf('quarter')],
  本年: [dayjs().startOf('year'), dayjs().endOf('year')],
};

const Stage: React.FC<{
  prefixCls: string;
  title: string;
  status: string;
  timeCost?: number;
  state?: 'done' | 'active' | 'pending' | 'failed';
  defaultOpen?: boolean;
  children?: ReactNode;
}> = ({ prefixCls, title, status, timeCost, state = 'pending', defaultOpen = true, children }) => {
  const [open, setOpen] = useState(defaultOpen);
  const failed = state === 'failed';
  const active = state === 'active';
  const bullet =
    state === 'done' ? (
      <CheckCircleFilled className={`${prefixCls}-stage-bullet ${prefixCls}-stage-bullet-done`} />
    ) : failed ? (
      <CloseCircleFilled className={`${prefixCls}-stage-bullet ${prefixCls}-stage-bullet-failed`} />
    ) : active ? (
      <LoadingOutlined
        spin
        className={`${prefixCls}-stage-bullet ${prefixCls}-stage-bullet-active`}
      />
    ) : (
      <span className={`${prefixCls}-stage-bullet ${prefixCls}-stage-bullet-pending`} />
    );
  return (
    <details
      className={classNames(`${prefixCls}-stage`, {
        [`${prefixCls}-stage-failed`]: failed,
      })}
      open={open}
    >
      {bullet}
      <summary
        className={`${prefixCls}-stage-title`}
        onClick={event => {
          event.preventDefault();
          setOpen(!open);
        }}
      >
        <strong>{title}</strong>
        <span
          className={classNames(`${prefixCls}-stage-status`, {
            [`${prefixCls}-stage-status-active`]: active,
            [`${prefixCls}-stage-status-failed`]: failed,
          })}
        >
          {status}
          {timeCost !== undefined && ` · ${timeCost}ms`}
        </span>
      </summary>
      {open && children && <div className={`${prefixCls}-stage-content`}>{children}</div>}
    </details>
  );
};

const DetailGrid: React.FC<{ prefixCls: string; items: DetailItem[] }> = ({ prefixCls, items }) => {
  if (!items.length) {
    return null;
  }
  // 末行只剩 1 个数据时整行通铺，不再切分列
  const singleCellLastRow = items.length % 3 === 1;
  return (
    <dl className={`${prefixCls}-detail-grid`}>
      {items.map((item, index) => (
        <div
          className={`${prefixCls}-detail`}
          key={item.label}
          style={
            singleCellLastRow && index === items.length - 1 ? { gridColumn: '1 / -1' } : undefined
          }
        >
          <dt>{item.label}</dt>
          <dd className={item.lowConfidence ? `${prefixCls}-low-confidence` : undefined}>
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
};

const BankAnswerWorkflow: React.FC<Props> = ({
  question,
  parseInfo,
  parseTimeCost,
  totalTimeCost,
  data,
  workflowStage,
  parseTip,
  isSimpleMode,
  dimensionFilters,
  dateInfo,
  entityInfo,
  agentId,
  integrateSystem,
  onFiltersChange,
  onSwitchEntity,
  onDateInfoChange,
  handlePresetClick,
}) => {
  const [manualOpen, setManualOpen] = useState<boolean | null>(null);
  const [activeSqlTab, setActiveSqlTab] = useState<string>('');

  // 精简模式：不展示问数过程，直接展示结果
  if (workflowStage === 'idle' || isSimpleMode) {
    return null;
  }

  const prefixCls = `${PREFIX_CLS}-item`;
  const parseFailed = !!parseTip;
  const statuses = stageStatuses(workflowStage, parseFailed);
  const workflowOpen = manualOpen ?? WORKFLOW_OPEN_STATES.includes(workflowStage);

  const requirements = modelRequirements(parseInfo);
  const confidence = mappingConfidenceText(parseInfo);
  const confidenceMeta = confidenceLevel(confidence);
  const validation = sqlValidationLabel(parseInfo);
  const metrics = semanticNames(parseInfo?.metrics);
  const dimensions = semanticNames(parseInfo?.dimensions);
  const filters = filterSummaries(parseInfo);
  const versions = sqlVersions(parseInfo);
  const schemaText = schemaMappingText(parseInfo?.elementMatches);

  const stageIcon =
    workflowStage === 'completed' ? (
      <CheckCircleOutlined />
    ) : workflowStage === 'clarifying' ? (
      <QuestionCircleOutlined />
    ) : workflowStage === 'forbidden' ? (
      <LockOutlined />
    ) : workflowStage === 'failed' || workflowStage === 'timeout' ? (
      <CloseCircleOutlined />
    ) : (
      <LoadingOutlined spin />
    );

  // ---- 阶段 1：需求理解 ----
  const recognizedObjects = [
    metrics.length || requirements?.metricCodes?.length ? '指标' : '',
    requirements?.organizationCodes?.length || parseInfo?.entityInfo?.dimensions?.length
      ? '机构'
      : '',
    requirements?.time?.startDate || requirements?.time?.endDate || parseInfo?.dateInfo?.startDate
      ? '时间'
      : '',
  ]
    .filter(Boolean)
    .join('、');
  const understandFacts: DetailItem[] = [];
  if (requirements?.intent) {
    understandFacts.push({
      label: '查询意图',
      value: INTENT_LABELS[requirements.intent] || requirements.intent,
    });
  }
  if (requirements?.action === 'EXECUTE') {
    understandFacts.push({ label: '需求合同', value: '已校验' });
  }
  if (requirements?.answerFactTypes?.length) {
    understandFacts.push({ label: '回答事实', value: requirements.answerFactTypes.join('、') });
  }
  const understandDetails: DetailItem[] = [];
  if (recognizedObjects) {
    understandDetails.push({ label: '识别对象', value: recognizedObjects });
  }
  if (requirements?.intent) {
    understandDetails.push({
      label: '问题类型',
      value: INTENT_LABELS[requirements.intent] || requirements.intent,
    });
  }
  if (requirements?.action === 'EXECUTE') {
    understandDetails.push({ label: '需求状态', value: '可执行' });
  }

  // ---- 阶段 2：口径与条件 ----
  const caliberDetails: DetailItem[] = [];
  if (parseInfo?.dataSet?.name) {
    caliberDetails.push({ label: '数据集', value: parseInfo.dataSet.name });
  }
  if (parseInfo?.queryType) {
    caliberDetails.push({
      label: '查询模式',
      value: parseInfo.queryType === 'DETAIL' ? '明细模式' : '聚合模式',
    });
  }
  if (confidence !== undefined) {
    caliberDetails.push({
      label: '口径匹配',
      value: `${(confidence * 100).toFixed(1)}% · ${confidenceMeta.label}`,
      lowConfidence: confidence < 0.75,
    });
  }
  if (metrics.length) {
    caliberDetails.push({
      label: '指标',
      value: parseInfo!.metrics
        .map(item => [item.bizName, item.name].filter(Boolean).join(' · '))
        .join('、'),
    });
  } else if (requirements?.metricCodes?.length) {
    // LLM 解析路径下 metrics 为空，从 Schema 映射证据回填指标名称
    caliberDetails.push({
      label: '指标',
      value: requirements.metricCodes
        .map(code => {
          const match = parseInfo?.elementMatches?.find(
            item =>
              item.element?.type === 'METRIC' &&
              (item.element?.bizName || '').toUpperCase() === code.toUpperCase()
          );
          const name = match?.element?.name;
          return name && name !== code ? `${code} · ${name}` : code;
        })
        .join('、'),
    });
  }
  if (requirements) {
    const entityName =
      parseInfo?.elementMatches?.find(match => match.element?.type === 'ID')?.element?.name ||
      parseInfo?.elementMatches?.find(
        match =>
          (match.element?.name || '').includes('机构') &&
          match.detectWord &&
          match.detectWord !== match.element?.name
      )?.detectWord;
    const codes = requirements.organizationCodes || [];
    caliberDetails.push({
      label: '机构',
      value: codes.length
        ? codes
            .map(code => (codes.length === 1 && entityName ? `${code} · ${entityName}` : code))
            .join('、')
        : '全省范围',
    });
  }
  const timeRange =
    requirementRangeText(requirements?.time) ||
    (parseInfo?.dateInfo?.startDate
      ? parseInfo.dateInfo.startDate === parseInfo.dateInfo.endDate
        ? parseInfo.dateInfo.startDate
        : `${parseInfo.dateInfo.startDate} 至 ${parseInfo.dateInfo.endDate}`
      : '');
  if (timeRange) {
    caliberDetails.push({ label: '时间范围', value: timeRange });
  }
  if (requirements?.time?.comparison) {
    caliberDetails.push({
      label: '比较口径',
      value:
        COMPARISON_LABELS[requirements.time.comparison] || requirements.time.comparison,
    });
  }
  if (filters.length) {
    caliberDetails.push({ label: '筛选条件', value: filters.join('；') });
  }
  if (dimensions.length) {
    caliberDetails.push({ label: '下钻维度', value: dimensions.map(item => item.name).join('、') });
  }
  [...metrics, ...dimensions]
    .filter(item => item.description)
    .forEach(item => {
      caliberDetails.push({ label: '口径定义', value: `${item.name}：${item.description}` });
    });

  // 可编辑筛选区：日期范围 + 维度筛选修改 + 实体切换（原 ParseTip 的交互能力）
  const nativeQuery = parseInfo?.nativeQuery;
  const entityAlias = parseInfo?.entity?.alias?.[0]?.split('.')?.[0];
  const editableFilters = dimensionFilters || [];
  const { startDate, endDate } = dateInfo || {};
  const showFilterEditor =
    !isSimpleMode &&
    (!!dateInfo?.startDate || editableFilters.length > 0) &&
    !!(onDateInfoChange || onFiltersChange);
  const filterEditor = showFilterEditor ? (
    <div className={`${prefixCls}-workflow-filter-editor`}>
      <div className={`${prefixCls}-tip-item-filter-content`}>
        {!!dateInfo && (
          <div
            className={classNames(`${prefixCls}-tip-item-option`, {
              [`${prefixCls}-mobile-tip-item-option`]: isMobile,
            })}
          >
            <span className={`${prefixCls}-tip-item-filter-name`}>数据时间：</span>
            {nativeQuery ? (
              <span className={`${prefixCls}-tip-item-value`}>
                {startDate === endDate ? startDate : `${startDate} ~ ${endDate}`}
              </span>
            ) : (
              <RangePicker
                value={startDate && endDate ? [dayjs(startDate), dayjs(endDate)] : null}
                onChange={onDateInfoChange}
                format="YYYY-MM-DD"
                renderExtraFooter={() => (
                  <Row gutter={[28, 28]}>
                    {Object.keys(DATE_PRESETS).map(key => (
                      <Col key={key}>
                        <button
                          type="button"
                          className={`${prefixCls}-date-preset`}
                          onClick={() => handlePresetClick?.(DATE_PRESETS[key as RangeKeys])}
                        >
                          {key}
                        </button>
                      </Col>
                    ))}
                  </Row>
                )}
              />
            )}
          </div>
        )}
        {editableFilters.map((filter: any, index: number) => (
          <FilterItem
            key={`${filter.name}_${index}`}
            modelId={parseInfo?.modelId!}
            filters={editableFilters}
            filter={filter}
            index={index}
            chatContext={parseInfo!}
            entityAlias={entityAlias}
            agentId={agentId}
            integrateSystem={integrateSystem}
            onFiltersChange={onFiltersChange!}
            onSwitchEntity={onSwitchEntity!}
          />
        ))}
      </div>
    </div>
  ) : null;

  // ---- 阶段 3：SQL 生成与校验 ----
  const trace = Array.isArray(parseInfo?.properties?.['bank.nl2sql.trace'])
    ? (parseInfo?.properties['bank.nl2sql.trace'] as any[])
    : [];
  const succeededAttempt = trace.filter(event => event.action === 'SUCCEEDED').pop();
  const noteParts = [
    succeededAttempt ? `第 ${succeededAttempt.attempt} 次执行计划通过` : '',
    parseInfo?.sqlEvaluation?.isValidated !== undefined ? validation.label : '',
    parseInfo?.sqlEvaluation?.features?.length
      ? parseInfo.sqlEvaluation.features
          .map(
            feature =>
              ({
                SINGLE_TABLE: '单表',
                MULTI_TABLE: '多表关联',
                NESTED_QUERY: '嵌套查询',
                WINDOW_FUNCTION: '窗口函数',
                AGGREGATION: '聚合',
                TOP_N: '排名',
                YOY: '同比',
                MOM: '环比',
                CROSS_ORGANIZATION: '跨机构',
              }[feature] || feature)
          )
          .join('、')
      : '',
  ].filter(Boolean);

  const sqlTabs = [
    ...(schemaText ? [{ key: 'schemaMapping', label: 'Schema 映射', sql: schemaText }] : []),
    ...versions,
  ];
  const activeTab = sqlTabs.some(item => item.key === activeSqlTab)
    ? activeSqlTab
    : sqlTabs.find(item => item.key === 'querySQL')?.key || sqlTabs[0]?.key || '';
  const activeSql = sqlTabs.find(item => item.key === activeTab)?.sql || '';

  // ---- 阶段 4：数据查询与解释 ----
  const queryFacts: DetailItem[] = [];
  if (data) {
    queryFacts.push({
      label: '访问状态',
      value: data.queryAuthorization?.message || '授权通过',
    });
    if (Array.isArray(data.queryResults)) {
      queryFacts.push({ label: '查询结果', value: `${data.queryResults.length} 条` });
    }
    queryFacts.push({ label: '结果呈现', value: resultPresentation(data) });
    queryFacts.push({
      label: '自然总结',
      value: data.textSummary || data.businessExplanation?.summary ? '已生成' : '未生成',
    });
  }
  const queryDetails: DetailItem[] = [];
  if (data?.queryColumns?.length) {
    queryDetails.push({
      label: '返回字段',
      value: data.queryColumns.map(column => column.name || column.nameEn).join('、'),
    });
  }
  if (data?.queryState) {
    queryDetails.push({
      label: '结果状态',
      value: data.queryState === 'SUCCESS' ? '查询成功' : data.queryState,
    });
  }

  const renderStage = (
    key: AnswerCardStageKey,
    index: number,
    title: string,
    content?: ReactNode
  ) => {
    const status = statuses[key];
    const timeCost = stageTimeCost(key, parseTimeCost, data?.queryTimeCost);
    return (
      <Stage
        key={key}
        prefixCls={prefixCls}
        title={`${index}. ${title}`}
        status={status.label}
        timeCost={status.state === 'done' ? timeCost : undefined}
        state={status.state}
      >
        {content}
      </Stage>
    );
  };

  // 总耗时优先用前端实测墙钟（含解析+执行+解释全链路），历史消息回退到后端 parseTime
  const totalCost = totalTimeCost ?? parseTimeCost?.parseTime;
  const showTotalCost = totalCost !== undefined && !['idle', 'parsing'].includes(workflowStage);
  const formatTotalCost = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`);

  return (
    <section className={`${prefixCls}-answer-workflow`} aria-label="查询处理流程">
      <details className={`${prefixCls}-workflow`} open={workflowOpen}>
        <summary
          className={classNames(
            `${prefixCls}-workflow-summary`,
            `${prefixCls}-workflow-summary-${workflowStage}`
          )}
          onClick={event => {
            event.preventDefault();
            setManualOpen(!workflowOpen);
          }}
          role="status"
          aria-live="polite"
        >
          <span className={`${prefixCls}-workflow-title`}>
            {stageIcon}
            <span>{WORKFLOW_STAGE_TEXT[workflowStage]}</span>
          </span>
          <span className={`${prefixCls}-workflow-side`}>
            {showTotalCost && (
              <span className={`${prefixCls}-workflow-parsetime`}>
                总耗时 {formatTotalCost(totalCost!)}
              </span>
            )}
            <DownOutlined
              className={classNames(`${prefixCls}-workflow-chevron`, {
                [`${prefixCls}-workflow-chevron-open`]: workflowOpen,
              })}
            />
          </span>
        </summary>
        {workflowOpen && (
          <div className={`${prefixCls}-pipeline`}>
            {renderStage(
              'understand',
              1,
              '需求理解',
              <>
                <p className={`${prefixCls}-workflow-question`}>{question}</p>
                {understandFacts.length > 0 && (
                  <div className={`${prefixCls}-inline-facts`}>
                    {understandFacts.map(item => (
                      <span
                        key={item.label}
                        className={
                          item.label === '需求合同' ? `${prefixCls}-fact-contract` : undefined
                        }
                      >
                        <b>{item.label}</b>
                        {item.value}
                      </span>
                    ))}
                  </div>
                )}
                <DetailGrid prefixCls={prefixCls} items={understandDetails} />
                {parseTip && <p className={`${prefixCls}-stage-note`}>{parseTip}</p>}
              </>
            )}
            {renderStage(
              'caliber',
              2,
              '口径与条件',
              <>
                <DetailGrid prefixCls={prefixCls} items={caliberDetails} />
                {filterEditor}
              </>
            )}
            {renderStage(
              'sql',
              3,
              'SQL 生成与校验',
              <>
                {noteParts.length > 0 && (
                  <p className={`${prefixCls}-stage-note`}>
                    <b>{noteParts[0]}</b>
                    {noteParts.length > 1 && ` · ${noteParts.slice(1).join(' · ')}`}
                  </p>
                )}
                {sqlTabs.length > 0 && (
                  <div className={`${prefixCls}-stage-more`}>
                    <div className={`${prefixCls}-sql-tabs`} role="tablist" aria-label="SQL版本">
                      {sqlTabs.map(item => (
                        <button
                          key={item.key}
                          type="button"
                          role="tab"
                          aria-selected={item.key === activeTab}
                          className={classNames(`${prefixCls}-sql-tab`, {
                            [`${prefixCls}-sql-tab-active`]: item.key === activeTab,
                          })}
                          onClick={() => setActiveSqlTab(item.key)}
                        >
                          {item.label}
                        </button>
                      ))}
                    </div>
                    <div className={`${prefixCls}-sql-panel-wrap`}>
                      <pre className={`${prefixCls}-sql-panel`}>{activeSql}</pre>
                      {activeSql && (
                        <CopyToClipboard
                          text={activeSql}
                          onCopy={(_, result) =>
                            result
                              ? message.success('SQL 已复制', 1)
                              : message.error('复制失败，请手动复制', 1)
                          }
                        >
                          <button
                            type="button"
                            className={`${prefixCls}-sql-copy`}
                            title="复制当前 SQL"
                            aria-label="复制当前 SQL"
                          >
                            <CopyOutlined />
                          </button>
                        </CopyToClipboard>
                      )}
                    </div>
                  </div>
                )}
              </>
            )}
            {renderStage(
              'query',
              4,
              '数据查询与解释',
              <>
                {queryFacts.length > 0 && (
                  <div className={`${prefixCls}-inline-facts`}>
                    {queryFacts.map(item => (
                      <span key={item.label}>
                        <b>{item.label}</b>
                        {item.value}
                      </span>
                    ))}
                  </div>
                )}
                <DetailGrid prefixCls={prefixCls} items={queryDetails} />
              </>
            )}
          </div>
        )}
      </details>
    </section>
  );
};

export default BankAnswerWorkflow;
