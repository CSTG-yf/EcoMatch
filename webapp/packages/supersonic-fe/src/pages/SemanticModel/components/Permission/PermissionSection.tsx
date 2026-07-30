import { Space } from 'antd';
import React from 'react';
import { ProCard } from '@ant-design/pro-components';
import PermissionTable from './PermissionTable';
import PermissionAdminForm from './PermissionAdminForm';
import { useModel } from '@umijs/max';

type Props = {
  permissionTarget: 'model' | 'domain';
};

const PermissionSection: React.FC<Props> = ({ permissionTarget }) => {
  const { initialState } = useModel('@@initialState');
  const isSuperAdmin = Boolean((initialState?.currentUser as any)?.superAdmin);

  return (
    <>
      <div>
        <Space direction="vertical" style={{ width: '100%' }} size={20}>
          <ProCard title="邀请成员" bordered>
            <PermissionAdminForm permissionTarget={permissionTarget} />
          </ProCard>
          {permissionTarget === 'model' && isSuperAdmin && <PermissionTable />}
        </Space>
      </div>
    </>
  );
};
export default PermissionSection;
