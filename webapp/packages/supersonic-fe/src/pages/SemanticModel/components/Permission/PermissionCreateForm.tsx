import { useEffect, useImperativeHandle, forwardRef } from 'react';
import { Form, Input, InputNumber, Select, Switch } from 'antd';
import type { ForwardRefRenderFunction } from 'react';
import SelectPartner from '@/components/SelectPartner';
import SelectTMEPerson from '@/components/SelectTMEPerson';
import { formLayout } from '@/components/FormHelper/utils';
import styles from '../style.less';
import AttributeConditionEditor from './AttributeConditionEditor';
type Props = {
  permissonData: any;
  onSubmit?: (data?: any) => void;
  onValuesChange?: (value: any, values: any) => void;
};

const FormItem = Form.Item;

const PermissionCreateForm: ForwardRefRenderFunction<any, Props> = (
  { permissonData, onValuesChange },
  ref,
) => {
  const [form] = Form.useForm();

  useImperativeHandle(ref, () => ({
    formRef: form,
  }));

  useEffect(() => {
    const fieldsValue = {
      ...permissonData,
    };
    fieldsValue.authorizedDepartmentIds = permissonData.authorizedDepartmentIds || [];
    fieldsValue.authorizedUsers = permissonData.authorizedUsers || [];
    fieldsValue.authorizedRoles = permissonData.authorizedRoles || [];
    fieldsValue.attributeConditionEntries = Object.entries(
      permissonData.attributeConditions || {},
    ).map(([key, value]) => ({ key, value }));
    form.setFieldsValue(fieldsValue);
    onValuesChange?.({}, fieldsValue);
  }, [permissonData]);

  return (
    <>
      <Form
        {...formLayout}
        key={permissonData.groupId}
        form={form}
        layout="vertical"
        onValuesChange={(value, values) => {
          onValuesChange?.(value, values);
        }}
        className={styles.form}
      >
        <FormItem hidden={true} name="groupId" label="ID">
          <Input placeholder="groupId" />
        </FormItem>
        <FormItem name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="请输入名称" />
        </FormItem>
        <FormItem name="authorizedDepartmentIds" label="按组织">
          <SelectPartner
            type="selectedDepartment"
            treeSelectProps={{
              placeholder: '请选择需要授权的部门',
            }}
          />
        </FormItem>

        <FormItem name="authorizedUsers" label="按个人">
          <SelectTMEPerson placeholder="请选择需要授权的个人" />
        </FormItem>
        <FormItem
          name="authorizedRoles"
          label="按角色"
          tooltip="角色名称必须与登录令牌中的角色声明完全一致"
        >
          <Select mode="tags" tokenSeparators={[',']} placeholder="输入角色后回车" />
        </FormItem>
        <FormItem name="effect" label="策略效果" initialValue="ALLOW">
          <Select
            options={[
              { value: 'ALLOW', label: '允许' },
              { value: 'DENY', label: '拒绝' },
            ]}
          />
        </FormItem>
        <FormItem name="orgScope" label="机构范围" initialValue="CURRENT">
          <Select
            options={[
              { value: 'CURRENT', label: '当前机构' },
              { value: 'CURRENT_AND_CHILDREN', label: '当前机构及下级' },
              { value: 'CUSTOM', label: '指定机构集合' },
            ]}
          />
        </FormItem>
        <FormItem name="priority" label="优先级" initialValue={0}>
          <InputNumber min={0} max={10000} style={{ width: '100%' }} />
        </FormItem>
        <FormItem name="enabled" label="启用策略" valuePropName="checked" initialValue={true}>
          <Switch />
        </FormItem>
        <FormItem label="按用户属性" tooltip="所有属性条件同时满足时策略才会生效">
          <AttributeConditionEditor />
        </FormItem>
      </Form>
    </>
  );
};

export default forwardRef(PermissionCreateForm);
