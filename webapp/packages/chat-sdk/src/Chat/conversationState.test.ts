import { mergeHistoryMessages, selectInitialConversation } from './conversationState';
import { MessageTypeEnum } from './type';

describe('conversation state', () => {
  const conversations = [
    { chatId: 12, chatName: '最新会话' },
    { chatId: 8, chatName: '历史会话' },
  ];

  it('restores a stored conversation when it still belongs to the agent', () => {
    expect(selectInitialConversation(conversations, '8')?.chatId).toBe(8);
  });

  it('falls back to the first conversation for stale or invalid storage', () => {
    expect(selectInitialConversation(conversations, '99')?.chatId).toBe(12);
    expect(selectInitialConversation(conversations, 'invalid')?.chatId).toBe(12);
  });

  it('replaces messages when the first history page is loaded', () => {
    const result = mergeHistoryMessages(
      [{ id: 1, type: MessageTypeEnum.QUESTION }],
      [{ id: 2, type: MessageTypeEnum.QUESTION }],
      1
    );
    expect(result.map(item => item.id)).toEqual([2]);
  });

  it('prepends older pages and removes duplicate questions', () => {
    const result = mergeHistoryMessages(
      [
        { id: 2, type: MessageTypeEnum.QUESTION },
        { id: 3, type: MessageTypeEnum.QUESTION },
      ],
      [
        { id: 1, type: MessageTypeEnum.QUESTION },
        { id: 2, type: MessageTypeEnum.QUESTION },
      ],
      2
    );
    expect(result.map(item => item.id)).toEqual([1, 2, 3]);
  });
});
