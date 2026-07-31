import { history, useLocation, useModel } from '@umijs/max';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat, DashboardQuerySource } from 'supersonic-chat-sdk';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';
import { message } from 'antd';
import { getDashboardModel } from '../Dashboard/service';

const ChatPage = () => {
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const query = queryString.parse(location.search) || {};
  const { agentId } = query;

  const saveToDashboard = async (source: DashboardQuerySource) => {
    try {
      const modelId = Number(source.modelId || source.semanticQuery?.modelId);
      if (!Number.isInteger(modelId) || modelId <= 0) {
        throw new Error('无法确认问数结果所属模型');
      }
      const response: any = await getDashboardModel(modelId);
      if (response?.code != null && Number(response.code) !== 200) {
        throw response;
      }
      const models = response?.data || response;
      const domainId = Number(Array.isArray(models) ? models[0]?.domainId : models?.domainId);
      if (!Number.isInteger(domainId) || domainId <= 0) {
        throw new Error('无法确认问数结果所属主题域');
      }
      history.push('/dashboard', { source: { ...source, domainId } });
    } catch (error: any) {
      message.error(error?.msg || error?.message || '无法确认问数结果所属主题域');
    }
  };

  return (
    <Chat
      initialAgentId={agentId ? +agentId : undefined}
      token={getToken() || ''}
      isDeveloper={canViewDeveloperDiagnostics(initialState?.currentUser)}
      onSaveToDashboard={saveToDashboard}
    />
  );
};

export default ChatPage;
