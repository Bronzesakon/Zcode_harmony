/**
 * ZCode 远程主页面
 *
 * 迁移自 Deepseek-Harmony（https://github.com/SakuraNeko/Deepseek-Harmony）
 * MIT License © Sakura Neko
 *
 * 变更要点（项目文档 §8）：
 * - 移除：分享拦截、2in1 适配（拖拽手柄/返回按钮/隐藏标题栏）、拍照上传（CameraPicker）
 * - 新增：扫码引导页、URL 持久化分支、快捷方式扫码路由、屏幕常亮、onBackPress 全吞
 * - 调整：上传模态框裁剪为「相册 + 文件」两项；外链白名单改为 z.ai 全系域名
 */
import { webview } from '@kit.ArkWeb';
import { common, ConfigurationConstant, Want } from '@kit.AbilityKit';
import { fileIo as fs, fileUri, picker } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { i18n } from '@kit.LocalizationKit';
import { connection } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { url } from '@kit.ArkTS';
import { ALLOWED_UPLOAD_EXTENSIONS } from '../common/AllowedFileExtensions';
import { isValidRemoteUrl, saveRemoteUrl } from '../common/RemoteUrlManager';

const DOMAIN = 0x0001;
const TAG = 'ZCodeIndex';

/**
 * 网页顶部底色采样脚本（返回 #RRGGBB，取不到返回空串）
 *
 * 网页显示区被顶到状态栏下方，故视口 y≈0 处的有效背景色 = 状态栏下方那一条的真实颜色
 * （控制页即两级菜单标题栏）。做法：多点取样 + 众数投票，命中透明/分隔线时回溯祖先求
 * 半透明叠加后的有效不透明底色；全链路无实心底色（渐变/背景图/跨域 iframe）时返回空串，
 * 交由原生回退到限定资源色。
 */
const STATUS_BAR_SAMPLE_JS: string = `
(function () {
  function parseColor(c) {
    if (!c) { return null; }
    var m = c.match(/rgba?\\(([^)]+)\\)/);
    if (!m) { return null; }
    var p = m[1].split(',');
    if (p.length < 3) { return null; }
    var r = parseFloat(p[0]);
    var g = parseFloat(p[1]);
    var b = parseFloat(p[2]);
    var a = p.length > 3 ? parseFloat(p[3]) : 1;
    if (isNaN(r) || isNaN(g) || isNaN(b) || isNaN(a)) { return null; }
    return { r: r, g: g, b: b, a: a };
  }
  function over(fg, bg) {
    var a = fg.a;
    return {
      r: Math.round(fg.r * a + bg.r * (1 - a)),
      g: Math.round(fg.g * a + bg.g * (1 - a)),
      b: Math.round(fg.b * a + bg.b * (1 - a))
    };
  }
  function hex(c) {
    function h(n) { var s = n.toString(16); return s.length < 2 ? '0' + s : s; }
    return '#' + h(c.r) + h(c.g) + h(c.b);
  }
  function effective(el) {
    var stack = [];
    var node = el;
    while (node && node.nodeType === 1) {
      var c = parseColor(getComputedStyle(node).backgroundColor);
      if (c && c.a > 0) {
        stack.push(c);
        if (c.a >= 1) { break; }
      }
      node = node.parentElement;
    }
    if (!node || node.nodeType !== 1) { return ''; }
    var last = stack.length > 0 ? stack[stack.length - 1] : null;
    if (!last || last.a < 1) { return ''; }
    var out = stack.pop();
    for (var i = stack.length - 1; i >= 0; i--) { out = over(stack[i], out); }
    return hex(out);
  }
  try {
    var w = window.innerWidth;
    var pts = [1, 3, 6, 12, 20];
    var votes = {};
    for (var i = 0; i < pts.length; i++) {
      var el = document.elementFromPoint(Math.round(w / 2), pts[i])
        || document.elementFromPoint(4, pts[i])
        || document.body;
      var c = effective(el);
      if (c) { votes[c] = (votes[c] || 0) + 1; }
    }
    var best = '';
    var top = -1;
    for (var k in votes) { if (votes[k] > top) { top = votes[k]; best = k; } }
    return best;
  } catch (e) {
    return '';
  }
})()
`;

