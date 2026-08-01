import { history, useLocation, useModel } from '@umijs/max';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat, DashboardQueryDraft } from 'supersonic-chat-sdk';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';
import { DASHBOARD_DRAFT_STORAGE_KEY } from '@/pages/Dashboard/model';

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
      onSaveToDashboard={(draft: DashboardQueryDraft) => {
        sessionStorage.setItem(DASHBOARD_DRAFT_STORAGE_KEY, JSON.stringify(draft));
        history.push('/dashboard?source=query');
      }}
    />
  );
};

export default ChatPage;
