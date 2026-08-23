import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { deleteConversation, getAllConversations } from '../service';
import Conversation from './index';

jest.mock('../service', () => ({
  deleteConversation: jest.fn(),
  getAllConversations: jest.fn(),
  saveConversation: jest.fn(),
}));

const agent = { id: 7, name: '银行问数' } as any;
const conversation = {
  chatId: 12,
  agentId: 7,
  chatName: '季度分析',
  lastQuestion: '贷款余额',
  lastTime: '2026-08-23 10:00:00',
} as any;

describe('conversation deletion', () => {
  beforeEach(() => {
    (getAllConversations as jest.Mock)
      .mockResolvedValueOnce({ data: [conversation] })
      .mockResolvedValue({ data: [] });
    (deleteConversation as jest.Mock).mockResolvedValue({ data: true });
  });

  it('notifies the parent when the selected conversation is deleted', async () => {
    const onConversationDeleted = jest.fn();
    render(
      <Conversation
        agentList={[agent]}
        currentAgent={agent}
        currentConversation={conversation}
        onSelectConversation={jest.fn()}
        onRequestAgentSelection={jest.fn()}
        onCloseConversation={jest.fn()}
        onInitializationChange={jest.fn()}
        onConversationDeleted={onConversationDeleted}
      />
    );

    expect(await screen.findByText('季度分析')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('删除会话'));

    await waitFor(() => expect(deleteConversation).toHaveBeenCalledWith(12));
    await waitFor(() => expect(onConversationDeleted).toHaveBeenCalledWith(12));
    expect(await screen.findByText('暂无历史会话')).toBeInTheDocument();
  });
});
