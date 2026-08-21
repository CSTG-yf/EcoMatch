import RightContent from '@/components/RightContent';
import S2Icon, { ICON } from '@/components/S2Icon';
import { Space, Spin, ConfigProvider } from 'antd';
import ScaleLoader from 'react-spinners/ScaleLoader';
import { history, RunTimeLayoutConfig } from '@umijs/max';
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

/**
 * 银行问数前端重构（第一步：仅菜单归组，不增删路由/页面）。
 * 用户：问数、分析看板、指标市场、导出中心；
 * 管理员（superAdmin）：额外看到"管理中心"顶级入口，点击进入后左侧边栏平铺展示
 * 全部管理页面（不使用多级级联菜单）；安全审计码持有者可看到安全运营。
 * 所有路由保持注册不变，管理员直连 URL 仍可访问，普通用户按权限拦截。
 */
const buildRoleMenu = (menuData: any[], initialState: any) => {
  const byPath = (path: string) =>
    menuData.find(
      (item: any) =>
        (item.path === path || item.path === `${path}/`) &&
        !item.hideInMenu &&
        !item.redirect,
    );
  const pick = (paths: string[]) => paths.map(byPath).filter(Boolean);

  const userItems = pick(['/chat', '/dashboard', '/metric', '/exports']);

  const isAdmin = !!initialState?.currentUser?.superAdmin;
  const hasSecurityAudit = (initialState?.authCodes || []).includes(
    ROUTE_AUTH_CODES.SECURITY_AUDIT,
  );

  if (!isAdmin) {
    return hasSecurityAudit ? [...userItems, ...pick(['/security'])] : userItems;
  }

  // 管理中心：侧边栏平铺全部管理页，顺序按职责域排列（问数配置 / 数据与口径 /
  // 平台与连接 / 安全与评估），不做多级目录。
  const adminItems = pick([
    '/agent',
    '/plugin',
    '/model',
    '/governance',
    '/tag',
    '/database',
    '/llm',
    '/system',
    '/security',
    '/evaluation',
  ]);

  return [
    ...userItems,
    // path 落在第一个管理页（助理管理）：点击进入后侧边栏平铺全部管理项
    ...(adminItems.length > 0
      ? [{ name: '管理中心', path: '/agent', children: adminItems }]
      : []),
  ];
};

export const layout: RunTimeLayoutConfig = (params) => {
  const { initialState } = params as any;
  return {
    menuDataRender: (menuData: any[]) => buildRoleMenu(menuData, initialState),
    onMenuHeaderClick: (e) => {
      e.preventDefault();
      history.push(replaceRoute);
    },
    logo: (
      <Space>
        <S2Icon
          icon={ICON.iconlogobiaoshi}
          size={30}
          color="#1672fa"
          style={{ display: 'inline-block', marginTop: 8 }}
        />
        <div className="logo" style={{ position: 'relative', top: '-2px' }}>
          SuperSonic
        </div>
      </Space>
    ),
    contentStyle: { ...(initialState?.contentStyle || {}) },
    rightContentRender: () => <RightContent />,
    disableContentMargin: true,
    // menuHeaderRender: undefined,
    childrenRender: (dom) => {
      return (
        <ConfigProvider theme={configProviderTheme}>
          <div
            style={{
              height: location.pathname.includes('chat') ? 'calc(100vh - 56px)' : undefined,
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
