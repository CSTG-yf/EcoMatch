import React, { useState, useEffect } from 'react';
import { Form, Input, Switch, message } from 'antd';
import SelectPartner from '@/components/SelectPartner';
import SelectTMEPerson from '@/components/SelectTMEPerson';
import { useModel } from '@umijs/max';
import FormItemTitle from '@/components/FormHelper/FormItemTitle';
import { updateDomain, updateModel, getDomainDetail, getModelDetail } from '../../service';

import styles from '../style.less';
type Props = {
  permissionTarget: 'model' | 'domain';
  onSubmit?: (data?: any) => void;
  onValuesChange?: (value, values) => void;
};

const FormItem = Form.Item;

const PermissionAdminForm: React.FC<Props> = ({ permissionTarget, onValuesChange }) => {
  const [form] = Form.useForm();
  const [isOpenState, setIsOpenState] = useState<boolean>(true);
  const [classDetail, setClassDetail] = useState<any>({});
  const domainModel = useModel('SemanticModel.domainData');
  const modelModel = useModel('SemanticModel.modelData');
  const { selectDomainId } = domainModel;
  const { selectModelId: modelId } = modelModel;

  const queryClassDetail = async () => {
    const selectId = permissionTarget === 'model' ? modelId : selectDomainId;
    const { code, msg, data } = await (permissionTarget === 'model'
      ? getModelDetail
      : getDomainDetail)({ modelId: selectId });
    if (code === 200) {
      setClassDetail(data);
      const fieldsValue = {
        ...data,
      };
      fieldsValue.admins = fieldsValue.admins || [];
      fieldsValue.adminOrgs = fieldsValue.adminOrgs || [];
      fieldsValue.viewers = fieldsValue.viewers || [];
      fieldsValue.viewOrgs = fieldsValue.viewOrgs || [];
      fieldsValue.isOpen = !!fieldsValue.isOpen;
      setIsOpenState(fieldsValue.isOpen);
      form.setFieldsValue(fieldsValue);
      return;
    }
    message.error(msg);
  };

  useEffect(() => {
    queryClassDetail();
  }, [modelId, selectDomainId]);

  const saveAuth = async () => {
    const values = await form.validateFields();
    const { admins, adminOrgs, isOpen, viewOrgs = [], viewers = [] } = values;
    const queryClassData = {
      ...classDetail,
      admins,
      adminOrgs,
      viewOrgs,
      viewers,
      isOpen: isOpen ? 1 : 0,
    };
    const { code, msg } = await (permissionTarget === 'model' ? updateModel : updateDomain)(
      queryClassData,
    );
    if (code === 200) {
      return;
    }
    message.error(msg);
  };

  return (
    <>
      <Form
        form={form}
        layout="vertical"
        onValuesChange={(value, values) => {
          const { isOpen } = value;
          if (isOpen !== undefined) {
            setIsOpenState(isOpen);
          }
          saveAuth();
          onValuesChange?.(value, values);
        }}
        className={styles.form}
      >
        <FormItem hidden={true} name="groupId" label="ID">
          <Input placeholder="groupId" />
        </FormItem>
        <FormItem
          name="admins"
          label={
            <FormItemTitle
              title={permissionTarget === 'model' ? '模型管理员' : '主题域管理员'}
              subTitle={
                permissionTarget === 'model'
                  ? '管理员拥有模型范围内的编辑及访问权限。'
                  : '管理员拥有主题域范围内的编辑及访问权限。'
              }
            />
          }
        >
          <SelectTMEPerson placeholder="请邀请团队成员" />
        </FormItem>
        {/* {APP_TARGET === 'inner'} */}
        <FormItem name="adminOrgs" label="管理员组织范围">
          <SelectPartner
            type="selectedDepartment"
            treeSelectProps={{
              placeholder: '请选择需要授权的部门',
            }}
          />
        </FormItem>
        <Form.Item
          label={
            <FormItemTitle
              title={'设为公开'}
              subTitle={
                permissionTarget === 'model'
                  ? '公开后，所有用户可使用模型范围内的低/中敏感度资源，高敏感度资源需通过细粒度数据授权组授权。'
                  : '公开后，所有用户可访问主题域范围内的低/中敏感度资源，高敏感度资源仍按主题域成员与访问范围控制。'
              }
            />
          }
          name="isOpen"
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
        {!isOpenState && (
          <>
            {/* {APP_TARGET === 'inner' && } */}
            <FormItem name="viewOrgs" label="成员组织范围">
              <SelectPartner
                type="selectedDepartment"
                treeSelectProps={{
                  placeholder: '请选择需要授权的部门',
                }}
              />
            </FormItem>
            <FormItem name="viewers" label="成员个人范围">
              <SelectTMEPerson placeholder="请选择需要授权的个人" />
            </FormItem>
          </>
        )}
      </Form>
    </>
  );
};

export default PermissionAdminForm;
