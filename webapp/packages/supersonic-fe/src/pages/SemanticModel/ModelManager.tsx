import React, { useEffect, useState } from 'react';
import { useParams, useModel } from '@umijs/max';
import ModelManagerTab from './components/ModelManagerTab';
import { normalizeModelMenuKey, toModelList } from '@/pages/SemanticModel/utils';

type Props = {};

const ModelManager: React.FC<Props> = ({}) => {
  const defaultTabKey = 'metric';
  const params: any = useParams();
  const modelId = params.modelId;
  const domainModel = useModel('SemanticModel.domainData');
  const modelModel = useModel('SemanticModel.modelData');
  const dimensionModel = useModel('SemanticModel.dimensionData');
  const metricModel = useModel('SemanticModel.metricData');
  const { selectDomainId } = domainModel;
  const { selectModelId, modelList } = modelModel;
  const { MrefreshDimensionList } = dimensionModel;
  const { MrefreshMetricList } = metricModel;
  const menuKey = normalizeModelMenuKey(params.menuKey) || defaultTabKey;
  const [activeKey, setActiveKey] = useState<string>(menuKey);

  const initModelConfig = () => {
    const currentMenuKey = menuKey === defaultTabKey ? '' : menuKey;
    toModelList(selectDomainId, selectModelId!, currentMenuKey);
    setActiveKey(menuKey);
  };

  useEffect(() => {
    setActiveKey(menuKey);
  }, [menuKey]);

  useEffect(() => {
    if (!selectModelId || `${selectModelId}` === `${modelId}`) {
      return;
    }
    initModelConfig();
    MrefreshDimensionList({ modelId: selectModelId });
    MrefreshMetricList({ modelId: selectModelId });
  }, [selectModelId]);

  return (
    <ModelManagerTab
      activeKey={activeKey}
      modelList={modelList}
      onMenuChange={(menuKey) => {
        const normalizedMenuKey = normalizeModelMenuKey(menuKey) || defaultTabKey;
        setActiveKey(normalizedMenuKey);
        toModelList(selectDomainId, selectModelId!, normalizedMenuKey);
      }}
    />
  );
};

export default ModelManager;
