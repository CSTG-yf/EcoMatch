export const MODEL_MEMBER_SETTING_KEY = 'modelMemberSetting';
export const DATA_PERMISSION_SETTING_KEY = 'dataPermissionSetting';
export const LEGACY_MODEL_PERMISSION_KEY = 'permissonSetting';

export const normalizeModelMenuKey = (menuKey: string = ''): string => {
  return menuKey === LEGACY_MODEL_PERMISSION_KEY ? MODEL_MEMBER_SETTING_KEY : menuKey;
};

export const buildModelManagerPath = (
  domainId: number,
  modelId: number,
  menuKey?: string,
): string => {
  const normalizedMenuKey = normalizeModelMenuKey(menuKey);
  return `/model/domain/manager/${domainId}/${modelId}${
    normalizedMenuKey ? `/${normalizedMenuKey}` : ''
  }`;
};
