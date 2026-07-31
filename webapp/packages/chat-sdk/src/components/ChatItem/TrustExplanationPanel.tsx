import {
  BugOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  EditOutlined,
  EyeOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Badge,
  Button,
  Collapse,
  Descriptions,
  Drawer,
  Progress,
  Space,
  Steps,
  Table,
  Tag,
  Timeline,
} from 'antd';
import { useState } from 'react';
import { ChatContextType } from '../../common/type';
import { PREFIX_CLS } from '../../common/constants';
import { isMobile } from '../../utils/utils';
import { QueryWorkflowStage } from './workflow';
import {
  confidenceLevel,
  correctionRecords,
  filterSummaries,
  mappingConfidence,
  semanticNames,
  sqlValidationLabel,
} from './trustModel';

type Props = {
  question: string;
  parseInfo?: ChatContextType;
  workflowStage: QueryWorkflowStage;
  isDeveloper?: boolean;
  parseError?: string;
  executeError?: string;
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
};

const diagnosticProperties = (properties?: Record<string, any>) =>
  Object.entries(properties || {})
    .filter(([key]) => key.startsWith('bank.nl2sql.') || key === 'complexSqlFeatures')
    .map(([key, value]) => ({ key, value: Array.isArray(value) ? value.join('、') : `${value}` }));

const TrustExplanationPanel: React.FC<Props> = ({
  question,
  parseInfo,
  workflowStage,
  isDeveloper,
  parseError,
  executeError,
}) => {
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  if (
    !parseInfo ||
    (!parseInfo.metrics?.length && !parseInfo.dimensions?.length && !parseInfo.sqlInfo)
  ) {
    return null;
  }
  const prefixCls = `${PREFIX_CLS}-item`;
  const confidence = mappingConfidence(parseInfo.elementMatches);
  const confidenceMeta = confidenceLevel(confidence);
  const validation = sqlValidationLabel(parseInfo);
  const metrics = semanticNames(parseInfo.metrics);
  const dimensions = semanticNames(parseInfo.dimensions);
  const filters = filterSummaries(parseInfo);
  const records = correctionRecords(parseInfo.sqlInfo, parseInfo.sqlEvaluation?.isValidated);
  const properties = diagnosticProperties(parseInfo.properties);
  const sqlItems = [
    { key: 'parsed', label: '生成 S2SQL', sql: parseInfo.sqlInfo?.parsedS2SQL },
    { key: 'corrected', label: '修正 S2SQL', sql: parseInfo.sqlInfo?.correctedS2SQL },
    { key: 'query', label: '执行 SQL', sql: parseInfo.sqlInfo?.querySQL },
    { key: 'optimized', label: '优化后 SQL', sql: parseInfo.sqlInfo?.correctedQuerySQL },
  ].filter(item => item.sql);

  const conditionText = filters.length ? filters.join('；') : '未设置额外筛选';
  const semanticText = [
    metrics.length ? `指标：${metrics.map(item => item.name).join('、')}` : '',
    dimensions.length ? `维度：${dimensions.map(item => item.name).join('、')}` : '',
  ]
    .filter(Boolean)
    .join('；');

  return (
    <section className={`${prefixCls}-trust`} aria-label="查询可信度">
      <div className={`${prefixCls}-trust-heading`}>
        <strong>查询理解与可信度</strong>
        {confidence !== undefined && (
          <Space size={6}>
            <Progress
              type="circle"
              size={24}
              percent={Math.round(confidence * 100)}
              showInfo={false}
              status={confidence < 0.75 ? 'exception' : 'normal'}
            />
            <span>映射 {(confidence * 100).toFixed(1)}%</span>
            <Tag color={confidenceMeta.color === 'default' ? undefined : confidenceMeta.color}>
              {confidenceMeta.label}
            </Tag>
          </Space>
        )}
        <Badge status={validation.status} text={validation.label} />
        {isDeveloper && (
          <Button
            className={`${prefixCls}-trust-diagnostics-button`}
            type="text"
            size="small"
            icon={<BugOutlined />}
            onClick={() => setDiagnosticsOpen(true)}
          >
            诊断
          </Button>
        )}
      </div>
      <Steps
        className={`${prefixCls}-trust-steps`}
        size="small"
        responsive
        items={[
          { title: '原始问题', description: question, status: 'finish' },
          {
            title: '口径映射',
            description: semanticText || '等待识别指标与维度',
            status: semanticText ? 'finish' : 'wait',
          },
          {
            title: '条件确认',
            description: conditionText,
            status: 'finish',
          },
          {
            title: 'SQL 校验',
            description: validation.label,
            status:
              parseInfo.sqlEvaluation?.isValidated === false
                ? 'error'
                : parseInfo.sqlEvaluation?.isValidated === true
                ? 'finish'
                : 'process',
          },
        ]}
      />
      {(metrics.length > 0 || dimensions.length > 0) && (
        <div className={`${prefixCls}-trust-definitions`}>
          {[
            ...metrics.map(item => ({ ...item, category: 'metric' })),
            ...dimensions.map(item => ({ ...item, category: 'dimension' })),
          ].map(item => (
            <div key={`${item.category}-${item.name}`}>
              <strong>{item.name}</strong>
              <span>{item.description || '使用已发布语义口径'}</span>
            </div>
          ))}
        </div>
      )}
      {filters.length > 0 && (
        <div className={`${prefixCls}-trust-conditions`}>
          <span>本次查询条件</span>
          <strong>{conditionText}</strong>
        </div>
      )}

      {isDeveloper && (
        <Drawer
          className={`${prefixCls}-trust-drawer`}
          title="SQL 解释与诊断"
          width={isMobile ? '100%' : 760}
          open={diagnosticsOpen}
          destroyOnClose
          onClose={() => setDiagnosticsOpen(false)}
        >
          <Descriptions size="small" bordered column={isMobile ? 1 : 2}>
            <Descriptions.Item label="当前阶段">{workflowStage}</Descriptions.Item>
            <Descriptions.Item label="查询模式">{parseInfo.queryMode || '-'}</Descriptions.Item>
            <Descriptions.Item label="映射置信度">
              {confidence === undefined ? '未提供' : `${(confidence * 100).toFixed(1)}%`}
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

          {(parseError || executeError || parseInfo.sqlEvaluation?.validateMsg) && (
            <Alert
              className={`${prefixCls}-trust-diagnostic-alert`}
              type="error"
              showIcon
              message="当前阶段诊断"
              description={parseError || executeError || parseInfo.sqlEvaluation?.validateMsg}
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

          {sqlItems.length > 0 && (
            <>
              <h3>SQL 版本</h3>
              <Collapse
                items={sqlItems.map(item => ({
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
        </Drawer>
      )}
    </section>
  );
};

export default TrustExplanationPanel;