@Entry
@Component
struct Index {
  // 冷启动预读的持久化 URL；空串 = 无连接 → 扫码引导页
  @State remoteUrl: string = '';
  @State hasConnection: boolean = false;
  @State loadingProgress: number = 0;
  @State isLoading: boolean = true;
  @State @Watch('onErrorStateChange') isError: boolean = false;
  @State isNetworkError: boolean = false;
  // 快捷方式扫码指令通道（EntryAbility onCreate/onNewWant 写入）
  @StorageLink('scanAction') @Watch('onScanActionChange') scanAction: string = '';
  // 深链通道（EntryAbility onCreate/onNewWant 校验后写入）：冷启动 onPageShow / 热启动 @Watch 消费
  @StorageLink('deepLinkUrl') @Watch('onDeepLinkChange') deepLinkUrl: string = '';
  // 系统语言适配：监听系统语言变化以重新注入 WebView
  @StorageLink('systemLanguage') @Watch('onSystemLanguageChange') systemLanguage: string = '';
  controller: webview.WebviewController = new webview.WebviewController();
  delegate: webview.WebDownloadDelegate = new webview.WebDownloadDelegate();
  private pageLoadTimer: number = -1;
  private readonly LOAD_TIMEOUT_MS: number = 15000;
  // 深浅色跟随系统：避让区/容器取控制页网页背景同色（暗=左栏深灰，亮=白）
  @StorageProp('currentColorMode') @Watch('onColorModeChange')
  currentColorMode: ConfigurationConstant.ColorMode = ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET;
  @State webBgColor: string = '#FFFFFFFF';
  // 状态栏占位底色：平板取设备限定资源固定色，手机控制页取网页顶部实测色（兜底同上）
  @State statusBarColor: ResourceColor = $r('app.color.status_bar_bg');
  // 手机端才做动态取色；平板保持固定色（避免影响平板上已正常的观感）
  private readonly isPhone: boolean = deviceInfo.deviceType === deviceInfo.DeviceTypes.TYPE_PHONE;
  // 上一次采样结果（空串 = 尚未取到有效色，走资源兜底）
  private sampledBarColor: string = '';
  private statusBarTimer: number = -1;
  private readonly STATUS_BAR_POLL_MS: number = 1000;
  // 上传方式选择模态框（相册 + 文件两项）
  @State showUploadModal: boolean = false;
  // 无效二维码提示
  @State showInvalidQrToast: boolean = false;
  // 扫码中标识：避免引导页按钮重复拉起扫码
  @State isScanning: boolean = false;
  // 状态栏高度（px）：EntryAbility 读取并写入，用于页面顶部占位
  @StorageProp('topAvoidPx') topAvoidPx: number = 0;
  private pendingFileListHandler: ((fileList: Array<string>) => void) | null = null;

  aboutToAppear(): void {
    this.remoteUrl = AppStorage.get<string>('bootRemoteUrl') ?? '';
    this.hasConnection = this.remoteUrl !== '';
    // 初始化深浅色背景
    this.onColorModeChange();
    if (this.hasConnection) {
      hilog.info(DOMAIN, TAG, 'Saved connection found, entering control page');
    }
  }

  aboutToDisappear(): void {
    // 组件销毁前清理定时器，避免内存泄漏和无效回调
    this.clearLoadTimeout();
    this.stopStatusBarWatch();
  }

  onPageShow(): void {
    // 控制页前台常亮（项目文档 §3 #13）
    if (this.hasConnection) {
      this.setKeepScreenOn(true);
    }
    // 引导页状态下由快捷方式冷启动：页面可见后自动拉起扫码
    if (!this.hasConnection && this.scanAction === 'scan') {
      this.consumeScanAction();
      this.startScan();
    }
    // 冷启动深链：校验过的 z.ai /remote 链接直接作为连接 URL
    if (this.deepLinkUrl && this.deepLinkUrl !== '') {
      this.consumeDeepLink();
    }
  }

  // 快捷方式触发热启动扫码
  onScanActionChange(): void {
    if (this.scanAction === 'scan') {
      this.consumeScanAction();
      this.startScan();
    }
  }

  // 热启动深链
  onDeepLinkChange(): void {
    if (this.deepLinkUrl && this.deepLinkUrl !== '') {
      this.consumeDeepLink();
    }
  }

  private consumeDeepLink(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const target = this.deepLinkUrl;
    AppStorage.set<string>('deepLinkUrl', '');
    // EntryAbility 已校验；此处直接持久化并进入控制页
    saveRemoteUrl(ctx, target);
    this.remoteUrl = target;
    if (this.hasConnection) {
      this.loadControlPage(target);
    } else {
      this.hasConnection = true;
      this.syncStatusBarColor();
    }
  }

  private consumeScanAction(): void {
    AppStorage.set<string>('scanAction', '');
  }

