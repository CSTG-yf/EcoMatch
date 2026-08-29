import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  EditOutlined,
  EyeOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { Alert, Collapse, Descriptions, Modal, Space, Table, Tag, Timeline } from 'antd';
import { BankPlanTraceEventType, ChatContextType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import { isMobile } from '../../utils/utils';
import { QueryWorkflowStage } from './workflow';
import {
  confidenceLevel,
  correctionRecords,
  mappingConfidence,
  sqlValidationLabel,
} from './trustModel';
import { sqlVersions } from './answerCardModel';

type Props = {
  open: boolean;
  question: string;
  parseInfo?: ChatContextType;
  workflowStage: QueryWorkflowStage;
  parseError?: string;
  executeError?: string;
  llmReq?: any;
  llmResp?: any;
  onClose: () => void;
};

const ERROR_TYPE_LABELS: Record<string, string> = {
  NONE: '无错误',
  MAPPING_ERROR: '映射错误',
  DEFINITION_ERROR: '口径错误',
  JOIN_ERROR: '关联错误',
  FILTER_ERROR: '筛选错误',
  SYNTAX_ERROR: '语法错误',
  EXECUTION_ERROR: '执行错误',
};

const FEATURE_LABELS: Record<string, string> = {
  SINGLE_TABLE: '单表',
  MULTI_TABLE: '多表关联',
  NESTED_QUERY: '嵌套查询',
  WINDOW_FUNCTION: '窗口函数',
  AGGREGATION: '聚合',
  TOP_N: '排名',
  YOY: '同比',
  MOM: '环比',
  CROSS_ORGANIZATION: '跨机构',
};

const BANK_STAGE_LABELS: Record<string, string> = {
  PLAN_SCHEMA: '计划结构',
  PLAN_SEMANTIC: '计划语义',
  COMPILE: '查询编译',
  SQL_SAFETY: 'SQL 安全',
  DATABASE_PREPARE: '数据库预检查',
  DATABASE_EXECUTE: '数据库执行',
  RESULT_SEMANTIC: '结果语义',
};

const bankPlanTrace = (properties?: Record<string, any>): BankPlanTraceEventType[] => {
  const trace = properties?.['bank.nl2sql.trace'];
  return Array.isArray(trace) ? trace : [];
};

const planSummary = (event: BankPlanTraceEventType) => {
  const summary = event.planSummary;
  if (!summary) {
    return '未提供计划摘要';
  }
  return [
    summary.intent ? `意图 ${summary.intent}` : '',
    summary.metrics?.length ? `指标 ${summary.metrics.join('、')}` : '',
    summary.organizations?.length ? `机构 ${summary.organizations.join('、')}` : '',
    summary.timeGranularity ? `粒度 ${summary.timeGranularity}` : '',
    summary.timeComparison && summary.timeComparison !== 'NONE'
      ? `对比 ${summary.timeComparison}`
      : '',
    summary.calculationType ? `计算 ${summary.calculationType}` : '',
    summary.outputColumns?.length ? `输出 ${summary.outputColumns.join('、')}` : '',
  ]
    .filter(Boolean)
    .join('；');
};

const traceColor = (action: BankPlanTraceEventType['action']) => {
  if (action === 'SUCCEEDED') {
    return 'green';
  }
  if (action === 'REPAIRING') {
    return 'blue';
  }
  return 'red';
};

const diagnosticProperties = (properties?: Record<string, any>) =>
  Object.entries(properties || {})
    .filter(
      ([key]) =>
        (key.startsWith('bank.nl2sql.') && key !== 'bank.nl2sql.trace') ||
        key === 'complexSqlFeatures'
    )
    .map(([key, value]) => ({ key, value: Array.isArray(value) ? value.join('、') : `${value}` }));

const TechnicalDiagnosticsModal: React.FC<Props> = ({
  open,
  question,
  parseInfo,
  workflowStage,
  parseError,
  executeError,
  llmReq,
  llmResp,
  onClose,
}) => {
  if (!parseInfo) {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const confidence = mappingConfidence(parseInfo.elementMatches);
  const confidenceMeta = confidenceLevel(confidence);
  const validation = sqlValidationLabel(parseInfo);
  const records = correctionRecords(parseInfo.sqlInfo, parseInfo.sqlEvaluation?.isValidated);
  const properties = diagnosticProperties(parseInfo.properties);
  const toolTrace = bankPlanTrace(parseInfo.properties);
  const versions = sqlVersions(parseInfo);
  const exceptionText = parseError || executeError || parseInfo.sqlEvaluation?.validateMsg;

  const { schema, terms, priorExts } = llmReq || {};
  const fewShots = (Object.values(llmResp?.sqlRespMap || {})[0] as any)?.fewShots || [];

  return (
    <Modal
      className={`${prefixCls}-trust-modal`}
      title="技术详情"
      width={isMobile ? '100%' : 760}
      open={open}
      footer={null}
      centered
      destroyOnClose
      onCancel={onClose}
      styles={{ body: { maxHeight: '72vh', overflowY: 'auto' } }}
    >
      <Descriptions size="small" bordered column={isMobile ? 1 : 2}>
        <Descriptions.Item label="当前阶段">{workflowStage}</Descriptions.Item>
        <Descriptions.Item label="查询模式">{parseInfo.queryMode || '-'}</Descriptions.Item>
        <Descriptions.Item label="映射置信度">
          {confidence === undefined
            ? '未提供'
            : `${(confidence * 100).toFixed(1)}% · ${confidenceMeta.label}`}
        </Descriptions.Item>
        <Descriptions.Item label="语义覆盖率">
          {typeof parseInfo.sqlEvaluation?.semanticScore === 'number'
            ? `${(parseInfo.sqlEvaluation.semanticScore * 100).toFixed(1)}%`
            : '未提供'}
        </Descriptions.Item>
        <Descriptions.Item label="校验状态">{validation.label}</Descriptions.Item>
        <Descriptions.Item label="错误分类">
          {ERROR_TYPE_LABELS[parseInfo.sqlEvaluation?.errorType || 'NONE'] ||
            parseInfo.sqlEvaluation?.errorType ||
            '-'}
        </Descriptions.Item>
      </Descriptions>

      {exceptionText && (
        <Alert
          className={`${prefixCls}-trust-diagnostic-alert`}
          type="error"
          showIcon
          message="当前阶段诊断"
          description={exceptionText}
        />
      )}

      <h3>转换与修正记录</h3>
      <Timeline
        items={records.map(record => ({
          color:
            record.status === 'failed'
              ? 'red'
              : record.status === 'changed'
              ? 'blue'
              : record.status === 'completed'
              ? 'green'
              : 'gray',
          dot:
            record.status === 'failed' ? (
              <CloseCircleOutlined />
            ) : record.status === 'changed' ? (
              <EditOutlined />
            ) : record.status === 'completed' ? (
              <CheckCircleOutlined />
            ) : (
              <LoadingOutlined />
            ),
          children: (
            <div>
              <strong>{record.label}</strong>
              <div>{record.detail}</div>
            </div>
          ),
        }))}
      />

      {parseInfo.elementMatches?.length > 0 && (
        <>
          <h3>Schema 映射证据</h3>
          <Table
            size="small"
            pagination={false}
            rowKey={record =>
              `${record.detectWord || record.word || 'match'}-${
                record.element?.id ||
                record.element?.name ||
                record.element?.bizName ||
                record.element?.type ||
                'element'
              }-${record.offset ?? 'no-offset'}`
            }
            dataSource={parseInfo.elementMatches}
            columns={[
              {
                title: '识别文本',
                dataIndex: 'detectWord',
                render: (_, record) => record.detectWord || record.word || '-',
              },
              {
                title: '映射对象',
                render: (_, record) =>
                  `${record.element?.type || '-'} / ${
                    record.element?.name || record.element?.bizName || '-'
                  }`,
              },
              {
                title: '相似度',
                dataIndex: 'similarity',
                render: value =>
                  typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '-',
              },
              {
                title: '来源',
                render: (_, record) =>
                  record.inherited ? '上下文继承' : record.llmMatched ? 'LLM 映射' : '规则映射',
              },
            ]}
          />
        </>
      )}

      {(parseInfo.sqlEvaluation?.features?.length || 0) > 0 && (
        <>
          <h3>复杂 SQL 特征</h3>
          <Space wrap>
            {parseInfo.sqlEvaluation?.features?.map(feature => (
              <Tag key={feature}>{FEATURE_LABELS[feature] || feature}</Tag>
            ))}
          </Space>
        </>
      )}

      {toolTrace.length > 0 && (
        <>
          <h3>工具执行记录</h3>
          <Timeline
            items={toolTrace.map(event => ({
              color: traceColor(event.action),
              dot:
                event.action === 'SUCCEEDED' ? (
                  <CheckCircleOutlined />
                ) : event.action === 'REPAIRING' ? (
                  <EditOutlined />
                ) : (
                  <CloseCircleOutlined />
                ),
              children: (
                <div className={`${prefixCls}-trust-tool-attempt`}>
                  <Space wrap size={6}>
                    <strong>第 {event.attempt} 次计划</strong>
                    <Tag color={traceColor(event.action)}>{event.action}</Tag>
                    {event.errorCode && <Tag>{event.errorCode}</Tag>}
                  </Space>
                  <div>{event.actionMessage}</div>
                  <div className={`${prefixCls}-trust-tool-plan`}>{planSummary(event)}</div>
                  {(event.stageResults?.length || 0) > 0 && (
                    <Space wrap size={[6, 6]}>
                      {event.stageResults?.map((stage, index) => (
                        <Tag
                          key={`${event.attempt}-${stage.stage}-${index}`}
                          color={stage.status === 'SUCCEEDED' ? 'success' : 'error'}
                        >
                          {BANK_STAGE_LABELS[stage.stage] || stage.stage}：{stage.message}
                        </Tag>
                      ))}
                    </Space>
                  )}
                </div>
              ),
            }))}
          />
        </>
      )}

      {(schema || terms?.length > 0 || priorExts || fewShots.length > 0) && <h3>工具与生成上下文</h3>}
      {schema && (
        <Collapse
          className={`${prefixCls}-diagnostic-collapse`}
          items={[
            {
              key: 'schema-context',
              label: 'Schema 映射上下文',
              children: (
                <Descriptions size="small" column={1}>
                  {schema.fieldNameList?.length > 0 && (
                    <Descriptions.Item label="名称">
                      {schema.fieldNameList.join('、')}
                    </Descriptions.Item>
                  )}
                  {schema.values?.length > 0 && (
                    <Descriptions.Item label="取值">
                      {schema.values
                        .map((item: any) => `${item.fieldName}: ${item.fieldValue}`)
                        .join('、')}
                    </Descriptions.Item>
                  )}
                  {priorExts && <Descriptions.Item label="附加">{priorExts}</Descriptions.Item>}
                  {terms?.length > 0 && (
                    <Descriptions.Item label="术语">
                      {terms
                        .map(
                          (item: any) =>
                            `${item.name}${
                              item.alias?.length > 0 ? `(${item.alias.join(',')})` : ''
                            }: ${item.description}`
                        )
                        .join('、')}
                    </Descriptions.Item>
                  )}
                </Descriptions>
              ),
            },
          ]}
        />
      )}
      {fewShots.length > 0 && (
        <Collapse
          className={`${prefixCls}-diagnostic-collapse`}
          items={[
            {
              key: 'few-shots',
              label: 'Few-shot 示例',
              children: (
                <Descriptions size="small" column={1}>
                  {fewShots.map((item: any, index: number) => (
                    <Descriptions.Item key={index} label={`示例${index + 1}`}>
                      {item.question}
                    </Descriptions.Item>
                  ))}
                </Descriptions>
              ),
            },
          ]}
        />
      )}

      {properties.length > 0 && (
        <>
          <h3>候选诊断</h3>
          <Descriptions size="small" bordered column={1}>
            {properties.map(item => (
              <Descriptions.Item key={item.key} label={item.key}>
                {item.value}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </>
      )}

      {versions.length > 0 && (
        <>
          <h3>SQL 版本</h3>
          <Collapse
            items={versions.map(item => ({
              key: item.key,
              label: item.label,
              children: (
                <pre className={`${prefixCls}-trust-sql`}>
                  <code>{item.sql}</code>
                </pre>
              ),
              extra: <EyeOutlined />,
            }))}
          />
        </>
      )}

      <h3>异常日志</h3>
      {exceptionText ? (
        <pre className={`${prefixCls}-trust-sql`}>
          <code>{exceptionText}</code>
        </pre>
      ) : (
        <p className={`${prefixCls}-diagnostic-note`}>本次查询无异常。</p>
      )}
    </Modal>
  );
};

export default TechnicalDiagnosticsModal;
