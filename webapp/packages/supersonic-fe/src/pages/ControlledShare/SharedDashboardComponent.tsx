import { Alert, Empty, Table, Tag } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import React from 'react';
import { SharedComponentQueryData, SharedDashboardComponent as Component } from './types';
import styles from './style.less';

type Props = {
  component: Component;
  data?: SharedComponentQueryData;
  error?: string;
};

const columnKey = (column: { bizName?: string; name?: string }, index: number) =>
  column.bizName || column.name || `column-${index}`;

const numericValue = (value: unknown) =>
  Number.parseFloat(String(value ?? '').replace(/,/g, '')) || 0;

const cellValue = (row: Record<string, unknown>, key?: string) => (key ? row[key] : undefined);

export const SharedDashboardComponent: React.FC<Props> = ({ component, data, error }) => {
  const rows = Array.isArray(data?.queryResults) ? data.queryResults : [];
  const sourceColumns = Array.isArray(data?.queryColumns) ? data.queryColumns : [];
  const columns = sourceColumns.map((column, index) => {
    const key = columnKey(column, index);
    return {
      title: column.bizName || column.name || key,
      dataIndex: key,
      key,
      ellipsis: true,
    };
  });
  const chartType = component.visualization?.chartType || component.type || 'table';
  const dimension = columns[0];
  const metrics = columns.slice(1);
  const chartOption =
    chartType === 'pie'
      ? {
          tooltip: { trigger: 'item' },
          series: [
            {
              type: 'pie',
              radius: ['38%', '68%'],
              data: rows.map((row) => ({
                name: String(cellValue(row, dimension?.dataIndex) ?? ''),
                value: numericValue(cellValue(row, metrics[0]?.dataIndex)),
              })),
            },
          ],
        }
      : {
          tooltip: { trigger: 'axis' },
          grid: { top: 24, right: 18, bottom: 36, left: 48 },
          xAxis: {
            type: 'category',
            data: rows.map((row) => String(cellValue(row, dimension?.dataIndex) ?? '')),
          },
          yAxis: { type: 'value' },
          series: metrics.map((metric) => ({
            name: metric.title,
            type: chartType === 'line' ? 'line' : 'bar',
            smooth: chartType === 'line',
            data: rows.map((row) => numericValue(cellValue(row, metric.dataIndex))),
          })),
        };
  const masked = Boolean(
    component.masked || data?.masked || data?.maskingApplied || data?.dataMasked,
  );

  return (
    <article className={styles.sharedComponent}>
      <div className={styles.sharedComponentHeader}>
        <strong title={component.title}>{component.title}</strong>
        <div className={styles.componentTags}>
          {masked && (
            <Tag icon={<LockOutlined />} color="blue">
              已脱敏
            </Tag>
          )}
          <Tag>{chartType}</Tag>
        </div>
      </div>
      <div className={styles.sharedComponentBody}>
        {error ? (
          <Alert type="error" showIcon message="组件加载失败" description={error} />
        ) : rows.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
        ) : component.type === 'number' ? (
          <div className={styles.numberValue}>
            {String(cellValue(rows[0], columns[columns.length - 1]?.dataIndex) ?? '--')}
          </div>
        ) : component.type === 'chart' && !['table', 'number'].includes(chartType) ? (
          <ReactECharts option={chartOption} className={styles.sharedChart} />
        ) : (
          <Table
            size="small"
            pagination={false}
            rowKey={(row, index) =>
              columns.map((column) => String(cellValue(row, column.dataIndex) ?? '')).join('|') ||
              String(index)
            }
            columns={columns}
            dataSource={rows}
            scroll={{ x: true, y: 320 }}
          />
        )}
      </div>
    </article>
  );
};

export default SharedDashboardComponent;
