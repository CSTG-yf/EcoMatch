import ReactECharts from 'echarts-for-react';
import { Empty, Statistic, Table } from 'antd';
import type { DashboardChartType, DashboardComponent } from './types';

type Props = {
  component: DashboardComponent;
  rows: Record<string, unknown>[];
};

const firstMatchingKey = (
  row: Record<string, unknown>,
  expected: string[],
  predicate: (value: unknown) => boolean,
) => expected.find((key) => key in row) || Object.keys(row).find((key) => predicate(row[key]));

const valueAt = (row: Record<string, unknown>, key?: string) => (key ? row[key] : undefined);

function buildOption(component: DashboardComponent, rows: Record<string, unknown>[]) {
  const sample = rows[0] || {};
  const dimensionKey = firstMatchingKey(sample, component.query.groups || [], () => true);
  const metricKeys = (component.query.aggregators || [])
    .map((item) => item.column)
    .filter((key) => key in sample);
  if (metricKeys.length === 0) {
    const inferred = Object.keys(sample).filter(
      (key) => key !== dimensionKey && Number.isFinite(Number(sample[key])),
    );
    metricKeys.push(...inferred);
  }
  const categories = rows.map((row) => String(valueAt(row, dimensionKey) ?? '-'));
  const seriesType = (type: DashboardChartType, index: number) => {
    if (type === 'COMBO') {
      return index === 0 ? 'bar' : 'line';
    }
    return type === 'LINE' ? 'line' : 'bar';
  };

  if (component.chartType === 'PIE') {
    const metricKey = metricKeys[0];
    return {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      series: [
        {
          type: 'pie',
          radius: ['38%', '68%'],
          data: rows.map((row) => ({
            name: String(valueAt(row, dimensionKey) ?? '-'),
            value: Number(valueAt(row, metricKey) || 0),
          })),
        },
      ],
    };
  }

  return {
    color: ['#1677ff', '#2f9e44', '#f08c00', '#d9485f', '#15aabf'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 44, right: component.chartType === 'COMBO' ? 48 : 20, top: 42, bottom: 44 },
    xAxis: { type: 'category', data: categories, axisLabel: { hideOverlap: true } },
    yAxis:
      component.chartType === 'COMBO'
        ? [{ type: 'value' }, { type: 'value', splitLine: { show: false } }]
        : { type: 'value' },
    series: metricKeys.map((key, index) => ({
      name: key,
      type: seriesType(component.chartType, index),
      yAxisIndex: component.chartType === 'COMBO' && index > 0 ? 1 : 0,
      smooth: component.chartType === 'LINE' || (component.chartType === 'COMBO' && index > 0),
      data: rows.map((row) => Number(valueAt(row, key) || 0)),
    })),
  };
}

const DashboardChart: React.FC<Props> = ({ component, rows }) => {
  if (!rows.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  }
  const sample = rows[0];
  const metricKey = firstMatchingKey(
    sample,
    component.query.aggregators.map((item) => item.column),
    (value) => Number.isFinite(Number(value)),
  );

  if (component.chartType === 'KPI_CARD') {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: 230 }}>
        <Statistic
          title={metricKey || component.title}
          value={Number(valueAt(sample, metricKey) || 0)}
        />
      </div>
    );
  }

  if (component.chartType === 'TABLE') {
    const columns = Object.keys(sample).map((key) => ({
      title: key,
      dataIndex: key,
      key,
      ellipsis: true,
    }));
    return (
      <Table
        size="small"
        rowKey={(_, index) => String(index)}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 8, size: 'small', showSizeChanger: false }}
        scroll={{ x: true }}
      />
    );
  }

  return <ReactECharts option={buildOption(component, rows)} style={{ height: 300 }} notMerge />;
};

export default DashboardChart;
