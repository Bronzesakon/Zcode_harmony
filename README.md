# ZCode 远程

> 智谱 ZCode 桌面端「手机远程控制」页面的 HarmonyOS 客户端 —— **WebView 套壳 + 系统能力对接层**，不重写网页功能。

| 项 | 值 |
|---|---|
| 应用名 | ZCode 远程 |
| 包名 | `com.zcode.control` |
| 平台 | HarmonyOS（Stage 模型），API 6.1.1(24) |
| 设备 | phone / tablet |
| 工程形态 | 单 entry 模块（HAP），ArkTS + ArkUI，应用文案仅中文 |
| 分发方式 | 自用侧载（调试签名） |

## 简介

桌面端 ZCode 生成连接二维码，手机或平板扫码后，应用把远程控制页装载进 ArkWeb WebView，并接管所需系统能力（扫码、附件上传、文件下载、桌面快捷方式、深度链接、状态栏配色、返回手势、屏幕常亮等）。连接 URL 首次扫码后长期持久化，之后冷启动直接进入控制页；凭证失效时长按桌面图标重新扫码即可覆盖。

## 功能

| 能力 | 说明 |
|---|---|
| 扫码连接 | Scan Kit 默认界面扫码（系统统一 UI，相机权限系统预授权）；支持相册识码兜底 |
| 连接持久化 | 完整 URL 存入应用私有 preferences；冷启动直进控制页，无中间层 |
| 扫码入口（仅两处） | ① 首次开屏引导页；② 桌面长按图标 → 快捷方式「扫码连接」 |
| 深度链接 | z.ai 域 `/remote` 链接经域名校验后直达连接流程 |
| 附件上传 | 网页上传选择器替换为原生模态框：相册 + 文件两项，按后缀白名单过滤 |
| 文件下载 | 沙箱缓存 → DocumentViewPicker 转存公共下载目录 |
| 外链策略 | z.ai 全系域名应用内打开，其余域名唤起系统浏览器 |
| 状态栏配色 | 沉浸式布局 + 状态栏高度占位 Row；由网页 DOM 状态嗅探驱动两态固定色（亮/暗各一套），**运行时零取色** |
| 返回手势 | 手机：关模态框 → 网页历史回退 → 退后台保留会话；平板：全吞屏蔽（防多窗/分屏边缘手势误触） |
| 屏幕常亮 | 控制页前台常亮，切后台/退出恢复系统息屏节奏 |
| 稳定性 | 15s 加载超时、主框架错误过滤、网络可达性判定、渲染崩溃自愈 |
| 深色模式 | 跟随系统，向网页传递 `prefers-color-scheme`，窗口/容器底色与网页开屏壳对齐 |

## 技术架构

- **ArkTS + ArkUI** 声明式 UI，**Stage 模型**，单 entry 模块。
- **ArkWeb** `Web` 组件承载远程控制页；`javaScriptProxy` 建立「网页 → 原生」状态通道。
- **Scan Kit** 默认界面扫码（`scanBarcode.startScanForResult`），不申请相机权限。
- **preferences** 持久化连接 URL（键值存储于应用私有目录）。
- **状态栏方案**：窗口沉浸式铺满 + 页面首子组件为状态栏高度占位 Row（避让高度在 `loadContent` 回调后读取，`avoidAreaChange` 刷新）；配色由「网页状态 → 固定色表」查表切换。

## 目录结构

```
.
├── AppScope/                                  应用级配置与资源
│   ├── app.json5                              bundleName / 版本 / 图标 / 应用名
│   └── resources/base/media/app_icon.png      单层满幅应用图标（1024×1024）
├── entry/
│   ├── src/main/
│   │   ├── ets/
│   │   │   ├── entryability/EntryAbility.ets  窗口与状态栏、深链/快捷方式路由
│   │   │   ├── entrybackupability/            备份扩展 Ability
│   │   │   ├── pages/Index.ets                主页面：WebView、扫码、上传/下载、状态栏查表
│   │   │   └── common/                        URL 校验与持久化、上传后缀白名单
│   │   ├── resources/
│   │   │   ├── base/ dark/ tablet/ tablet-dark/   设备与深浅色限定资源
│   │   │   └── base/profile/                  页面路由、快捷方式、备份配置
│   │   └── module.json5                       模块与 Ability 配置（权限、快捷方式、深链）
│   └── hvigorfile.ts
├── build-profile.json5                        构建配置（SDK 6.1.1(24)、产品与签名）
└── README.md
```

## 环境要求

- DevEco Studio（支持 HarmonyOS 6.1.1 / API 24）
- HarmonyOS SDK 6.1.1(24)
- HarmonyOS 手机或平板真机（扫码能力依赖相机，模拟器不可用）

## 构建与安装

**方式一：DevEco Studio** — 打开工程根目录，配置签名（`build-profile.json5` → `signingConfigs`）后直接 Run。

**方式二：命令行**（依赖本机 DevEco 附带的 hvigor，工程未内置 `hvigorw` 包装脚本）：

```bash
hvigorw assembleHap --mode module -p product=default -p buildMode=debug
hdc install entry/build/default/outputs/default/entry-default-signed.hap
```

## 使用流程

1. 桌面端 ZCode 打开远程控制，生成连接二维码；
2. 手机/平板扫码（首次开屏引导页，或长按桌面图标 → 「扫码连接」）；
3. 应用装载控制页，连接长期有效，冷启动直接进入；
4. 连接失效（换机、桌面端重开会话、过期）→ 长按图标重新扫码，新连接覆盖旧连接（单连接覆盖式模型）。

## 实现要点

### 状态栏配色：状态嗅探 + 固定色查表

远程页各状态的顶部色取自其设计 token（`--color-background`、`--color-background-win-alt`、`--color-header`，亮/暗各一套）。运行时**不做任何像素或样式取值**：网页侧注入脚本只检查两个 DOM 标记（Boot 加载壳、主视图根节点）的存在性，向原生回推状态名；原生据此并按设备形态查固定色表切换状态栏底色。设备接管的 KICKED 等状态页因主视图卸载而自动落回 Boot 面配色，无需额外分支。

### 返回手势按设备分化

手机保留系统返回链（关闭模态框 → 网页历史回退 → 退后台不销毁，控制会话保留）；平板恒消费返回事件，避免侧滑返回与多窗/分屏边缘手势冲突。

### 应用图标

单层满幅 `app_icon.png`（1024×1024，边缘零透明留白），圆角交由系统遮罩裁切。不使用分层图标：分层前景自带的圆角图块与四周留白会在图标外缘形成可见黑边。

## 安全说明

连接 URL 含会话凭证（`sid`、`hash`、`mid` 等），仅持久化于应用私有 preferences（`zcode_remote`），**严禁写入日志、文档或提交记录**；本仓库不包含任何真实连接凭证。

## 致谢与许可

- 部分系统能力对接实现参考自 [SakuraNeko/Deepseek-Harmony](https://github.com/SakuraNeko/Deepseek-Harmony)（MIT）；按 MIT 许可要求保留原作者版权声明。
- 本仓库为自用侧载项目，未附开源许可证。
