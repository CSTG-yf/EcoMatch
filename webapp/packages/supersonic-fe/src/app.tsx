import AvatarDropdown from '@/components/RightContent/AvatarDropdown';
import { Spin, ConfigProvider } from 'antd';
import ScaleLoader from 'react-spinners/ScaleLoader';
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { history, RunTimeLayoutConfig, useModel } from '@umijs/max';
import defaultSettings from '../config/defaultSettings';
import settings from '../config/themeSettings';
import { queryCurrentUser } from './services/user';
import { deleteUrlQuery, isMobile, getToken } from '@/utils/utils';
import { publicPath } from '../config/defaultSettings';
import type { DefaultSetting } from '../config/defaultSettings';
import { Copilot } from 'supersonic-chat-sdk';
import { configProviderTheme } from '../config/themeSettings';
export { request } from './services/request';
import { BASE_TITLE } from '@/common/constants';
import { ROUTE_AUTH_CODES } from '../config/routes';
import AppPage from './pages/index';
import { canViewDeveloperDiagnostics } from '@/utils/developerAccess';

const replaceRoute = '/';

const getRunningEnv = async () => {
  try {
    const response = await fetch(`${publicPath}supersonic.config.json`);
    const config = await response.json();
    return config;
  } catch (error) {
    console.warn('无法获取配置文件: 运行时环境将以semantic启动');
  }
};

Spin.setDefaultIndicator(
  <ScaleLoader color={settings['primary-color']} height={25} width={2} radius={2} margin={2} />,
);

const getAuthCodes = (params: any) => {
  const { currentUser } = params;
  const codes = [];
  if (currentUser?.superAdmin) {
    codes.push(ROUTE_AUTH_CODES.SYSTEM_ADMIN);
    codes.push(ROUTE_AUTH_CODES.SECURITY_AUDIT);
  }
  const roles = new Set<string>(currentUser?.roles || []);
  if (['SECURITY_ADMIN', 'SECURITY_AUDITOR', 'RISK_AUDITOR'].some((role) => roles.has(role))) {
    codes.push(ROUTE_AUTH_CODES.SECURITY_AUDIT);
  }
  return codes;
};

export async function getInitialState(): Promise<{
  settings?: DefaultSetting;
  currentUser?: API.CurrentUser;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  codeList?: string[];
  authCodes?: string[];
}> {
  const fetchUserInfo = async () => {
    try {
      const { code, data } = await queryCurrentUser();
      if (code === 200) {
        return { ...data, staffName: data.staffName || data.name };
      }
    } catch (error) {}
    return undefined;
  };

  let currentUser: any;
  if (!window.location.pathname.includes('login')) {
    currentUser = await fetchUserInfo();
  }

  if (currentUser) {
    localStorage.setItem('user', currentUser.staffName);
    if (currentUser.orgName) {
      localStorage.setItem('organization', currentUser.orgName);
    }
  }

  const authCodes = getAuthCodes({
    currentUser,
  });

  return {
    fetchUserInfo,
    currentUser,
    settings: defaultSettings,
    authCodes,
  };
}

// export async function patchRoutes({ routes }) {
//   const config = await getRunningEnv();
//   if (config && config.env) {
//     window.RUNNING_ENV = config.env;
//     const { env } = config;
//     const target = routes[0].routes;
//     if (env) {
//       const envRoutes = traverseRoutes(target, env);
//       // 清空原本route;
//       target.splice(0, 99);
//       // 写入根据环境转换过的的route
//       target.push(...envRoutes);
//     }
//   } else {
//     const target = routes[0].routes;
//     // start-standalone模式不存在env，在此模式下不显示chatSetting
//     const envRoutes = target.filter((item: any) => {
//       return !['chatSetting'].includes(item.name);
//     });
//     target.splice(0, 99);
//     target.push(...envRoutes);
//   }
// }

export function onRouteChange() {
  setTimeout(() => {
    let title = window.document.title;
    if (!title.toLowerCase().endsWith(BASE_TITLE.toLowerCase())) {
      window.document.title = `${title}-${BASE_TITLE}`;
    }
  }, 100);
}

// 侧边栏头部：logo + 折叠按钮（折叠态堆叠为 图标/按钮 两行）
const SiderHeader: React.FC<{ logoDom: React.ReactNode; collapsed?: boolean }> = ({
  logoDom,
  collapsed,
}) => {
  const { setInitialState } = useModel('@@initialState');
  const toggle = () =>
    setInitialState((state: any) => ({ ...state, siderCollapsed: !collapsed }));
  return (
    <div className="sider-header-wrap">
      {collapsed ? (
        <img
          src={`${publicPath}branding/bank-query-avatar.svg`}
          alt="银行问数"
          width={30}
          height={30}
        />
      ) : (
        logoDom
      )}
      <span
        className="sider-collapse-trigger"
        title={collapsed ? '展开侧边栏' : '收起侧边栏'}
        onClick={toggle}
      >
        {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
      </span>
    </div>
  );
};

export const layout: RunTimeLayoutConfig = (params) => {
  const { initialState, setInitialState } = params as any;
  const siderCollapsed = Boolean((initialState as any)?.siderCollapsed);
  return {
    onMenuHeaderClick: (e) => {
      e.preventDefault();
      history.push(replaceRoute);
    },
    // 侧边栏头部：logo + 折叠按钮
    menuHeaderRender: (logoDom, _titleDom, props) => (
      <SiderHeader logoDom={logoDom} collapsed={props?.collapsed} />
    ),
    collapsed: siderCollapsed,
    onCollapse: (collapsed) => setInitialState({ ...initialState, siderCollapsed: collapsed }),
    collapsedButtonRender: false,
    logo: (
      <img
        src={`${publicPath}branding/bank-query-logo.svg`}
        alt="银行问数"
        className="brand-logo"
      />
    ),
    contentStyle: { background: '#fff', ...(initialState?.contentStyle || {}) },
    // 侧边栏布局：顶部不需要 header，账户入口收进侧边栏底部
    headerRender: false,
    menuFooterRender: (props) =>
      props?.collapsed ? (
        <div className="sider-account" style={{ textAlign: 'center' }}>
          <AvatarDropdown hideName />
        </div>
      ) : (
        <div className="sider-account" style={{ padding: '0 16px' }}>
          <AvatarDropdown />
        </div>
      ),
    disableContentMargin: true,
    childrenRender: (dom) => {
      return (
        <ConfigProvider theme={configProviderTheme}>
          <div
            style={{
              height: location.pathname.includes('chat') ? '100vh' : undefined,
            }}
          >
            {/* <AppPage dom={dom} /> */}
            {dom}
            {history.location.pathname !== '/chat' && !isMobile && (
              <Copilot
                token={getToken() || ''}
                isDeveloper={canViewDeveloperDiagnostics(initialState?.currentUser)}
              />
            )}
          </div>
        </ConfigProvider>
      );
    },
    ...initialState?.settings,
  };
};