  // 深浅色切换：避让区/容器背景取控制页网页侧栏实测底色（暗=#2B2B2B，亮=#ECECEE，2026-09-09 真机取样）
  onColorModeChange(): void {
    this.webBgColor =
      this.currentColorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK
        ? '#FF2B2B2B' : '#FFECECEE';
    // 网页会随 prefers-color-scheme 重绘，旧采样值立即失效，先回退兜底色再重新采样
    this.sampledBarColor = '';
    this.syncStatusBarColor();
    if (this.hasConnection && !this.isError) {
      this.scheduleStatusBarSample(200);
    }
  }

  /**
   * 决定状态栏占位 Row 当前应使用的颜色：
   * - 原生页（扫码引导 / 加载失败）与页面自身背景保持一致（webBgColor）；
   * - 平板控制页用设备限定资源固定色（现状不变）；
   * - 手机控制页用网页顶部动态采样色，未取到时回退到限定资源色。
   */
  // 错误页出现/消失时同步状态栏底色（错误页为原生背景，需与 webBgColor 一致）
  onErrorStateChange(): void {
    if (this.isError) {
      this.stopStatusBarWatch();
    }
    this.syncStatusBarColor();
  }

  private syncStatusBarColor(): void {
    if (!this.hasConnection || this.isError) {
      this.statusBarColor = this.webBgColor;
      return;
    }
    if (!this.isPhone || this.sampledBarColor === '') {
      this.statusBarColor = $r('app.color.status_bar_bg');
      return;
    }
    this.statusBarColor = this.sampledBarColor;
  }

  // 启动状态栏取色轮询（仅手机控制页；幂等）
  private startStatusBarWatch(): void {
    if (!this.isPhone || this.statusBarTimer !== -1) {
      return;
    }
    this.statusBarTimer = setInterval(() => {
      if (!this.hasConnection || this.isError) {
        return;
      }
      this.sampleStatusBarColor();
    }, this.STATUS_BAR_POLL_MS);
  }

  private stopStatusBarWatch(): void {
    if (this.statusBarTimer !== -1) {
      clearInterval(this.statusBarTimer);
      this.statusBarTimer = -1;
    }
  }

  // 延迟单次采样：页面加载完成后尽快刷新，不必等一个轮询周期
  private scheduleStatusBarSample(delayMs: number): void {
    if (!this.isPhone || !this.hasConnection || this.isError) {
      return;
    }
    setTimeout(() => {
      if (this.hasConnection && !this.isError) {
        this.sampleStatusBarColor();
      }
    }, delayMs);
  }

