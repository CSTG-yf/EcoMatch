import {
  buildContinuationDraft,
  findConversationAgent,
  findConversationByChatId,
  getConversationDisplayTitle,
  matchesConversationSearch,
  mergeHistoryMessages,
} from './conversationState';
import { MessageTypeEnum } from './type';

describe('conversation state', () => {
  const agents = [
    { id: 1, name: '银行问数' },
    { id: 2, name: '风险助理' },
  ] as any[];
  const conversations = [
    { chatId: 12, agentId: 1, chatName: '新问答对话', lastQuestion: '查询贷款余额' },
    { chatId: 8, agentId: 2, chatName: '风险专题', lastQuestion: '不良率是多少' },
  ];

  it('selects a newly saved conversation by its returned chat id', () => {
    expect(findConversationByChatId(conversations, 8)?.chatName).toBe('风险专题');
    expect(findConversationByChatId(conversations, '12')?.agentId).toBe(1);
    expect(findConversationByChatId(conversations, undefined)).toBeUndefined();
  });

  it('binds historical conversations to their authorized agent', () => {
    expect(findConversationAgent(conversations[1], agents)?.name).toBe('风险助理');
    expect(
      findConversationAgent({ chatId: 9, agentId: 99, chatName: '未知助理会话' }, agents)
    ).toBeUndefined();
  });

  it('uses the agent name only for legacy default titles', () => {
    expect(getConversationDisplayTitle(conversations[0], agents)).toBe('银行问数');
    expect(getConversationDisplayTitle(conversations[1], agents)).toBe('风险专题');
  });

  it('searches display title, agent name and last question', () => {
    expect(matchesConversationSearch(conversations[0], agents, '银行')).toBe(true);
    expect(matchesConversationSearch(conversations[1], agents, '风险助理')).toBe(true);
    expect(matchesConversationSearch(conversations[1], agents, '不良率')).toBe(true);
    expect(matchesConversationSearch(conversations[1], agents, '贷款')).toBe(false);
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

  it('builds a continuation draft without retyping the historical question', () => {
    expect(buildContinuationDraft('查询南京分行贷款余额')).toBe(
      '基于“查询南京分行贷款余额”继续提问：'
    );
  });
});
