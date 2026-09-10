/**
 * ZCode 远程 EntryAbility
 *
 * 迁移自 Deepseek-Harmony（https://github.com/SakuraNeko/Deepseek-Harmony）
 * MIT License © Sakura Neko
 *
 * 窗口约定（项目文档 §8 / §11）：
 * - 不启用沉浸式布局：窗口内容区由系统默认避让顶部状态栏，页面自然从状态栏下方开始。
 * - 状态栏背景透明，实际底色由页面顶部占位 Row 提供；色值走设备/深浅色限定资源
 *   app.color.status_bar_bg（对齐远程网页开屏壳底色，手机控制页加载后由动态采样细化），
 *   图标暗色白、亮色黑。
 * - 底部贴底由页面 expandSafeArea(BOTTOM) 负责，此处不干预。
 */
import { AbilityConstant, Configuration, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { webview } from '@kit.ArkWeb';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { i18n } from '@kit.LocalizationKit';
import { isValidRemoteUrl, loadRemoteUrl } from '../common/RemoteUrlManager';

const DOMAIN = 0x0001;
const TAG = 'ZCodeEntryAbility';

// 状态栏配色（项目文档 §11）
// 底色由设备/深浅色限定资源 status_bar_bg 提供，取值对齐远程网页开屏壳底色
// （亮 #F8F8F8 / 暗 #161616）；常量仅作为资源读取异常时的兜底。
const BAR_ICON_DARK = '#FFFFFF';
const BAR_ICON_LIGHT = '#000000';
const BAR_BG_FALLBACK_DARK = '#FF161616';
const BAR_BG_FALLBACK_LIGHT = '#FFF8F8F8';

let webEngineInitialized: boolean = false;
let mainWindowClass: window.Window | null = null;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    // Web 引擎只初始化一次，避免重复初始化导致异常
    if (!webEngineInitialized) {
      try {
        webview.WebviewController.initializeWebEngine();
        webEngineInitialized = true;
        hilog.info(DOMAIN, TAG, 'WebEngine initialized');
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'Failed to initialize WebEngine');
      }
    }

    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'Failed to set colorMode');
    }

    // 当前系统颜色模式，供 WebView 深色模式适配与状态栏配色
    AppStorage.setOrCreate<ConfigurationConstant.ColorMode>('currentColorMode', this.context.config.colorMode);

    // 当前系统语言，供 WebView 语言适配
    try {
      AppStorage.setOrCreate<string>('systemLanguage', i18n.System.getSystemLanguage());
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'Failed to get system language');
    }

    // 冷启动预读持久化 URL（Index.aboutToAppear 消费，URL 本身不落日志）
    const savedUrl: string = loadRemoteUrl(this.context);
    AppStorage.setOrCreate<string>('bootRemoteUrl', savedUrl);

    // 快捷方式「扫码连接」路由（项目文档 §7）
    this.handleScanAction(want);
    // 深链兜底：z.ai 域 /remote 前缀链接直接作为连接 URL
    this.handleDeepLink(want);

    hilog.info(DOMAIN, TAG, 'Ability onCreate, hasSavedUrl=%{public}s', String(savedUrl !== ''));
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'Ability onDestroy');
  }

  // 系统深浅色 / 语言变化：同步 AppStorage，并重刷状态栏配色
  onConfigurationUpdate(newConfig: Configuration): void {
    const newColorMode: ConfigurationConstant.ColorMode | undefined = newConfig.colorMode;
    if (newColorMode !== undefined) {
      const current: ConfigurationConstant.ColorMode | undefined = AppStorage.get('currentColorMode');
      if (current !== newColorMode) {
        AppStorage.setOrCreate<ConfigurationConstant.ColorMode>('currentColorMode', newColorMode);
        // 必须用回调入参 newConfig.colorMode：此时 this.context.config 尚未刷新，
        // 沿用会拿到上一档模式，导致状态栏与页面配色反相
        this.applySystemBar(newColorMode);
      }
    }

    const newLanguage: string | undefined = newConfig.language;
    if (newLanguage !== undefined) {
      const current: string | undefined = AppStorage.get('systemLanguage');
      if (current !== newLanguage) {
        AppStorage.setOrCreate<string>('systemLanguage', newLanguage);
      }
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'Ability onWindowStageCreate');

    try {
      mainWindowClass = windowStage.getMainWindowSync();
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'Failed to obtain main window');
    }

    if (mainWindowClass) {
      // 沉浸式布局：窗口铺满全屏（底部天然贴底），顶部由页面按状态栏高度留白
      try {
        mainWindowClass.setImmersiveModeEnabledState(true);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'setImmersiveModeEnabledState not supported');
      }

      // 启动即定色，避免冷启动闪屏（与状态栏固定色一致）
      this.applySystemBar();

      // 旋转 / 分屏导致状态栏高度变化时刷新避让值
      try {
        mainWindowClass.on('avoidAreaChange', (options: window.AvoidAreaOptions) => {
          if (options.type === window.AvoidAreaType.TYPE_SYSTEM) {
            this.syncTopAvoid();
          }
        });
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'avoidAreaChange register failed');
      }
    }

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      // 内容加载完成后系统才上报避让区；此前读取会得到 0，导致网页顶到屏幕最上沿
      this.syncTopAvoid();
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'Ability onForeground');
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'Ability onBackground');
  }

  // 返回兜底（项目文档 §10）：页面 onBackPress 返回 false 时到达此处（当前仅手机）。
  // true = 移到后台不销毁：手机侧滑返回→应用退后台，WebView 会话保留；平板被页面消费，不会到这里。
  onBackPressed(): boolean {
    return true;
  }

  /**
   * 状态栏配色（跟随系统深浅）
   *
   * 背景色保持透明，固定色由页面顶部占位 Row 提供（沉浸模式下窗口铺满全屏，
   * 该 Row 正好覆盖状态栏区域）。这是官方推荐做法：若在此直接给 statusBarColor
   * 赋值，系统会在启动/退出过渡动画期间就立即刷成该颜色，窗口尚未铺满屏幕时
   * 状态栏会突兀变色（FAQ faqs-arkui-1652）。透明则由页面内容自然带出同色。
   *
   * colorMode 显式传入：onConfigurationUpdate 回调中 this.context.config 尚未刷新，
   * 读取它只能得到上一档模式，会造成状态栏图标与页面配色反相。
   */
  private applySystemBar(colorMode?: ConfigurationConstant.ColorMode): void {
    if (!mainWindowClass) {
      return;
    }
    const isDark: boolean =
      (colorMode !== undefined ? colorMode : this.context.config.colorMode)
        === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;

    const sysBarProps: window.SystemBarProperties = {
      statusBarColor: '#00000000',
      statusBarContentColor: isDark ? BAR_ICON_DARK : BAR_ICON_LIGHT
    };
    try {
      mainWindowClass.setWindowSystemBarProperties(sysBarProps).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'setWindowSystemBarProperties failed: %{public}d', err.code);
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'applySystemBar exception');
    }

    // 窗口背景同步限定底色：状态栏透明后，启动过渡期由窗口底色兜底，避免闪白。
    // 色值随设备（tablet 限定词目录）与深浅色自动切换，手机/平板各取所需。
    try {
      mainWindowClass.setWindowBackgroundColor(this.resolveStatusBarBg());
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'setWindowBackgroundColor not supported');
    }
  }

  /**
   * 读取设备/深浅色限定的状态栏底色（app.color.status_bar_bg）。
   * 平板命中 tablet / tablet-dark 限定词目录，手机命中 base / dark，取不到时回退兜底常量。
   */
  private resolveStatusBarBg(): string {
    try {
      const value: number = this.context.resourceManager.getColorByNameSync('status_bar_bg');
      return '#' + (value >>> 0).toString(16).padStart(8, '0');
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'resolveStatusBarBg failed, using fallback');
      const isDark: boolean =
        this.context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
      return isDark ? BAR_BG_FALLBACK_DARK : BAR_BG_FALLBACK_LIGHT;
    }
  }

  /**
   * 读取系统状态栏高度（px）写入 AppStorage，供页面顶部占位使用。
   * 必须在 loadContent 之后读取：内容加载前系统尚未上报避让区，会得到 0。
   */
  private syncTopAvoid(): void {
    if (!mainWindowClass) {
      return;
    }
    try {
      const avoid: window.AvoidArea =
        mainWindowClass.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      AppStorage.setOrCreate<number>('topAvoidPx', avoid.topRect.height);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'syncTopAvoid failed');
    }
  }

  /**
   * 热启动接收新的快捷方式扫码指令 / Deep Link
   */
  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    this.handleScanAction(want);
    this.handleDeepLink(want);
  }

  /**
   * 快捷方式路由：want.parameters.action === 'scan' → AppStorage → Index 拉起扫码
   */
  private handleScanAction(want: Want): void {
    const params = want?.parameters;
    if (params && (params.action === 'scan' || params['action'] === 'scan')) {
      AppStorage.setOrCreate('scanAction', 'scan');
      hilog.info(DOMAIN, TAG, 'Scan shortcut action received');
    } else {
      // 非扫码启动：清空残留指令，防止旧值干扰
      AppStorage.setOrCreate('scanAction', '');
    }
  }

  /**
   * Deep Link：z.ai 域 /remote 前缀的 https 链接 → 作为连接 URL（扫码为主入口，此处兜底）
   */
  private handleDeepLink(want: Want): void {
    const uri = want?.uri;
    if (uri && isValidRemoteUrl(uri)) {
      hilog.info(DOMAIN, TAG, 'Valid remote DeepLink received');
      AppStorage.setOrCreate('deepLinkUrl', uri);
    }
  }
}