  // 读取网页顶部有效底色并同步；仅当取值变化时更新，避免无谓重绘
  private sampleStatusBarColor(): void {
    try {
      this.controller.runJavaScript(STATUS_BAR_SAMPLE_JS, (err: BusinessError, result: string) => {
        if (err || !result) {
          return;
        }
        const color: string = this.normalizeSampledColor(result);
        if (color === '' || color === this.sampledBarColor) {
          return;
        }
        this.sampledBarColor = color;
        this.syncStatusBarColor();
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'sampleStatusBarColor failed');
    }
  }

  // 采样返回值归一化为 #AARRGGBB；非法值返回空串（交由资源兜底）
  private normalizeSampledColor(raw: string): string {
    let value: string = raw.trim();
    if (value.length >= 2 && value.charAt(0) === '"' && value.charAt(value.length - 1) === '"') {
      value = value.substring(1, value.length - 1);
    }
    if (!/^#[0-9a-fA-F]{6}$/.test(value)) {
      return '';
    }
    return '#FF' + value.substring(1).toUpperCase();
  }

  // 深浅色 UI 取色助手
  private themeColor(darkColor: ResourceColor, lightColor: ResourceColor): ResourceColor {
    return this.currentColorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK
      ? darkColor : lightColor;
  }

  // 拉起默认界面扫码（系统统一 UI，相机权限系统预授权）
  private startScan(): void {
    if (this.isScanning) {
      return;
    }
    this.isScanning = true;
    const options: scanBarcode.ScanOptions = {
      scanTypes: [scanCore.ScanType.ALL],
      enableMultiMode: false,
      enableAlbum: true
    };
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    scanBarcode.startScanForResult(ctx, options).then((result: scanBarcode.ScanResult) => {
      this.isScanning = false;
      const scanned: string = result.originalValue;
      if (isValidRemoteUrl(scanned)) {
        // 校验通过 → 覆盖保存 → 热切换（单连接覆盖式模型）
        saveRemoteUrl(ctx, scanned);
        this.remoteUrl = scanned;
        if (this.hasConnection) {
          this.loadControlPage(scanned);
        } else {
          this.hasConnection = true;
          this.syncStatusBarColor();
        }
      } else {
        // 无效二维码：提示后丢弃，不保存，原状态不变
        this.showInvalidQrToast = true;
        setTimeout(() => {
          this.showInvalidQrToast = false;
        }, 2500);
      }
    }).catch((err: BusinessError) => {
      this.isScanning = false;
      // 1000500002 = 用户取消，静默返回；其余异常同样静默
      if (err.code !== 1000500002) {
        hilog.warn(DOMAIN, TAG, 'Scan failed: %{public}d', err.code);
      }
    });
  }

  build() {
    Column() {
      // 状态栏占位：沉浸模式下窗口从屏幕顶端开始，此处留出状态栏高度，
      // 使网页显示区域顶端严格对齐状态栏底部；底色取控制页顶部实测色（平板/兜底为限定资源）
      Row()
        .width('100%')
        .height(this.topAvoidPx > 0 ? `${this.topAvoidPx}px` : '0px')
        .backgroundColor(this.statusBarColor)

      if (this.hasConnection) {
        Stack({ alignContent: Alignment.Top }) {
          Web({ src: this.remoteUrl, controller: this.controller })
            .domStorageAccess(true)
            .javaScriptAccess(true)
            .fileAccess(true)
            .mixedMode(1)
            .imageAccess(true)
            .zoomAccess(true)
            .darkMode(2) // WebDarkMode.Auto: 跟随系统深色模式，传递 prefers-color-scheme 给网页
            .backgroundColor(this.webBgColor)
            .geolocationAccess(true)
            .width('100%')
            .height('100%')
            .onControllerAttached(() => {
              try {
                const customUA: string = this.controller.getUserAgent() + ' ZCode-HarmonyOS/1.0';
                this.controller.setCustomUserAgent(customUA);
              } catch (err) {
                hilog.error(DOMAIN, TAG, 'Failed to set user agent');
              }
              this.setupDownloadDelegate();
            })
            .onPageBegin((event) => {
              this.isLoading = true;
              this.isError = false;
              this.isNetworkError = false;
              this.startLoadTimeout();
              // 在页面加载最早时机注入系统语言，确保网页 JS 读取 navigator.language 前已覆盖
              this.injectLanguageOverride();
            })
            .onProgressChange((event) => {
              if (event) {
                this.loadingProgress = event.newProgress;
              }
            })
            .onPageEnd(() => {
              this.isLoading = false;
              this.clearLoadTimeout();
              // 页面就绪后采样顶部底色，随后转入低频轮询（SPA 路由/主题切换时自动跟随）
              this.scheduleStatusBarSample(80);
              this.startStatusBarWatch();
            })
            .onErrorReceive((event) => {
              if (event && event.error && event.error.getErrorCode() !== 0) {
                // 仅主框架的导航错误才触发错误页（过滤子资源错误）
                if (event.request && event.request.isMainFrame() && !this.checkNetworkAvailable()) {
                  this.isNetworkError = true;
                  this.isError = true;
                  this.isLoading = false;
                  this.clearLoadTimeout();
                }
              }
            })
            .onHttpErrorReceive((event) => {
              if (event && event.response) {
                const statusCode: number = event.response.getResponseCode();
                // 仅主框架的 HTTP 错误才触发错误页（过滤子资源如 API、图片等返回的 404/500）
                const isMainFrame: boolean = event.request ? event.request.isMainFrame() : false;
                if (statusCode >= 400 && isMainFrame) {
                  // 先检测网络状态，再决定错误类型
                  this.isNetworkError = !this.checkNetworkAvailable();
                  this.isError = true;
                  this.isLoading = false;
                  this.clearLoadTimeout();
                }
              }
            })
            .onRenderExited((event) => {
              if (event && event.renderExitReason === 2) {
                hilog.error(DOMAIN, TAG, 'Render crashed, reloading...');
                try {
                  this.controller.refresh();
                } catch (err) {
                  hilog.error(DOMAIN, TAG, 'Failed to refresh after crash');
                }
              }
            })
            .onShowFileSelector((event) => {
              if (!event) {
                return false;
              }
              // 存储 FileSelectorResult，弹出上传方式选择模态框供用户选择
              this.pendingFileListHandler = (fileList: Array<string>) => {
                event.result.handleFileList(fileList);
              };
              this.showUploadModal = true;
              return true;
            })
            .onLoadIntercept((event) => {
              if (!event) {
                return false;
              }
              const requestUrl: string = event.data.getRequestUrl();
              // 非 z.ai 域名的页面跳转：仅拦截主框架导航，放行子请求（下载/资源等）
              if (!this.isZaiDomain(requestUrl) && event.data.isMainFrame()
                && !this.isDownloadUrl(requestUrl)) {
                this.openInSystemBrowser(requestUrl);
                return true;
              }
              return false;
            })

          if (this.isLoading) {
            Progress({ value: this.loadingProgress, type: ProgressType.Linear })
              .width('100%')
              .height(3)
              .color('#4A90D9')
              .backgroundColor('#00FFFFFF')
          }

          if (this.isError) {
            Column() {
              Text(this.isNetworkError ? '⚠' : '✕')
                .fontSize(48)
                .fontColor(this.themeColor('#BBBBBB', '#999999'))
                .margin({ top: 80, bottom: 16 })
              Text(this.isNetworkError ? $r('app.string.error_network_title') : $r('app.string.error_load_title'))
                .fontSize(18)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.themeColor('#FFFFFF', '#333333'))
                .margin({ bottom: 8 })
              Text($r('app.string.error_desc_with_rescan'))
                .fontSize(14)
                .fontColor(this.themeColor('#BBBBBB', '#999999'))
                .margin({ bottom: 4 })
              Column() {
                Text(this.isNetworkError
                  ? $r('app.string.error_network_desc') : $r('app.string.error_load_desc'))
                  .fontSize(14)
                  .fontColor(this.themeColor('#BBBBBB', '#999999'))
                  .margin({ bottom: 24 })
              }
              Button($r('app.string.retry_button'))
                .type(ButtonType.Normal)
                .fontSize(16).width(160).height(40).borderRadius(20)
                .backgroundColor('#4A90D9').fontColor(Color.White)
                .onClick(() => {
                  this.isError = false;
                  this.isNetworkError = false;
                  this.refreshPage();
                })
            }
            .width('100%').height('100%')
            .justifyContent(FlexAlign.Center)
            .backgroundColor(this.webBgColor)
          }

          // 上传方式选择模态框：相册 + 文件两项（拍照入口已裁剪）
          if (this.showUploadModal) {
            Column() {
              Column() {
                Text($r('app.string.upload_modal_title'))
                  .fontSize(18)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(this.themeColor('#FFFFFF', '#333333'))
                  .margin({ bottom: 20 })

                Row({ space: 12 }) {
                  // 相册
                  Column() {
                    SymbolGlyph($r('sys.symbol.picture'))
                      .fontSize(32)
                      .fontColor([this.themeColor('#E6E6E6', '#1A1A1A')])
                      .margin({ bottom: 8 })
                    Text($r('app.string.upload_album'))
                      .fontSize(13)
                  }
                  .layoutWeight(1)
                  .aspectRatio(1)
                  .borderRadius(14)
                  .justifyContent(FlexAlign.Center)
                  .backgroundColor(this.themeColor('#3A3A3A', '#F5F5F5'))
                  .onClick(() => this.handlePickFromAlbum())

                  // 文件
                  Column() {
                    SymbolGlyph($r('sys.symbol.doc'))
                      .fontSize(32)
                      .fontColor([this.themeColor('#E6E6E6', '#1A1A1A')])
                      .margin({ bottom: 8 })
                    Text($r('app.string.upload_file'))
                      .fontSize(13)
                  }
                  .layoutWeight(1)
                  .aspectRatio(1)
                  .borderRadius(14)
                  .justifyContent(FlexAlign.Center)
                  .backgroundColor(this.themeColor('#3A3A3A', '#F5F5F5'))
                  .onClick(() => this.handlePickFile())
                }
                .width('100%')
                .margin({ bottom: 16 })

                Text($r('app.string.upload_hint'))
                  .fontSize(13)
                  .fontColor(this.themeColor('#888888', '#999999'))
              }
              .width('85%')
              .constraintSize({ maxWidth: 360 })
              .padding(24)
              .borderRadius(16)
              .backgroundColor(this.themeColor('#2A2A2A', '#FFFFFF'))
              .shadow({ radius: 24, color: 'rgba(0,0,0,0.3)', offsetY: 8 })
              .onClick(() => {})
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
            .backgroundColor('rgba(0,0,0,0.5)')
            .onClick(() => this.dismissUploadModal())
            .zIndex(100)
          }
        }
        .width('100%')
        .layoutWeight(1)
        .backgroundColor(this.webBgColor)
      } else {
        // 首次开屏扫码引导页：应用名 + 一句话说明 + 主按钮（不自动拉相机）
        // 宽屏（平板/分屏）时内容收敛为窄栏居中，保持手机端布局观感
        Column() {
          Blank()
          Column() {
            Image($r('app.media.foreground'))
              .width(96)
              .height(96)
              .borderRadius(24)
              .margin({ bottom: 32 })
            Text($r('app.string.app_title'))
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.themeColor('#FFFFFF', '#1A1A1A'))
              .margin({ bottom: 12 })
            Text($r('app.string.guide_desc'))
              .fontSize(15)
              .fontColor(this.themeColor('#BBBBBB', '#666666'))
              .textAlign(TextAlign.Center)
          }
          .width('100%')
          .constraintSize({ maxWidth: 420 })
          .justifyContent(FlexAlign.Center)

          Blank()
          Column() {
            Button($r('app.string.scan_connect_button'))
              .type(ButtonType.Normal)
              .fontSize(17)
              .width(220)
              .height(48)
              .borderRadius(24)
              .backgroundColor(this.themeColor(Color.White, '#1A1A1A'))
              .fontColor(this.themeColor(Color.Black, Color.White))
              .onClick(() => this.startScan())
            Text($r('app.string.guide_hint_shortcut'))
              .fontSize(12)
              .fontColor(this.themeColor('#888888', '#AAAAAA'))
              .margin({ top: 16 })
          }
          .margin({ bottom: 80 })
        }
        .width('100%')
        .layoutWeight(1)
        .constraintSize({ maxWidth: 560 })
        .backgroundColor(this.webBgColor)
      }

      // 无效二维码提示（自绘 Toast，覆盖引导页/控制页两种形态）
      if (this.showInvalidQrToast) {
        Column() {
          Text($r('app.string.toast_invalid_qr'))
            .fontSize(14)
            .fontColor(Color.White)
            .padding({ left: 24, right: 24, top: 12, bottom: 12 })
        }
        .borderRadius(22)
        .backgroundColor('rgba(0,0,0,0.75)')
        .position({ x: '50%', y: '80%' })
        .markAnchor({ x: '50%', y: '50%' })
        .zIndex(200)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.webBgColor)
  }

  onPageHide(): void {
    // 切后台恢复系统息屏节奏（项目文档 §3 #13）
    if (this.hasConnection) {
      this.setKeepScreenOn(false);
    }
  }

  // 全吞返回手势：上传模态框开时先关模态框，其余场景一律消费事件（项目文档 §10）
  onBackPress(): boolean {
    if (this.showUploadModal) {
      this.dismissUploadModal();
      return true;
    }
    return true;
  }

  // 前台常亮 / 后台恢复
  private setKeepScreenOn(keep: boolean): void {
    try {
      const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
      window.getLastWindow(ctx).then((win: window.Window) => {
        win.setWindowKeepScreenOn(keep).catch((err: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'setWindowKeepScreenOn failed: %{public}d', err.code);
        });
      }).catch(() => {
        hilog.warn(DOMAIN, TAG, 'getLastWindow failed');
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'setKeepScreenOn exception');
    }
  }

  private startLoadTimeout(): void {
    this.clearLoadTimeout();
    this.pageLoadTimer = setTimeout(() => {
      this.isLoading = false;
      // 超时时检测网络：无网络则为网络错误，有网络则为加载失败
      this.isNetworkError = !this.checkNetworkAvailable();
      this.isError = true;
    }, this.LOAD_TIMEOUT_MS);
  }

  private clearLoadTimeout(): void {
    if (this.pageLoadTimer !== -1) {
      clearTimeout(this.pageLoadTimer);
      this.pageLoadTimer = -1;
    }
  }

  // 检测当前网络是否可用（基于系统网络管理 API）
  private checkNetworkAvailable(): boolean {
    try {
      // 1. 检查是否有默认激活的数据网络
      if (!connection.hasDefaultNetSync()) {
        return false;
      }
      // 2. 获取默认网络句柄
      const netHandle = connection.getDefaultNetSync();
      if (!netHandle || netHandle.netId === 0) {
        return false;
      }
      // 3. 验证网络是否通过互联网可达性验证
      const netCapabilities = connection.getNetCapabilitiesSync(netHandle);
      const caps: Array<connection.NetCap> = netCapabilities.networkCap || [];
      return caps.includes(connection.NetCap.NET_CAPABILITY_VALIDATED);
    } catch (err) {
      // 异常时保守认定为无网络
      return false;
    }
  }

  // 将系统语言标识转换为 BCP 47 格式（如 "zh-Hans-CN" → "zh-CN"），供 WebView navigator.language 使用
  private getBCP47Language(): string {
    try {
      const raw: string = this.systemLanguage || i18n.System.getSystemLanguage();
      if (!raw || raw.length === 0) {
        return 'en';
      }
      const parts: string[] = raw.split('-');
      const lang: string = parts[0]; // "zh"
      // 查找地区部分（格式如 "zh-Hans-CN"，最后一段为2个大写字母的地区代码）
      const lastPart: string = parts.length > 0 ? parts[parts.length - 1] : '';
      if (lastPart.length === 2 && lastPart === lastPart.toUpperCase()) {
        return `${lang}-${lastPart}`;
      }
      return lang;
    } catch (err) {
      return 'en';
    }
  }

  // 注入 JS 覆写 navigator.language / navigator.languages，使网页感知系统语言
  private injectLanguageOverride(): void {
    const bcp47Lang: string = this.getBCP47Language();
    try {
      this.controller.runJavaScript(`
        (function() {
          var lang = '${bcp47Lang}';
          Object.defineProperty(Object.getPrototypeOf(navigator), 'language', {
            get: function() { return lang; },
            configurable: true
          });
          Object.defineProperty(Object.getPrototypeOf(navigator), 'languages', {
            get: function() { return [lang]; },
            configurable: true
          });
        })();
      `);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'Failed to inject language override');
    }
  }

