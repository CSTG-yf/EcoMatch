import { DEFAULT_CONVERSATION_NAME } from './constants';
import { AgentType, ConversationDetailType, MessageItem } from './type';

export const findConversationByChatId = (
  conversations: ConversationDetailType[],
  chatId?: number | string
) => {
  const normalizedChatId = Number(chatId);
  if (!Number.isFinite(normalizedChatId)) {
    return undefined;
  }
  return conversations.find(item => item.chatId === normalizedChatId);
};

export const findConversationAgent = (
  conversation: ConversationDetailType,
  agentList: AgentType[]
) => agentList.find(agent => agent.id === conversation.agentId);

export const getConversationDisplayTitle = (
  conversation: ConversationDetailType,
  agentList: AgentType[]
) => {
  const agent = findConversationAgent(conversation, agentList);
  return conversation.chatName === DEFAULT_CONVERSATION_NAME && agent
    ? agent.name
    : conversation.chatName;
};

export const matchesConversationSearch = (
  conversation: ConversationDetailType,
  agentList: AgentType[],
  searchValue: string
) => {
  const keyword = searchValue.trim().toLowerCase();
  if (!keyword) {
    return true;
  }
  const agentName = findConversationAgent(conversation, agentList)?.name || '';
  return [
    getConversationDisplayTitle(conversation, agentList),
    agentName,
    conversation.lastQuestion || '',
  ].some(value => value.toLowerCase().includes(keyword));
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

export const buildContinuationDraft = (question: string) => `基于“${question}”继续提问：`;
