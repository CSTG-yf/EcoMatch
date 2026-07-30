import { PREFIX_CLS } from '../../../common/constants';
import { MsgDataType } from '../../../common/type';
import NoPermissionChart from '../NoPermissionChart';
import { ColumnType } from '../../../common/type';
import { Spin } from 'antd';
import PieChart from './PieChart';

type Props = {
  data: MsgDataType;
  question: string;
  triggerResize?: boolean;
  loading: boolean;
  metricField: ColumnType;
  categoryField: ColumnType;
  onApplyAuth?: (model: string) => void;
  onCategorySelect?: (column: ColumnType, value: any) => void;
};

const Pie: React.FC<Props> = ({
  data,
  question,
  triggerResize,
  loading,
  metricField,
  categoryField,
  onApplyAuth,
  onCategorySelect,
}) => {
  const { entityInfo } = data;

  if (metricField && !metricField?.authorized) {
    return (
      <NoPermissionChart
        model={entityInfo?.dataSetInfo?.name || ''}
        chartType="pieChart"
        onApplyAuth={onApplyAuth}
      />
    );
  }

  const prefixCls = `${PREFIX_CLS}-pie`;

  return (
    <div className={prefixCls}>
      <div className={`${prefixCls}-metric-fields ${prefixCls}-metric-field-single`}>
        {question}
      </div>
      <Spin spinning={loading}>
        <PieChart
          data={data}
          metricField={metricField}
          categoryField={categoryField}
          triggerResize={triggerResize}
          onCategorySelect={onCategorySelect}
        />
      </Spin>
    </div>
  );
};

export default Pie;
