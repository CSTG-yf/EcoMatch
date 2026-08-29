import React, { useEffect, useState, useRef } from 'react';
import { Button, message, Form, Space, Modal, Input, Select } from 'antd';
import { ProCard } from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import { createGroupAuth, updateGroupAuth } from '../../service';
import PermissionCreateForm from './PermissionCreateForm';
import type { StateType } from '../../model';
import SqlEditor from '@/components/SqlEditor';
import { TransType } from '../../enum';
import DimensionMetricVisibleTransfer from '../Entity/DimensionMetricVisibleTransfer';
import { wrapperTransTypeAndId } from '../../utils';
import styles from '../style.less';
import PermissionScopePreview from './PermissionScopePreview';

type Props = {
  permissonData: any;
  onCancel: () => void;
  visible: boolean;
  onSubmit: (params?: any) => void;
};
const FormItem = Form.Item;
const TextArea = Input.TextArea;
const PermissionCreateModal: React.FC<Props> = ({ visible, permissonData, onCancel, onSubmit }) => {
  const modelModel = useModel('SemanticModel.modelData');
  const dimensionModel = useModel('SemanticModel.dimensionData');
  const metricModel = useModel('SemanticModel.metricData');

  const { selectModelId: modelId } = modelModel;
  const { MdimensionList: dimensionList } = dimensionModel;
  const { MmetricList: metricList } = metricModel;

  const [form] = Form.useForm();
  const basicInfoFormRef = useRef<any>(null);
  const [selectedDimensionKeyList, setSelectedDimensionKeyList] = useState<string[]>([]);
  const [selectedMetricKeyList, setSelectedMetricKeyList] = useState<string[]>([]);
  const [selectedKeyList, setSelectedKeyList] = useState<string[]>([]);
  const [columnAccessMode, setColumnAccessMode] = useState<string>('MASKED');
  const [basicInfoValues, setBasicInfoValues] = useState<Record<string, any>>({});
  const watchedRowFilter = Form.useWatch('dimensionFilters', form);

  const saveAuth = async () => {
    const basicInfoFormValues = await basicInfoFormRef.current.formRef.validateFields();
    const values = await form.validateFields();
    const { dimensionFilters, dimensionFilterDescription, rowFilterRulesJson } = values;
    const { attributeConditionEntries = [], ...basicValues } = basicInfoFormValues;
    const hasSubject =
      basicValues.authorizedDepartmentIds?.length ||
      basicValues.authorizedUsers?.length ||
      basicValues.authorizedRoles?.length ||
      attributeConditionEntries.length;
    if (!hasSubject) {
      message.error('至少配置一个用户、组织、角色或属性条件');
      return;
    }
    const attributeConditions = attributeConditionEntries.reduce(
      (result: Record<string, string>, item: { key?: string; value?: string }) => {
        if (item.key?.trim() && item.value?.trim()) {
          result[item.key.trim()] = item.value.trim();
        }
        return result;
      },
      {},
    );

    const { authRules = [] } = permissonData;
    let target = authRules?.[0];
    if (!target) {
      target = { dimensions: dimensionList };
    } else {
      target.dimensions = dimensionList;
    }
    permissonData.authRules = [target];

    let rowFilterRules: any[] = [];
    if (rowFilterRulesJson?.trim()) {
      try {
        rowFilterRules = JSON.parse(rowFilterRulesJson);
        if (!Array.isArray(rowFilterRules)) {
          throw new Error('not-array');
        }
      } catch {
        message.error('结构化行权限必须是合法 JSON 数组');
        return;
      }
    }
    const resourcePermissions = [
      ...selectedDimensionKeyList.map((resourceName) => ({
        resourceType: 'DIMENSION',
        resourceName,
        accessMode: columnAccessMode,
      })),
      ...selectedMetricKeyList.map((resourceName) => ({
        resourceType: 'METRIC',
        resourceName,
        accessMode: columnAccessMode,
      })),
    ];

    let saveAuthQuery = createGroupAuth;
    if (basicInfoFormValues.groupId) {
      saveAuthQuery = updateGroupAuth;
    }
    const { code, msg } = await saveAuthQuery({
      ...basicValues,
      attributeConditions,
      dimensionFilters: dimensionFilters ? [dimensionFilters] : [],
      dimensionFilterDescription,
      rowFilterRules,
      resourcePermissions,
      authRules: [
        {
          dimensions: selectedDimensionKeyList,
          metrics: selectedMetricKeyList,
        },
      ],
      modelId,
    });

    if (code === 200) {
      onSubmit?.();
      message.success('保存成功');
      return;
    }
    message.error(msg);
  };

  useEffect(() => {
    form.resetFields();
    const { dimensionFilters, dimensionFilterDescription, rowFilterRules = [] } = permissonData;
    form.setFieldsValue({
      dimensionFilterDescription,
      dimensionFilters: Array.isArray(dimensionFilters) ? dimensionFilters[0] || '' : '',
      rowFilterRulesJson: rowFilterRules.length ? JSON.stringify(rowFilterRules, null, 2) : '',
    });
    const dimensionAuth = permissonData?.authRules?.[0]?.dimensions || [];
    const metricAuth = permissonData?.authRules?.[0]?.metrics || [];
    setSelectedDimensionKeyList(dimensionAuth);
    setSelectedMetricKeyList(metricAuth);
    const configuredMode = permissonData?.resourcePermissions?.find(
      (permission: any) => permission?.accessMode,
    )?.accessMode;
    setColumnAccessMode(configuredMode || 'MASKED');

    const dimensionKeys = dimensionList.reduce((dimensionChangeList: string[], item: any) => {
      if (dimensionAuth.includes(item.bizName)) {
        dimensionChangeList.push(wrapperTransTypeAndId(TransType.DIMENSION, item.id));
      }
      return dimensionChangeList;
    }, []);
    const metricKeys = metricList.reduce((metricChangeList: string[], item: any) => {
      if (metricAuth.includes(item.bizName)) {
        metricChangeList.push(wrapperTransTypeAndId(TransType.METRIC, item.id));
      }
      return metricChangeList;
    }, []);
    setSelectedKeyList([...dimensionKeys, ...metricKeys]);
  }, [permissonData]);

  const renderFooter = () => {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Space>
          <Button onClick={onCancel}>取消</Button>
          <Button
            type="primary"
            onClick={() => {
              saveAuth();
            }}
          >
            完成
          </Button>
        </Space>
      </div>
    );
  };

  return (
    <>
      <Modal
        width={1280}
        className={styles.permissionModal}
        destroyOnClose
        title={permissonData?.groupId ? '编辑授权' : '新建授权'}
        maskClosable={false}
        open={visible}
        footer={renderFooter()}
        onCancel={onCancel}
      >
        <div style={{ margin: '0 auto', width: '100%' }}>
          <Space direction="vertical" style={{ width: '100%' }} size={20}>
            <ProCard title="基本信息" bordered>
              <PermissionCreateForm
                ref={basicInfoFormRef}
                permissonData={permissonData}
                onValuesChange={(_, values) => setBasicInfoValues(values)}
              />
            </ProCard>

            <ProCard title="列权限" bordered tooltip="仅对敏感度为高的指标/维度进行授权">
              <Form.Item label="所选字段访问模式" style={{ maxWidth: 320 }}>
                <Select
                  value={columnAccessMode}
                  onChange={setColumnAccessMode}
                  options={[
                    { value: 'MASKED', label: '允许查询并脱敏' },
                    { value: 'RAW', label: '允许原值（高风险）' },
                    { value: 'DENY', label: '禁止访问' },
                  ]}
                />
              </Form.Item>
              <DimensionMetricVisibleTransfer
                titles={['未授权维度/指标', '已授权维度/指标']}
                listStyle={{
                  width: 520,
                  height: 600,
                }}
                sourceList={[
                  ...dimensionList
                    .map((item) => {
                      const transType = TransType.DIMENSION;
                      const { id } = item;
                      return {
                        ...item,
                        transType,
                        key: wrapperTransTypeAndId(transType, id),
                      };
                    })
                    .filter((item) => item.sensitiveLevel === 2),
                  ...metricList
                    .map((item) => {
                      const transType = TransType.METRIC;
                      const { id } = item;
                      return {
                        ...item,
                        transType,
                        key: wrapperTransTypeAndId(transType, id),
                      };
                    })
                    .filter((item) => item.sensitiveLevel === 2),
                ]}
                targetList={selectedKeyList}
                onChange={(newTargetKeys: string[]) => {
                  setSelectedKeyList(newTargetKeys);
                  const dimensionKeyChangeList = dimensionList.reduce(
                    (dimensionChangeList: string[], item: any) => {
                      if (
                        newTargetKeys.includes(wrapperTransTypeAndId(TransType.DIMENSION, item.id))
                      ) {
                        dimensionChangeList.push(item.bizName);
                      }
                      return dimensionChangeList;
                    },
                    [],
                  );
                  const metricKeyChangeList = metricList.reduce(
                    (metricChangeList: string[], item: any) => {
                      if (
                        newTargetKeys.includes(wrapperTransTypeAndId(TransType.METRIC, item.id))
                      ) {
                        metricChangeList.push(item.bizName);
                      }
                      return metricChangeList;
                    },
                    [],
                  );
                  setSelectedDimensionKeyList(dimensionKeyChangeList);
                  setSelectedMetricKeyList(metricKeyChangeList);
                }}
              />
            </ProCard>

            <ProCard bordered title="行权限">
              <div>
                <Form form={form} layout="vertical">
                  <FormItem name="dimensionFilters" label="表达式">
                    <SqlEditor height={'150px'} />
                  </FormItem>
                  <FormItem name="dimensionFilterDescription" label="描述">
                    <TextArea placeholder="行权限描述" />
                  </FormItem>
                  <FormItem
                    name="rowFilterRulesJson"
                    label="结构化行规则（JSON）"
                    tooltip="数组元素包含 field、operator、values、valueSource"
                  >
                    <TextArea
                      autoSize={{ minRows: 3, maxRows: 8 }}
                      placeholder="可选，使用白名单字段和操作符"
                    />
                  </FormItem>
                </Form>
              </div>
            </ProCard>
            <ProCard bordered title="生效范围预览">
              <PermissionScopePreview
                values={basicInfoValues}
                dimensionCount={selectedDimensionKeyList.length}
                metricCount={selectedMetricKeyList.length}
                rowFilter={watchedRowFilter}
              />
            </ProCard>
          </Space>
        </div>
      </Modal>
    </>
  );
};

export default PermissionCreateModal;
