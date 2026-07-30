import Bar from './Bar';
import MetricCard from './MetricCard';
import MetricTrend from './MetricTrend';
import MarkDown from './MarkDown';
import Table from './Table';
import {
  ColumnType,
  DrillDownDimensionType,
  FieldType,
  FilterItemType,
  MsgDataType,
} from '../../common/type';
import { useEffect, useRef, useState } from 'react';
import { queryData, recordChartFeedback } from '../../service';
import classNames from 'classnames';
import { PREFIX_CLS, MsgContentTypeEnum } from '../../common/constants';
import Text from './Text';
import DrillDownDimensions from '../DrillDownDimensions';
import MetricOptions from '../MetricOptions';
import { isMobile } from '../../utils/utils';
import Pie from './Pie';
import { Segmented, Tag, Tooltip, message } from 'antd';
import {
  BarChartOutlined,
  DashboardOutlined,
  FundProjectionScreenOutlined,
  LineChartOutlined,
  PieChartOutlined,
  TableOutlined,
} from '@ant-design/icons';
import {
  inferVisualizationType,
  isVisualizationCompatible,
  mergeDimensionFilters,
  normalizeVisualizationType,
  resolveVisualizationType,
  VisualizationType,
  visualizationOptions,
} from './visualizationModel';

type Props = {
  queryId?: number;
  question: string;
  data: MsgDataType;
  triggerResize?: boolean;
  forceShowTable?: boolean;
  isSimpleMode?: boolean;
  onMsgContentTypeChange?: (msgContentType: MsgContentTypeEnum) => void;
};

const VISUALIZATION_META: Record<
  VisualizationType,
  { label: string; icon: React.ReactNode; contentType: MsgContentTypeEnum }
> = {
  KPI_CARD: {
    label: '指标卡',
    icon: <DashboardOutlined />,
    contentType: MsgContentTypeEnum.METRIC_CARD,
  },
  TABLE: { label: '表格', icon: <TableOutlined />, contentType: MsgContentTypeEnum.TABLE },
  LINE: {
    label: '折线图',
    icon: <LineChartOutlined />,
    contentType: MsgContentTypeEnum.METRIC_TREND,
  },
  BAR: { label: '柱状图', icon: <BarChartOutlined />, contentType: MsgContentTypeEnum.METRIC_BAR },
  PIE: { label: '饼图', icon: <PieChartOutlined />, contentType: MsgContentTypeEnum.METRIC_PIE },
  COMBO: {
    label: '组合图',
    icon: <FundProjectionScreenOutlined />,
    contentType: MsgContentTypeEnum.METRIC_COMBO,
  },
};

