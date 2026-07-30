import { CHART_BLUE_COLOR, CHART_SECONDARY_COLOR, PREFIX_CLS } from '../../../common/constants';
import { MsgDataType } from '../../../common/type';
import {
  formatByDataFormatType,
  getChartLightenColor,
  getFormattedValue,
} from '../../../utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import {
  forwardRef,
  ForwardRefRenderFunction,
  useContext,
  useEffect,
  useImperativeHandle,
  useRef,
} from 'react';
import NoPermissionChart from '../NoPermissionChart';
import { ColumnType } from '../../../common/type';
import { Spin } from 'antd';
import { ChartItemContext } from '../../ChatItem';
import { useExportByEcharts } from '../../../hooks';

type Props = {
  data: MsgDataType;
  question?: string;
  triggerResize?: boolean;
  loading: boolean;
  metricField: ColumnType;
  onApplyAuth?: (model: string) => void;
  onCategorySelect?: (column: ColumnType, value: any) => void;
};

const BarChart: React.FC<Props> = ({
  data,
  question = '',
  triggerResize,
  loading,
  metricField,
  onApplyAuth,
  onCategorySelect,
}) => {
  const chartRef = useRef<any>();
  const instanceRef = useRef<ECharts>();

  const { queryColumns, queryResults, entityInfo } = data;

  const categoryColumn = queryColumns?.find(
    column => column.showType === 'CATEGORY' || column.showType === 'DATE'
  );
  const categoryColumnName = categoryColumn?.bizName || '';
  const metricColumns = queryColumns?.filter(column => column.showType === 'NUMBER') || [];

  const renderChart = () => {
    let instanceObj: any;
    if (!instanceRef.current) {
      instanceObj = echarts.init(chartRef.current);
      instanceRef.current = instanceObj;
    } else {
      instanceObj = instanceRef.current;
    }
    const data = queryResults || [];
    const xData = data.map(item =>
      item[categoryColumnName] !== undefined ? item[categoryColumnName] : '未知'
    );
    instanceObj.setOption({
      xAxis: {
        type: 'category',
        axisTick: {
          show: false,
        },
        axisLine: {
          lineStyle: {
            color: CHART_SECONDARY_COLOR,
          },
        },
        axisLabel: {
          width: 200,
          overflow: 'truncate',
          showMaxLabel: true,
          hideOverlap: false,
          interval: 0,
          color: '#333',
          rotate: 30,
        },
        data: xData,
      },
      yAxis: {
        type: 'value',
        splitLine: {
          lineStyle: {
            opacity: 0.3,
          },
        },
        axisLabel: {
          formatter: function (value: any) {
            return value === 0
              ? 0
              : metricField.dataFormatType === 'percent'
              ? formatByDataFormatType(value, metricField.dataFormatType, metricField.dataFormat)
              : getFormattedValue(value);
          },
        },
      },
      tooltip: {
        trigger: 'axis',
        formatter: function (params: any[]) {
          const param = params[0];
          const valueLabels = params
            .map(
              (item: any) =>
                `<div style="margin-top: 3px;">${
                  item.marker
                } <span style="display: inline-block; width: 70px; margin-right: 12px;">${
                  item.seriesName
                }</span><span style="display: inline-block; width: 90px; text-align: right; font-weight: 500;">${
                  item.value === ''
                    ? '-'
                    : metricField.dataFormatType === 'percent' ||
                      metricField.dataFormatType === 'decimal'
                    ? formatByDataFormatType(
                        item.value,
                        metricField.dataFormatType,
                        metricField.dataFormat
                      )
                    : getFormattedValue(item.value)
                }</span></div>`
            )
            .join('');
          return `${param.name}<br />${valueLabels}`;
        },
      },
      grid: {
        left: '2%',
        right: '1%',
        bottom: '3%',
        top: 20,
        containLabel: true,
      },
      legend:
        metricColumns.length > 1
          ? {
              left: 0,
              top: 0,
              type: 'scroll',
            }
          : undefined,
      series: metricColumns.map((column, index) => ({
        type: 'bar',
        name: column.name,
        barMaxWidth: 24,
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color:
            index === 0
              ? new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                  { offset: 0, color: CHART_BLUE_COLOR },
                  { offset: 1, color: getChartLightenColor(CHART_BLUE_COLOR) },
                ])
              : undefined,
        },
        label: {
          show: metricColumns.length === 1,
          position: 'top',
          formatter: function ({ value }: any) {
            return value === 0
              ? 0
              : column.dataFormatType === 'percent'
              ? formatByDataFormatType(value, column.dataFormatType, column.dataFormat)
              : getFormattedValue(value);
          },
        },
        data: data.map(item => item[column.bizName]),
      })),
    });
    instanceObj.off('click');
    if (categoryColumn && onCategorySelect) {
      instanceObj.on('click', (params: any) => {
        onCategorySelect(categoryColumn, params.name);
      });
    }
    instanceObj.resize();
  };

  useEffect(() => {
    if (
      queryResults &&
      queryResults.length > 0 &&
      metricColumns.length > 0 &&
      metricColumns.every(column => column.authorized)
    ) {
      renderChart();
    }
  }, [queryResults, onCategorySelect]);

  useEffect(() => {
    if (triggerResize && instanceRef.current) {
      instanceRef.current.resize();
    }
  }, [triggerResize]);

  if (metricColumns.some(column => !column.authorized)) {
    return (
      <NoPermissionChart
        model={entityInfo?.dataSetInfo.name || ''}
        chartType="barChart"
        onApplyAuth={onApplyAuth}
      />
    );
  }

  const prefixCls = `${PREFIX_CLS}-bar`;

  const { downloadChartAsImage } = useExportByEcharts({
    instanceRef,
    question,
  });

  const { register } = useContext(ChartItemContext);

  register('downloadChartAsImage', downloadChartAsImage);

  return (
    <div>
      <div className={`${prefixCls}-top-bar`}>
        <div className={`${prefixCls}-indicator-name`}>{question}</div>
      </div>
      <Spin spinning={loading}>
        <div className={`${prefixCls}-chart`} ref={chartRef} />
      </Spin>
    </div>
  );
};

export default BarChart;
