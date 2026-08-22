import { fireEvent, render, screen } from '@testing-library/react';
import ChatFooter from './index';

jest.mock('../../utils/utils', () => ({
  ...jest.requireActual('../../utils/utils'),
  getTextWidth: jest.fn(() => 0),
  isMobile: true,
}));

jest.mock('../../service', () => ({
  searchRecommend: jest.fn().mockResolvedValue({ data: [] }),
}));

const agents = [
  { id: 1, name: '银行问数', description: '银行指标查询' },
  { id: 2, name: '风险问数', description: '风险指标查询' },
] as any[];

const renderFooter = (agentList = agents) => {
  const callbacks = {
    onToggleHistoryVisible: jest.fn(),
    onOpenAgents: jest.fn(),
    onInputMsgChange: jest.fn(),
    onSendMsg: jest.fn(),
    onAddConversation: jest.fn(),
    onSelectAgent: jest.fn(),
    onOpenShowcase: jest.fn(),
  };

  const view = render(
    <ChatFooter inputMsg="" agentList={agentList} currentAgent={agentList[0]} {...callbacks} />
  );
  return { ...view, callbacks };
};

describe('mobile chat footer navigation', () => {
  beforeEach(() => {
    window.matchMedia = jest.fn().mockImplementation(query => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    }));
  });

  it('opens history and exposes Agent switching for multi-Agent deployments', () => {
    const { callbacks } = renderFooter();

    fireEvent.click(screen.getByRole('button', { name: '历史对话' }));
    fireEvent.click(screen.getByRole('button', { name: '智能助理' }));

    expect(callbacks.onToggleHistoryVisible).toHaveBeenCalledTimes(1);
    expect(callbacks.onOpenAgents).toHaveBeenCalledTimes(1);
  });

  it('does not show an Agent switcher for a single-Agent deployment', () => {
    renderFooter(agents.slice(0, 1));

    expect(screen.getByRole('button', { name: '历史对话' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '智能助理' })).not.toBeInTheDocument();
  });
});