  // 系统语言变化时，重新注入 JS 覆写 navigator.language 并显示刷新按钮。
  // 用户可点击按钮手动刷新以应用新语言，避免自动刷新打断当前操作。
  onSystemLanguageChange(): void {
    if (this.systemLanguage && this.systemLanguage !== '') {
      this.injectLanguageOverride();
      this.showLangRefreshBtn = true;
    }
  }

  @State showLangRefreshBtn: boolean = false;

  // 判断 URL 是否属于 z.ai 全系域名（z.ai / zcode.z.ai 及其子域名）
  private isZaiDomain(requestUrl: string): boolean {
    try {
      const parsed = url.URL.parseURL(requestUrl);
      const hostname: string = parsed.hostname;
      return hostname === 'z.ai' || hostname.endsWith('.z.ai');
    } catch (e) {
      // URL 解析失败时保守处理，允许在 WebView 内加载
      return true;
    }
  }

  // 判断 URL 是否为下载链接（根据常见文件扩展名）
  private isDownloadUrl(requestUrl: string): boolean {
    const downloadExtensions = [
      '.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.xz',
      '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
      '.jpg', '.jpeg', '.png', '.gif', '.bmp', '.svg', '.webp',
      '.mp3', '.mp4', '.avi', '.mov', '.mkv', '.flv', '.wmv',
      '.exe', '.msi', '.apk', '.dmg', '.deb', '.rpm',
      '.csv', '.json', '.xml', '.sql', '.db',
      '.iso', '.img', '.bin',
      '.txt', '.md', '.log'
    ];
    try {
      const parsed = url.URL.parseURL(requestUrl);
      const pathname: string = (parsed.pathname || '').toLowerCase();
      return downloadExtensions.some(ext => pathname.endsWith(ext));
    } catch (_e) {
      return false;
    }
  }

