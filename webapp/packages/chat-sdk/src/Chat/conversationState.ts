import { ConversationDetailType, MessageItem } from './type';

export const selectInitialConversation = (
  conversations: ConversationDetailType[],
  storedConversationId?: string | null
) => {
  if (!storedConversationId) {
    return conversations[0];
  }
  const conversationId = Number(storedConversationId);
  return conversations.find(item => item.chatId === conversationId) || conversations[0];
};

export const mergeHistoryMessages = (
  currentMessages: MessageItem[],
  historyMessages: MessageItem[],
  page: number
) => {
  const merged = page === 1 ? historyMessages : [...historyMessages, ...currentMessages];
  const ids = new Set<string>();
  return merged.filter(item => {
    const id = String(item.id);
    if (ids.has(id)) {
      return false;
    }
    ids.add(id);
    return true;
  });
};