const ChatMsg: React.FC<Props> = ({
  queryId,
  question,
  data,
  triggerResize,
  forceShowTable = false,
  isSimpleMode,
  onMsgContentTypeChange,
}) => {
  const { queryColumns, queryResults, chatContext, queryMode } = data || {};
  const { dimensionFilters, elementMatches } = chatContext || {};

  const [columns, setColumns] = useState<ColumnType[]>([]);
  const [referenceColumn, setReferenceColumn] = useState<ColumnType>();
  const [dataSource, setDataSource] = useState<any[]>(queryResults);
  const [drillDownDimension, setDrillDownDimension] = useState<DrillDownDimensionType>();
  const [secondDrillDownDimension, setSecondDrillDownDimension] =
    useState<DrillDownDimensionType>();
  const [loading, setLoading] = useState(false);
  const [defaultMetricField, setDefaultMetricField] = useState<FieldType>();
  const [activeMetricField, setActiveMetricField] = useState<FieldType>();
  const [dateModeValue, setDateModeValue] = useState<any>();
  const [currentDateOption, setCurrentDateOption] = useState<number>();
  const [activeVisualization, setActiveVisualization] = useState<VisualizationType>(() =>
    resolveVisualizationType(data?.recommendedChart, queryColumns || [], queryResults || [])
  );
  const [linkedFilters, setLinkedFilters] = useState<FilterItemType[]>([]);
  const drillRequestId = useRef(0);

  const prefixCls = `${PREFIX_CLS}-chat-msg`;

  const updateColumns = (queryColumnsValue: ColumnType[]) => {
    const referenceColumn = queryColumnsValue.find(item => item.showType === 'more');
    setReferenceColumn(referenceColumn);
    setColumns(queryColumnsValue.filter(item => item.showType !== 'more'));
  };

  useEffect(() => {
    updateColumns(queryColumns);
    setDataSource(queryResults);
    setDefaultMetricField(chatContext?.metrics?.[0]);
    setActiveMetricField(chatContext?.metrics?.[0]);
    setDateModeValue(chatContext?.dateInfo?.dateMode);
    setCurrentDateOption(chatContext?.dateInfo?.unit);
    setDrillDownDimension(undefined);
    setSecondDrillDownDimension(undefined);
    setActiveVisualization(
      resolveVisualizationType(data?.recommendedChart, queryColumns || [], queryResults || [])
    );
    setLinkedFilters([]);
  }, [data]);

  const metricFields = columns.filter(item => item.showType === 'NUMBER');

  const getMsgContentType = () => {
    if (!columns) {
      return;
    }
    if (isSimpleMode) {
      return MsgContentTypeEnum.MARKDOWN;
    }
    if (forceShowTable) {
      return MsgContentTypeEnum.TABLE;
    }
    const isText = !queryColumns?.length;

    if (isText) {
      return MsgContentTypeEnum.TEXT;
    }
    const compatibleVisualization = isVisualizationCompatible(
      activeVisualization,
      columns,
      dataSource
    )
      ? activeVisualization
      : inferVisualizationType(columns, dataSource);
    return VISUALIZATION_META[compatibleVisualization].contentType;
  };

  const getMsgStyle = (type: MsgContentTypeEnum) => {
    if (isMobile) {
      return { maxWidth: 'calc(100vw - 20px)' };
    }
    if (!queryResults?.length || !queryColumns.length) {
      return;
    }
    if (type === MsgContentTypeEnum.METRIC_BAR) {
      return {
        [queryResults.length > 5 ? 'width' : 'minWidth']: queryResults.length * 150,
      };
    }
    if (type === MsgContentTypeEnum.TABLE) {
      return {
        [queryColumns.length > 5 ? 'width' : 'minWidth']: queryColumns.length * 150,
      };
    }
    if (
      type === MsgContentTypeEnum.METRIC_TREND ||
      type === MsgContentTypeEnum.METRIC_PIE ||
      type === MsgContentTypeEnum.METRIC_COMBO
    ) {
      return { width: 'calc(100vw - 410px)' };
    }
  };

  useEffect(() => {
    const type = getMsgContentType();
    if (type) {
      onMsgContentTypeChange?.(type);
    }
  }, [data, columns, isSimpleMode]);

  if (!queryColumns || !queryResults || !columns) {
    return null;
  }

  const getMsgContent = () => {
    const contentType = getMsgContentType();
    switch (contentType) {
      case MsgContentTypeEnum.TEXT:
        return <Text columns={columns} referenceColumn={referenceColumn} dataSource={dataSource} />;
      case MsgContentTypeEnum.METRIC_CARD:
        return (
          <MetricCard
            data={{ ...data, queryColumns: columns, queryResults: dataSource }}
            question={question}
            loading={loading}
          />
        );
      case MsgContentTypeEnum.TABLE:
        return (
          <Table
            question={question}
            data={{ ...data, queryColumns: columns, queryResults: dataSource }}
            loading={loading}
          />
        );
      case MsgContentTypeEnum.METRIC_TREND:
        return (
          <MetricTrend
            data={{
              ...data,
              queryColumns: columns,
              queryResults: dataSource,
            }}
            question={question}
            loading={loading}
            triggerResize={triggerResize}
            activeMetricField={activeMetricField}
            drillDownDimension={drillDownDimension}
            currentDateOption={currentDateOption}
            onSelectDateOption={selectDateOption}
            visualizationType="LINE"
          />
        );
      case MsgContentTypeEnum.METRIC_BAR:
        return (
          <Bar
            data={{ ...data, queryColumns: columns, queryResults: dataSource }}
            question={question}
            triggerResize={triggerResize}
            loading={loading}
            metricField={metricFields[0]}
            onCategorySelect={applyLinkedFilter}
          />
        );
      case MsgContentTypeEnum.METRIC_PIE:
        const categoryField = columns.find(item => item.showType === 'CATEGORY');
        return (
          <Pie
            data={{ ...data, queryColumns: columns, queryResults: dataSource }}
            question={question}
            triggerResize={triggerResize}
            loading={loading}
            metricField={metricFields[0]}
            categoryField={categoryField!}
            onCategorySelect={applyLinkedFilter}
          />
        );
      case MsgContentTypeEnum.METRIC_COMBO:
        return (
          <MetricTrend
            data={{
              ...data,
              queryColumns: columns,
              queryResults: dataSource,
            }}
            question={question}
            loading={loading}
            triggerResize={triggerResize}
            activeMetricField={activeMetricField}
            drillDownDimension={drillDownDimension}
            currentDateOption={currentDateOption}
            onSelectDateOption={selectDateOption}
            visualizationType="COMBO"
          />
        );
      case MsgContentTypeEnum.MARKDOWN:
        return (
          <div style={{ maxHeight: 800 }}>
            <MarkDown markdown={data.textResult} loading={loading} />
          </div>
        );
      default:
        return (
          <Table
            question={question}
            data={{ ...data, queryColumns: columns, queryResults: dataSource }}
            loading={loading}
          />
        );
    }
  };

  const onLoadData = async (value: any) => {
    const requestId = ++drillRequestId.current;
    setLoading(true);
    try {
      const res: any = await queryData({
        ...chatContext,
        dimensionFilters: mergeDimensionFilters(chatContext.dimensionFilters, linkedFilters),
        ...value,
        queryId,
        parseId: chatContext.id,
      });
      if (requestId !== drillRequestId.current) {
        return false;
      }
      if (res.code === 200) {
        const nextColumns = res.data?.queryColumns || [];
        const nextRows = res.data?.queryResults || [];
        updateColumns(nextColumns);
        setDataSource(nextRows);
        setActiveVisualization(
          resolveVisualizationType(res.data?.recommendedChart, nextColumns, nextRows)
        );
        return true;
      }
      message.error(res.message || '下钻查询失败');
      return false;
    } catch {
      if (requestId === drillRequestId.current) {
        message.error('下钻查询失败，请稍后重试');
      }
      return false;
    } finally {
      if (requestId === drillRequestId.current) {
        setLoading(false);
      }
    }
  };

  async function applyLinkedFilter(column: ColumnType, value: any) {
    const dimension = [
      ...(chatContext.dimensions || []),
      ...(data.recommendedDimensions || []),
    ].find(item => item.bizName === column.bizName || item.name === column.name);
    if (!dimension?.id) {
      message.warning('当前字段未配置可联动的语义维度');
      return;
    }
    const previous = linkedFilters;
    const next = [
      ...linkedFilters.filter(filter => filter.bizName !== dimension.bizName),
      {
        elementID: dimension.id,
        name: dimension.name,
        bizName: dimension.bizName,
        operator: '=',
        value,
      },
    ];
    setLinkedFilters(next);
    const succeeded = await onLoadData({
      dimensionFilters: mergeDimensionFilters(chatContext.dimensionFilters, next),
    });
    if (!succeeded) {
      setLinkedFilters(previous);
    }
  }

  const removeLinkedFilter = async (bizName: string) => {
    const previous = linkedFilters;
    const next = linkedFilters.filter(filter => filter.bizName !== bizName);
    setLinkedFilters(next);
    const succeeded = await onLoadData({
      dimensionFilters: mergeDimensionFilters(chatContext.dimensionFilters, next),
    });
    if (!succeeded) {
      setLinkedFilters(previous);
    }
  };

  const onSelectDimension = async (dimension?: DrillDownDimensionType) => {
    const previous = drillDownDimension;
    setDrillDownDimension(dimension);
    setSecondDrillDownDimension(undefined);
    const succeeded = await onLoadData({
      dateInfo: {
        ...chatContext.dateInfo,
        dateMode: dateModeValue,
        unit: currentDateOption || chatContext.dateInfo?.unit,
      },
      dimensions: dimension
        ? [...(chatContext.dimensions || []), dimension]
        : chatContext.dimensions,
      metrics: [activeMetricField || defaultMetricField],
    });
    if (!succeeded) {
      setDrillDownDimension(previous);
    }
  };

  const onSelectSecondDimension = async (dimension?: DrillDownDimensionType) => {
    const previous = secondDrillDownDimension;
    setSecondDrillDownDimension(dimension);
    const succeeded = await onLoadData({
      dateInfo: {
        ...chatContext.dateInfo,
        dateMode: dateModeValue,
        unit: currentDateOption || chatContext.dateInfo?.unit,
      },
      dimensions: [
        ...(chatContext.dimensions || []),
        ...(drillDownDimension ? [drillDownDimension] : []),
        ...(dimension ? [dimension] : []),
      ],
      metrics: [activeMetricField || defaultMetricField],
    });
    if (!succeeded) {
      setSecondDrillDownDimension(previous);
    }
  };

  const onSwitchMetric = (metricField?: FieldType) => {
    setActiveMetricField(metricField);
    onLoadData({
      dateInfo: {
        ...chatContext.dateInfo,
        dateMode: dateModeValue,
        unit: currentDateOption || chatContext.dateInfo?.unit,
      },
      dimensions: drillDownDimension
        ? [...(chatContext.dimensions || []), drillDownDimension]
        : chatContext.dimensions,
      metrics: [metricField || defaultMetricField],
    });
  };

  const selectDateOption = (dateOption: number) => {
    setCurrentDateOption(dateOption);
    setDateModeValue('RECENT');
    onLoadData({
      metrics: [activeMetricField || defaultMetricField],
      dimensions: drillDownDimension
        ? [...(chatContext.dimensions || []), drillDownDimension]
        : chatContext.dimensions,
      dateInfo: {
        ...chatContext?.dateInfo,
        dateMode: 'RECENT',
        unit: dateOption,
      },
    });
  };

  const chartMsgClass = classNames({
    [prefixCls]: ![MsgContentTypeEnum.TABLE, MsgContentTypeEnum.MARKDOWN].includes(
      getMsgContentType() as MsgContentTypeEnum
    ),
  });

  const entityId = dimensionFilters?.length > 0 ? dimensionFilters[0].value : undefined;
  const entityName = elementMatches?.find((item: any) => item.element?.type === 'ID')?.element
    ?.name;

  const isEntityMode =
    (queryMode === 'TAG_LIST_FILTER' || queryMode === 'METRIC_TAG') &&
    typeof entityId === 'string' &&
    entityName !== undefined;

  const existDrillDownDimension =
    (queryMode.includes('METRIC') || queryMode === 'LLM_S2SQL') &&
    getMsgContentType() !== MsgContentTypeEnum.TEXT &&
    !isEntityMode;

  const recommendMetrics = chatContext?.metrics?.filter(metric =>
    queryColumns.every(queryColumn => queryColumn.bizName !== metric.bizName)
  );

  const isMultipleMetric =
    (queryMode.includes('METRIC') || queryMode === 'LLM_S2SQL') &&
    recommendMetrics?.length > 0 &&
    queryColumns?.filter(column => column.showType === 'NUMBER').length === 1;

  const type = getMsgContentType();
  const style = type ? getMsgStyle(type) : undefined;
  const availableVisualizations = visualizationOptions(
    data.recommendedChart,
    data.candidateCharts,
    columns,
    dataSource
  );
  const recommendedVisualization =
    normalizeVisualizationType(data.recommendedChart?.chartType) ||
    inferVisualizationType(columns, dataSource);
  const selectedVisualization = forceShowTable
    ? 'TABLE'
    : isVisualizationCompatible(activeVisualization, columns, dataSource)
    ? activeVisualization
    : inferVisualizationType(columns, dataSource);

  const switchVisualization = (next: string | number) => {
    const visualization = next as VisualizationType;
    setActiveVisualization(visualization);
    onMsgContentTypeChange?.(VISUALIZATION_META[visualization].contentType);
    if (queryId && visualization !== activeVisualization) {
      recordChartFeedback(queryId, recommendedVisualization, visualization, 'CHART_SELECTOR').catch(
        () => message.warning('图表已切换，反馈记录失败')
      );
    }
  };

  return (
    <div className={chartMsgClass} style={style}>
      {dataSource?.length === 0 ? (
        <div>暂无数据</div>
      ) : (
        <div>
          {!isSimpleMode && availableVisualizations.length > 1 && (
            <div className={`${prefixCls}-visualization-toolbar`}>
              <span>视图</span>
              <Segmented
                size="small"
                value={selectedVisualization}
                onChange={switchVisualization}
                options={availableVisualizations.map(visualization => ({
                  value: visualization,
                  label: (
                    <Tooltip title={VISUALIZATION_META[visualization].label}>
                      <span className={`${prefixCls}-visualization-option`}>
                        {VISUALIZATION_META[visualization].icon}
                        <span>{VISUALIZATION_META[visualization].label}</span>
                      </span>
                    </Tooltip>
                  ),
                }))}
              />
            </div>
          )}
          {linkedFilters.length > 0 && (
            <div className={`${prefixCls}-linked-filters`} aria-label="联动筛选">
              <span>联动筛选</span>
              {linkedFilters.map(filter => (
                <Tag
                  key={filter.bizName}
                  closable
                  onClose={event => {
                    event.preventDefault();
                    removeLinkedFilter(filter.bizName);
                  }}
                >
                  {filter.name}：{String(filter.value)}
                </Tag>
              ))}
            </div>
          )}
          {getMsgContent()}
          {(isMultipleMetric || existDrillDownDimension) && !isSimpleMode && (
            <div
              className={`${prefixCls}-bottom-tools ${
                getMsgContentType() === MsgContentTypeEnum.METRIC_CARD
                  ? `${prefixCls}-metric-card-tools`
                  : ''
              } ${isMobile ? 'mobile' : ''}`}
            >
              {isMultipleMetric && (
                <MetricOptions
                  metrics={chatContext.metrics}
                  defaultMetric={defaultMetricField}
                  currentMetric={activeMetricField}
                  onSelectMetric={onSwitchMetric}
                />
              )}
              {existDrillDownDimension && (
                <DrillDownDimensions
                  drillDownDimensions={data?.recommendedDimensions || []}
                  drillDownDimension={drillDownDimension}
                  secondDrillDownDimension={secondDrillDownDimension}
                  originDimensions={chatContext.dimensions}
                  dimensionFilters={chatContext.dimensionFilters}
                  onSelectDimension={onSelectDimension}
                  onSelectSecondDimension={onSelectSecondDimension}
                />
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default ChatMsg;
