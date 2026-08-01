jest.mock('@/services/request', () => ({
  __esModule: true,
  default: jest.fn(),
  TOKEN_KEY: 'SUPERSONIC_TOKEN',
}));

import {
  ControlledShareAccessPage,
  ShareCreateDialog,
  SharedDashboardComponent,
  ShareManagementDialog,
} from './index';

describe('controlled share public exports', () => {
  it('exports reusable dialogs, access page and shared component renderer', () => {
    expect(ControlledShareAccessPage).toBeDefined();
    expect(ShareCreateDialog).toBeDefined();
    expect(ShareManagementDialog).toBeDefined();
    expect(SharedDashboardComponent).toBeDefined();
  });
});
