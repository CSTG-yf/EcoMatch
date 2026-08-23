import {
  buildModelManagerPath,
  DATA_PERMISSION_SETTING_KEY,
  LEGACY_MODEL_PERMISSION_KEY,
  MODEL_MEMBER_SETTING_KEY,
  normalizeModelMenuKey,
} from './modelNavigation';

describe('semantic model tab navigation', () => {
  it('maps the legacy permission tab to the model member tab', () => {
    expect(normalizeModelMenuKey(LEGACY_MODEL_PERMISSION_KEY)).toBe(MODEL_MEMBER_SETTING_KEY);
    expect(normalizeModelMenuKey(MODEL_MEMBER_SETTING_KEY)).toBe(MODEL_MEMBER_SETTING_KEY);
    expect(normalizeModelMenuKey(DATA_PERMISSION_SETTING_KEY)).toBe(DATA_PERMISSION_SETTING_KEY);
  });

  it('builds model manager paths for legacy and new tab keys', () => {
    expect(buildModelManagerPath(3, 5, LEGACY_MODEL_PERMISSION_KEY)).toBe(
      '/model/domain/manager/3/5/modelMemberSetting',
    );
    expect(buildModelManagerPath(3, 5, MODEL_MEMBER_SETTING_KEY)).toBe(
      '/model/domain/manager/3/5/modelMemberSetting',
    );
    expect(buildModelManagerPath(3, 5, DATA_PERMISSION_SETTING_KEY)).toBe(
      '/model/domain/manager/3/5/dataPermissionSetting',
    );
    expect(buildModelManagerPath(3, 5)).toBe('/model/domain/manager/3/5');
  });
});