  // 调用系统浏览器打开外部链接
  private openInSystemBrowser(openUrl: string): void {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const want: Want = {
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: openUrl
      };
      context.startAbility(want).catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'Failed to open system browser: %{public}s', err.message);
        this.getUIContext().getPromptAction().showToast({
          message: $r('app.string.toast_external_link'),
          duration: 2000
        });
      });
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'openInSystemBrowser error');
    }
  }

  // 热切换到新扫码结果（覆盖式单连接）
  private loadControlPage(target: string): void {
    try {
      // 新页面顶部配色未知：先回退兜底色，待加载完成后重新采样
      this.sampledBarColor = '';
      this.syncStatusBarColor();
      this.controller.loadUrl(target);
      this.isError = false;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'loadControlPage failed');
    }
  }

  // 刷新当前页面
  private refreshPage(): void {
    try {
      this.controller.refresh();
      this.isError = false;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'refreshPage failed');
    }
  }

  // 注册 Web 下载代理：处理下载开始/进度/完成/失败，下载完成后可另存至公开目录
  private setupDownloadDelegate(): void {
    try {
      // 用 GUID 映射下载沙箱路径，避免多个并发的下载时路径覆盖
      const downloadPathMap: Record<string, string> = {};

      this.delegate.onBeforeDownload((webDownloadItem: webview.WebDownloadItem) => {
        const fileName: string = webDownloadItem.getSuggestedFileName();
        const guid: string = webDownloadItem.getGuid();
        const cachePath: string =
          this.getUIContext().getHostContext()!.cacheDir + '/web/' + fileName;
        downloadPathMap[guid] = cachePath;
        webDownloadItem.start(cachePath);
      });

      this.delegate.onDownloadUpdated((webDownloadItem: webview.WebDownloadItem) => {
        // 进度回调，无 UI 展示
      });

      this.delegate.onDownloadFinish((webDownloadItem: webview.WebDownloadItem) => {
        const guid: string = webDownloadItem.getGuid();
        const savePath: string = downloadPathMap[guid];
        this.getUIContext().getPromptAction().showToast({
          message: $r('app.string.toast_download_done'),
          duration: 2000
        });
        if (savePath) {
          this.saveToPublicDownload(webDownloadItem.getSuggestedFileName(), savePath);
          downloadPathMap[guid] = '';
        }
      });

      this.delegate.onDownloadFailed((webDownloadItem: webview.WebDownloadItem) => {
        const guid: string = webDownloadItem.getGuid();
        // 清理失败下载的路径映射
        downloadPathMap[guid] = '';
        this.getUIContext().getPromptAction().showToast({
          message: $r('app.string.toast_download_fail'),
          duration: 2000
        });
      });

      this.controller.setDownloadDelegate(this.delegate);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'Failed to setup download delegate');
    }
  }

  // 将已完成的下载从沙箱复制到用户选择的公开目录
  private saveToPublicDownload(fileName: string, srcPath: string): void {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const documentSaveOptions = new picker.DocumentSaveOptions();
      documentSaveOptions.pickerMode = picker.DocumentPickerMode.DOWNLOAD;
      documentSaveOptions.newFileNames = [fileName];

      const documentViewPicker = new picker.DocumentViewPicker(context);
      documentViewPicker.save(documentSaveOptions).then((result: Array<string>) => {
        if (result.length === 0 || !result[0]) {
          return;
        }
        const uri: string = result[0];
        // DOWNLOAD 模式返回的是文件夹路径，需拼接文件名
        const pubDir: string = new fileUri.FileUri(uri).path;
        const pubFilePath: string = pubDir + '/' + fileName;

        let srcFile: fs.File | null = null;
        let dstFile: fs.File | null = null;
        try {
          srcFile = fs.openSync(srcPath, fs.OpenMode.READ_ONLY);
          dstFile = fs.openSync(pubFilePath, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE);
          fs.copyFileSync(srcFile.fd, dstFile.fd);
          this.getUIContext().getPromptAction().showToast({
            message: $r('app.string.toast_save_done'),
            duration: 2000
          });
        } catch (e) {
          const err = e as BusinessError;
          hilog.error(DOMAIN, TAG, 'File copy failed: code=%{public}d', err.code);
        } finally {
          if (srcFile) {
            fs.closeSync(srcFile);
          }
          if (dstFile) {
            fs.closeSync(dstFile);
          }
        }
      }).catch((err: BusinessError) => {
        hilog.info(DOMAIN, TAG, 'User cancelled save dialog');
      });
    } catch (err) {
      hilog.info(DOMAIN, TAG, 'Save dialog not supported on this device');
    }
  }

  // 关闭上传模态框，若用户未选择则回传空列表
  private dismissUploadModal(): void {
    this.showUploadModal = false;
    if (this.pendingFileListHandler) {
      this.pendingFileListHandler([]);
      this.pendingFileListHandler = null;
    }
  }

  // 相册：使用 PhotoViewPicker 安全选择图片
  private handlePickFromAlbum(): void {
    try {
      const photoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
      photoSelectOptions.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      photoSelectOptions.maxSelectNumber = 5;
      const photoPicker = new photoAccessHelper.PhotoViewPicker();
      photoPicker.select(photoSelectOptions).then((photoResult) => {
        if (photoResult.photoUris && photoResult.photoUris.length > 0
          && this.pendingFileListHandler) {
          this.pendingFileListHandler(photoResult.photoUris);
          this.showUploadModal = false;
          this.pendingFileListHandler = null;
        }
        // 空结果（含取消）时保留模态框，不通知 web 端
      }).catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'PhotoPicker err: %{public}s', err.message);
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'PhotoPicker failed');
    }
  }

  // 文件：使用 DocumentViewPicker 选择通用文件
  private handlePickFile(): void {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const docOptions = new picker.DocumentSelectOptions();
      docOptions.fileSuffixFilters = ALLOWED_UPLOAD_EXTENSIONS;
      const docPicker = new picker.DocumentViewPicker(context);
      docPicker.select(docOptions).then((docResult) => {
        if (docResult && docResult.length > 0 && this.pendingFileListHandler) {
          this.pendingFileListHandler(docResult);
          this.showUploadModal = false;
          this.pendingFileListHandler = null;
        }
        // 空结果（含取消）时保留模态框，不通知 web 端
      }).catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'DocPicker err: %{public}s', err.message);
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'DocPicker failed');
    }
  }
}
