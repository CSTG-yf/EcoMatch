import { Alert, Empty, Spin, Table, Tag } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { dashboardColumnKey } from '../model';
import { DashboardComponent } from '../types';
import styles from '../style.less';

type Props = {
  component: DashboardComponent;
  selected: boolean;
  editable: boolean;
  loading?: boolean;
  error?: string;
  result?: any;
  onSelect: () => void;
};

const DashboardCard: React.FC<Props> = ({
  component,
  selected,
  editable,
  loading,
  error,
  result,
  onSelect,
}) => {
  const rows = Array.isArray(result?.queryResults) ? result.queryResults : [];
  const hasSuccessfulResult = result?.queryState === 'SUCCESS';
  const columns = Array.isArray(result?.queryColumns)
      ? result.queryColumns.slice(0, 8).map((column: any) => ({
          title: column.bizName || column.name,
          dataIndex: dashboardColumnKey(column),
          key: dashboardColumnKey(column),
          ellipsis: true,
        }))
    : [];
  const dimensionColumn = columns[0];
  const metricColumns = columns.slice(1);
  const chartType = component.visualization.chartType;
  const numericValue = (value: any) =>
    Number.parseFloat(String(value ?? '').replace(/,/g, '')) || 0;
  const chartOption =
    chartType === 'pie'
      ? {
          tooltip: { trigger: 'item' },
          series: [
            {
              type: 'pie',
              radius: ['38%', '68%'],
              data: rows.map((row: any) => ({
                name: String(row[dimensionColumn?.dataIndex] ?? '未命名'),
                value: numericValue(row[metricColumns[0]?.dataIndex]),
              })),
            },
          ],
        }
      : {
          tooltip: { trigger: 'axis' },
          grid: { top: 24, right: 18, bottom: 36, left: 48 },
          xAxis: {
            type: 'category',
            data: rows.map((row: any) => String(row[dimensionColumn?.dataIndex] ?? '')),
          },
          yAxis: { type: 'value' },
          series: metricColumns.map((column: any) => ({
            name: column.title,
            type: chartType === 'line' ? 'line' : 'bar',
            smooth: chartType === 'line',
            data: rows.map((row: any) => numericValue(row[column.dataIndex])),
          })),
        };

  return (
    <section
      className={`${styles.dashboardCard} ${selected ? styles.dashboardCardSelected : ''}`}
      style={{
        gridColumn: `${component.layout.x + 1} / span ${component.layout.w}`,
        gridRow: `span ${component.layout.h}`,
      }}
      onClick={onSelect}
      role={editable ? 'button' : undefined}
      tabIndex={editable ? 0 : undefined}
      onKeyDown={(event) => {
        if (editable && (event.key === 'Enter' || event.key === ' ')) {
          onSelect();
        }
      }}
    >
      <header className={styles.dashboardCardHeader}>
        <strong>{component.title}</strong>
        <div>
          {component.masked && (
            <Tag icon={<LockOutlined />} color="blue">
              已脱敏
            </Tag>
          )}
          <Tag>{component.visualization.chartType}</Tag>
        </div>
      </header>
      <div className={styles.dashboardCardBody}>
        <Spin spinning={Boolean(loading)}>
          {error ? (
            <Alert type="error" showIcon message="组件刷新失败" description={error} />
          ) : rows.length > 0 ? (
            component.type === 'number' ? (
              <div className={styles.numberValue}>
                {String(rows[0]?.[columns[columns.length - 1]?.dataIndex] ?? '--')}
              </div>
            ) : component.type === 'chart' && chartType !== 'table' ? (
              <ReactECharts option={chartOption} style={{ height: '100%', minHeight: 220 }} />
            ) : (
              <Table
                rowKey={(record: any) =>
                  columns.map((column: any) => record[column.dataIndex]).join('|')
                }
                size="small"
                pagination={false}
                columns={columns}
                dataSource={rows.slice(0, 8)}
                scroll={{ x: true }}
              />
            )
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={hasSuccessfulResult ? '暂无数据' : '等待刷新查询结果'}
            />
          )}
        </Spin>
      </div>
    </section>
  );
};

export default DashboardCard;
