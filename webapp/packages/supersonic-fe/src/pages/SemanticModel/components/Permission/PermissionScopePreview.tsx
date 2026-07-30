import { Alert, Descriptions, Tag } from 'antd';

type Props = {
  values?: Record<string, any>;
  dimensionCount: number;
  metricCount: number;
  rowFilter?: string;
};

const join = (values?: string[]) => (values || []).filter(Boolean).join('、') || '未配置';

const PermissionScopePreview: React.FC<Props> = ({
  values = {},
  dimensionCount,
  metricCount,
  rowFilter,
}) => {
  const attributes = (values.attributeConditionEntries || [])
    .filter((item: any) => item?.key && item?.value)
    .map((item: any) => `${item.key}=${item.value}`);
  const hasSubject =
    values.authorizedUsers?.length ||
    values.authorizedDepartmentIds?.length ||
    values.authorizedRoles?.length ||
    attributes.length;

  return (
    <>
      {!hasSubject && (
        <Alert
          type="warning"
          showIcon
          message="至少配置一个用户、组织、角色或属性条件后策略才会生效"
          style={{ marginBottom: 16 }}
        />
      )}
      <Descriptions size="small" column={2} bordered>
        <Descriptions.Item label="用户">{join(values.authorizedUsers)}</Descriptions.Item>
        <Descriptions.Item label="组织">
          {join(values.authorizedDepartmentIds)}
        </Descriptions.Item>
        <Descriptions.Item label="角色">{join(values.authorizedRoles)}</Descriptions.Item>
        <Descriptions.Item label="属性条件">
          {attributes.length ? attributes.map(item => <Tag key={item}>{item}</Tag>) : '未配置'}
        </Descriptions.Item>
        <Descriptions.Item label="列资源">
          {dimensionCount} 个维度，{metricCount} 个指标
        </Descriptions.Item>
        <Descriptions.Item label="行范围">{rowFilter || '不限制'}</Descriptions.Item>
      </Descriptions>
    </>
  );
};

export default PermissionScopePreview;
