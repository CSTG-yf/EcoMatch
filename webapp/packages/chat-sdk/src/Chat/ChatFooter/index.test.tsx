import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { searchRecommend } from '../../service';
import ChatFooter from './index';

jest.mock('../../utils/utils', () => ({
  ...jest.requireActual('../../utils/utils'),
  getTextWidth: jest.fn(() => 0),
  isMobile: false,
}));

jest.mock('../../service', () => ({
  searchRecommend: jest.fn(),
}));

const agents = [
  { id: 1, name: '银行问数', description: '银行指标查询' },
  { id: 2, name: '风险问数', description: '风险指标查询' },
] as any[];

const renderFooter = (props: Record<string, any> = {}) => {
  const callbacks = {
    onToggleHistoryVisible: jest.fn(),
    onOpenAgents: jest.fn(),
    onInputMsgChange: jest.fn(),
    onSendMsg: jest.fn(),
    onSelectAgent: jest.fn(),
  };
  const view = render(
    <ChatFooter
      inputMsg=""
      chatId={12}
      agentList={agents}
      currentAgent={agents[0]}
      {...callbacks}
      {...props}
    />
  );
  return { ...view, callbacks };
};

describe('desktop chat footer agent selection', () => {
  beforeEach(() => {
    (searchRecommend as jest.Mock).mockResolvedValue({ data: [] });
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

  it('opens the desktop selector and switches through the shared callback', async () => {
    const { callbacks } = renderFooter();

    fireEvent.click(screen.getByRole('button', { name: '选择助理' }));
    fireEvent.click(await screen.findByRole('button', { name: /风险问数/ }));

    expect(callbacks.onSelectAgent).toHaveBeenCalledWith(agents[1]);
  });

  it('keeps the selected agent label visible', () => {
    renderFooter();

    expect(screen.getByText('【银行问数】将与您对话')).toBeInTheDocument();
  });

  it('shows agent list error and empty states in the selector', () => {
    const { rerender } = renderFooter({ agentError: '助理列表加载失败，请重试' });
    fireEvent.click(screen.getByRole('button', { name: '选择助理' }));
    expect(screen.getByText('助理列表加载失败，请重试')).toBeInTheDocument();

    rerender(
      <ChatFooter
        inputMsg=""
        agentList={[]}
        onToggleHistoryVisible={jest.fn()}
        onOpenAgents={jest.fn()}
        onInputMsgChange={jest.fn()}
        onSendMsg={jest.fn()}
        onSelectAgent={jest.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: '选择助理' }));
    expect(screen.getByText('暂无可用助理')).toBeInTheDocument();
  });

  it('disables input, recommendations and sending without a ready conversation', async () => {
    const { callbacks } = renderFooter({
      currentAgent: undefined,
      chatId: undefined,
      disabled: true,
      inputMsg: '查询贷款余额',
    });

    expect(screen.getByRole('combobox')).toBeDisabled();
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() => expect(searchRecommend).not.toHaveBeenCalled());
    expect(callbacks.onSendMsg).not.toHaveBeenCalled();
    expect(screen.getByText('请选择助理后开始对话')).toBeInTheDocument();
  });

  it('sends only when agent and conversation are ready', () => {
    const { callbacks } = renderFooter({ inputMsg: '查询贷款余额', disabled: false });

    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(callbacks.onSendMsg).toHaveBeenCalledWith('查询贷款余额', undefined);
  });
});
