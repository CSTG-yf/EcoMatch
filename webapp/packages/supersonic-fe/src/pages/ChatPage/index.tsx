import { history, useLocation, useModel } from '@umijs/max';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat } from 'supersonic-chat-sdk';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';

const ChatPage = () => {
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const query = queryString.parse(location.search) || {};
  const { agentId } = query;

  return (
    <Chat
      initialAgentId={agentId ? +agentId : undefined}
      token={getToken() || ''}
      isDeveloper={canViewDeveloperDiagnostics(initialState?.currentUser)}
      onSaveToDashboard={(source) => history.push('/dashboard', { source })}
    />
  );
};

export default ChatPage;
