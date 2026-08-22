import AvatarDropdown from '@/components/RightContent/AvatarDropdown';
import { Spin, ConfigProvider } from 'antd';
import ScaleLoader from 'react-spinners/ScaleLoader';
import { ControlOutlined, MenuOutlined } from '@ant-design/icons';
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


const MobileLayoutHeader: React.FC<{
  collapsed?: boolean;
  onCollapse?: (collapsed: boolean) => void;
}> = ({ collapsed, onCollapse }) => (
  <div className="mobile-layout-header">
    <button
      type="button"
      className="mobile-menu-trigger"
      aria-label={collapsed ? '打开主导航' : '关闭主导航'}
      aria-expanded={!collapsed}
      onClick={() => onCollapse?.(!collapsed)}
    >
      <MenuOutlined />
    </button>
    <span className="mobile-brand-logo" role="img" aria-label="银行问数">
      <img src={`${publicPath}branding/bank-query-icon.svg`} alt="" />
      <img src={`${publicPath}branding/bank-query-text.svg`} alt="" />
    </span>
  </div>
);

/**
 * 银行问数前端菜单（按角色渲染，不增删路由/页面）。
 * 用户：问数、分析看板、指标知识库、导出中心；
 * 管理员（superAdmin）：额外看到"管理中心"顶级入口，点击进入后左侧边栏平铺展示
 * 全部管理页面（无分组标题、无多级级联，2026-08-22 用户确认）；安全审计码持有者可看到安全运营。
 * 所有路由保持注册不变，管理员直连 URL 仍可访问，普通用户按权限拦截。
 */
// 管理中心包含的管理页路径（侧边栏平铺顺序）
const ADMIN_MENU_PATHS = [
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
];

const buildRoleMenu = (menuData: any[], initialState: any) => {
  const byPath = (path: string) =>
    menuData.find(
      (item: any) =>
        (item.path === path || item.path === `${path}/`) && !item.hideInMenu && !item.redirect,
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

  // 管理中心：侧边栏平铺全部管理页（无分组标题），顺序按职责域排列。
  const adminItems = pick(ADMIN_MENU_PATHS);

  return [
    ...userItems,
    // path 落在第一个管理页（问数配置）：点击进入后侧边栏平铺全部管理项
    ...(adminItems.length > 0
      ? [{ name: '管理中心', icon: <ControlOutlined />, path: '/agent', children: adminItems }]
      : []),
  ];
};

export const layout: RunTimeLayoutConfig = (params) => {
  const { initialState, setInitialState } = params as any;
  const siderCollapsed = Boolean((initialState as any)?.siderCollapsed);
  return {
    menuDataRender: (menuData: any[]) => buildRoleMenu(menuData, initialState),
    onMenuHeaderClick: (e) => {
      e.preventDefault();
      history.push(replaceRoute);
    },
    // 不传 menuHeaderRender：mix 布局下 ProLayout 顶栏用默认 logo 渲染，
    // 侧边栏不再重复显示 logo（SiderMenu 在 mix 模式下默认不渲染侧栏头部），
    // 同时移除收缩按钮（2026-08-22 用户确认）。移动端抽屉由 MobileLayoutHeader 提供品牌与开关。
    collapsed: siderCollapsed,
    onCollapse: (collapsed) => setInitialState({ ...initialState, siderCollapsed: collapsed }),
    collapsedButtonRender: false,
    logo: (
      <span className="brand-logo">
        <img src={`${publicPath}branding/bank-query-icon.svg`} alt="" className="brand-logo-mark" />
        <img
          src={`${publicPath}branding/bank-query-text.svg`}
          alt="银行问数"
          className="brand-logo-text-img"
        />
      </span>
    ),
    contentStyle: { background: '#fff', ...(initialState?.contentStyle || {}) },
    // 桌面端 mix 布局：一级导航在顶栏，必须用 ProLayout 默认头部——自定义 headerRender 会整体
    // 替换 Header 内容（pro-layout Header/index.js: headerRender(props, defaultDom) 的返回值即顶栏），
    // 返回 null 会导致顶栏与一级菜单消失，故桌面端不传 headerRender。
    // 移动端保留位于 Drawer 外部的主导航入口。
    ...(isMobile
      ? {
          headerRender: (props: any) => (
            <MobileLayoutHeader collapsed={props.collapsed} onCollapse={props.onCollapse} />
          ),
        }
      : {}),
    // 侧边栏底部账户入口仅移动端抽屉需要（移动端顶栏被 MobileLayoutHeader 整体替换，不含账户）；
    // 桌面端账户在顶栏右侧（rightContentRender），不重复渲染。
    ...(isMobile
      ? {
          menuFooterRender: (props: any) =>
            props?.collapsed ? (
              <div className="sider-account" style={{ textAlign: 'center' }}>
                <AvatarDropdown hideName />
              </div>
            ) : (
              <div className="sider-account" style={{ padding: '0 16px' }}>
                <AvatarDropdown />
              </div>
            ),
        }
      : {}),
    // 账户入口（访问令牌/修改密码/退出登录）固定展示在顶栏右侧；
    // umi 默认 rightRender 只在 initialState.name/avatar 存在时才显示，本项目用 currentUser，故需显式渲染。
    rightContentRender: () => <AvatarDropdown />,
    disableContentMargin: true,
    childrenRender: (dom) => {
      return (
        <ConfigProvider theme={configProviderTheme}>
          <div
            style={{
              // mix 布局桌面端多出 56px 顶栏（pro-layout 默认 heightLayoutHeader），
              // 聊天页高度需扣除，否则整页出现纵向滚动条；移动端行为保持原样。
              // overflow hidden 兜底：/chat 整页不滚动，只有对话区内部滚动，历史面板位置固定。
              height: location.pathname.includes('chat')
                ? isMobile
                  ? '100vh'
                  : 'calc(100vh - 56px)'
                : undefined,
              overflow: location.pathname.includes('chat') ? 'hidden' : undefined,
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
