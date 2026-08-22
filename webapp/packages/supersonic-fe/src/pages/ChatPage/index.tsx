import { history, useLocation, useModel } from '@umijs/max';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat, DashboardQuerySource } from 'supersonic-chat-sdk';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';
import { message } from 'antd';
import { getDashboardModel } from '../Dashboard/service';
import { getDataSetDetail } from '../SemanticModel/service';
import { buildQueryExportRequest } from '../ExportCenter';

const ChatPage = () => {
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const query = queryString.parse(location.search) || {};
  const { agentId } = query;

  // 保存到看板只需要 domainId。两条取得路径：
  //  1) 有 modelId：model -> domain（getModelListByIds，VIEWER 校验）
  //  2) modelId 缺失（bank 投影后 metrics/dimensions 为空）但有 dataSetId：
  //     dataSet -> domainId（GET /dataSet/{id}，该接口是 ADMIN 级，superAdmin 放行）
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
        const response: any = await getDataSetDetail(dataSetId);
        if (response?.code != null && Number(response.code) !== 200) {
          throw new Error(response?.msg || '无该数据集的管理权限，无法保存到看板');
        }
        domainId = Number(response?.data?.domainId ?? response?.domainId);
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
