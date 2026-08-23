import { Tabs } from 'antd';
import React, { useRef, useEffect } from 'react';
import { useModel } from '@umijs/max';
import ClassDimensionTable from './ClassDimensionTable';
import ClassMetricTable from './ClassMetricTable';
import PermissionAdminForm from './Permission/PermissionAdminForm';
import PermissionTable from './Permission/PermissionTable';
import styles from './style.less';
import { ISemantic } from '../data';
import {
  DATA_PERMISSION_SETTING_KEY,
  MODEL_MEMBER_SETTING_KEY,
  normalizeModelMenuKey,
} from '../utils';

type Props = {
  activeKey: string;
  modelList: ISemantic.IModelItem[];
  onMenuChange?: (menuKey: string) => void;
};
const ModelManagerTab: React.FC<Props> = ({ activeKey, onMenuChange }) => {
  const initState = useRef<boolean>(false);
  const defaultTabKey = 'metric';
  const modelModel = useModel('SemanticModel.modelData');
  const { initialState } = useModel('@@initialState');

  const { selectModelId } = modelModel;
  const isSuperAdmin = Boolean((initialState?.currentUser as any)?.superAdmin);

  useEffect(() => {
    initState.current = false;
  }, [selectModelId]);

  const isModelItem = [
    {
      label: '指标管理',
      key: 'metric',
      children: (
        <ClassMetricTable
          onEmptyMetricData={() => {
            if (!initState.current) {
              initState.current = true;
              onMenuChange?.('dimension');
            }
          }}
        />
      ),
    },
    {
      label: '维度管理',
      key: 'dimension',
      children: <ClassDimensionTable />,
    },
    {
      label: '模型成员与使用范围',
      key: MODEL_MEMBER_SETTING_KEY,
      children: <PermissionAdminForm permissionTarget="model" />,
    },
    ...(isSuperAdmin
      ? [
          {
            label: '细粒度数据授权组',
            key: DATA_PERMISSION_SETTING_KEY,
            children: <PermissionTable />,
          },
        ]
      : []),
  ];

  const getActiveKey = () => {
    const key = normalizeModelMenuKey(activeKey || defaultTabKey);
    const tabItemsKeys = isModelItem.map((item) => item.key);
    if (!tabItemsKeys.includes(key)) {
      return tabItemsKeys[0];
    }
    return key;
  };

  return (
    <div>
      <Tabs
        className={styles.tab}
        items={isModelItem}
        activeKey={getActiveKey()}
        size="large"
        onChange={(menuKey: string) => {
          onMenuChange?.(normalizeModelMenuKey(menuKey));
        }}
      />
    </div>
  );
};

export default ModelManagerTab;
