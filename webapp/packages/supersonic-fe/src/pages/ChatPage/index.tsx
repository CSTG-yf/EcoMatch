import { history, useLocation, useModel } from '@umijs/max';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat, DashboardQuerySource } from 'supersonic-chat-sdk';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';
import { message } from 'antd';
import { getDashboardDataSetDomain, getDashboardModel } from '../Dashboard/service';
import { buildQueryExportRequest } from '../ExportCenter';

const ChatPage = () => {
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const query = queryString.parse(location.search) || {};
  const { agentId } = query;

  // 保存到看板只需要 domainId。两条路径都只使用 VIEWER 权限接口：
  //  1) 有 modelId：model -> domain（getModelListByIds）
  //  2) modelId 缺失但有 dataSetId：dataSet -> domain
  const saveToDashboard = async (source: DashboardQuerySource) => {
    try {
      const modelId = Number(source.modelId || source.semanticQuery?.modelId);
      const dataSetId = Number(source.dataSetId || source.semanticQuery?.dataSetId);
      let domainId = NaN;

      if (Number.isInteger(modelId) && modelId > 0) {
        const response: any = await getDashboardModel(modelId);
        if (response?.code != null && Number(response.code) !== 200) {
          throw new Error(response?.msg || '无该模型的数据权限，无法保存到看板');
        }
        const models = response?.data || response;
        domainId = Number(Array.isArray(models) ? models[0]?.domainId : models?.domainId);
      } else if (Number.isInteger(dataSetId) && dataSetId > 0) {
        const response: any = await getDashboardDataSetDomain(dataSetId);
        if (response?.code != null && Number(response.code) !== 200) {
          throw new Error(response?.msg || '当前用户不可见该数据集，无法保存到看板');
        }
        domainId = Number(response?.data ?? response);
      } else {
        throw new Error('无法识别该问数结果所属的数据模型，暂不能保存到看板');
      }

      if (!Number.isInteger(domainId) || domainId <= 0) {
        throw new Error('无法识别该结果所属的主题域，暂不能保存到看板');
      }
      history.push('/dashboard', { source: { ...source, domainId } });
    } catch (error: any) {
      message.error(error?.msg || error?.message || '保存到看板失败');
    }
  };

  const exportQuery = (source: DashboardQuerySource) => {
    try {
      history.push('/exports', {
        initialRequest: buildQueryExportRequest(source, 'XLSX'),
      });
    } catch (error: any) {
      message.error(error?.message || '当前问数结果无法安全导出');
    }
  };

  return (
    <Chat
      initialAgentId={agentId ? +agentId : undefined}
      defaultAgentName="银行问数"
      token={getToken() || ''}
      isDeveloper={canViewDeveloperDiagnostics(initialState?.currentUser)}
      onSaveToDashboard={saveToDashboard}
      onExportQuery={exportQuery}
    />
  );
};

export default ChatPage;
