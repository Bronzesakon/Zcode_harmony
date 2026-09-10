# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发版流程：日常推 `pre` 分支（CI 自动发预发布 Release）；正式版更新本文件顶部段落（须与 `gradle.properties` 的 `zcodeBaseVersion` 一致），把 `pre` 合入 `main` 后 `git tag vX.Y.Z` 推送，CI 读取 `## [X.Y.Z]` 段落作为 Release 说明。

## [1.0.0] - 2026-09-10

首个版本：安卓薄壳（WebView 套壳 + WebSocket 协议层注入），补齐鸿蒙版没有的通知与后台保活。

### Added
- WebView 薄壳加载 `zcode.z.ai/remote/v4`；扫码（ZXing，无 GMS 依赖）/ 剪贴板粘贴 / 手动输入三种接入方式，链接按 `https` + `*.z.ai` + `/remote` 前缀校验后持久化。
- document-start 注入（`WebViewCompat.addDocumentStartJavaScript`）：hook `WebSocket` 收发，注入层同时兼容 `message` 事件与 `onmessage`，Blob/ArrayBuffer 载荷统一转字符串。
- 在页面既有 socket 上实现完整协议客户端（`rpc-frame` 分片重组 + crc32 校验 + ChannelClient 值编解码），支持两种数据来源：
  - **被动**：解码页面自身的 sessions-index 流，零协议写入；
  - **主动（D7）**：为页面未展示的工作区自行开通 bridge 并订阅，覆盖所有工作区。
- 可见性劫持（D12）：`hidden` / `visibilityState` / `hasFocus` 恒定可见，拦截并吞掉 window/document 上的 `visibilitychange` / `pagehide` / `freeze` / `blur`。
- 通知三渠道：`running_tasks`（低重要性、静音、每个运行中任务一条常驻通知 + 群组摘要）、`task_attention`（有声，待确认时补发）、`task_completed`（有声，D10）；常驻通知标题为任务名、正文为 `状态 · 最新进展`（D8），状态只区分「运行中 / 等待确认」（D9）。
- 任务完成判定按参考实现的「上一拍 phase ∈ 运行态 → 这一拍 ∈ 终态」跳变，重跑后再次触发；待确认按 `interactionId` 去重。
- 点击通知拉起应用并尝试定位任务（D11）：按标题匹配任务行并派发完整指针事件序列，失败则安静降级为「仅打开应用」。
- 前台服务（`specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，D6）与 `setRendererPriorityPolicy(IMPORTANT, false)` 保活；返回键不销毁进程，改为退到后台。
- 注入层自带心跳（`pair_status_query`）与陈旧检测：心跳超时且限流允许时关闭 socket，交由页面自身重连逻辑恢复。
- **诊断与独立日志**：`Diagnostics`（内存环形缓冲 + logcat）+ `ShellLog`（外部存储上的轮转日志文件，含崩溃堆栈），设置页可查看、复制、以及通过 FileProvider 分享日志文件——无需 adb 即可取日志。
- **后台存活取证**：注入层上报收帧计数与配对确认次数；回到前台时输出一行结论（`后台存活检查：时长 X，期间收到 N 帧、配对确认 M 次 → 保活成立/失败`），把迁移文档 §8 第 4 步从「凭感觉」变成可读出的测量。
- 云端 CI：`js`（Node 协议层测试）与 `build`（单次 Gradle 调用完成 release 单元测试 + APK）并行；签名从 Secrets 恢复，产物重命名 + md5 + apksigner 校验签名者 DN；`v*` tag 触发 Release。

### Fixed
- 顶部工具栏被状态栏遮挡（右上角溢出菜单被压住）：`targetSdk 35` 起 Android 15 强制 edge-to-edge，窗口会画到系统栏下面且 `statusBarColor` 被忽略。改为 `enableEdgeToEdge` + 根布局消费 systemBars/displayCutout inset，两个 Activity 都覆盖。
- 底部内容相对可视区偏左 / 右边距偏大：去掉 WebView 的 `useWideViewPort` 与 `loadWithOverviewMode`（这两个开关是给桌面版老页面用的，对自适应 SPA 会让视口宽度与实际显示宽度不一致）；同时注入层新增视口尺寸上报，便于把这类问题变成可读数字而不是靠截图争论。

### Added（本轮第二批）
- **网页文件上传**：补上此前完全缺失的 `WebChromeClient.onShowFileChooser`——在此之前网页里点上传是死的。接入系统相册（Photo Picker，上限 5）/文件（SAF）选择器，弹窗「选择上传方式」对齐鸿蒙版；三条路径都不需要存储权限，未改 Manifest。唯一缺口：`MODE_SAVE`（网页请求保存文件）仍返回 false。
- **ColorOS 16 流体云（Android 16 Live Updates）**：走平台路径，无需 OPPO 审批（OPPO 卡片路线需企业账号+定邀白名单+授权码）。声明 `POST_PROMOTED_NOTIFICATIONS`；常驻任务通知请求提升并带状态栏芯片（运行中/等待确认）。提升名额只给 2 张卡：先「等待确认」，再按最近活动取运行中任务（`PromotionPolicy`，7 项单测）。
  - 不调 androidx API 的原因：`setRequestPromotedOngoing` / `setShortCriticalText` 的封装只在 androidx.core 1.17.0，该 AAR 声明 minCompileSdk=36 且 minAGP=8.9.1；而平台侧全部效果是写两个 extras，故按 androidx 源码里的字面键直接写，API<36 惰性无害
  - 「已完成」按官方 UX 规范走标准通知：规范明确「实时更新必须表示正在积极进行的活动……活动发生在过去请勿使用实时更新」
- **界面按 Material 3 规范梳理**：颜色改用 M3 颜色角色 + 启用动态配色（Android 12+ 取系统调色板），修复暗色模式此前从构造上就是坏的问题；字阶改用 M3 字阶；设置页改 MaterialCardView 卡片（12dp 圆角/低强调容器/56dp 行高/outlineVariant 分隔线）；空态与错误态补图标与字阶；上传弹窗颜色改角色（尺寸指标保留对齐鸿蒙）。
- CI 新增 Kotlin 结构检查步骤（括号配平/包名与目录一致/合并残留），放在快 job 里。

### Fixed（本轮第二批）
- edge-to-edge 的系统栏图标此前被无条件强化为「浅色背景深色图标」，暗色模式下不可见；改为按 `uiMode` 在 `SystemBarStyle.light/dark` 间切换。
- 恢复 `action_back` 字符串（此前误判未使用而删除，设置页返回键标题要用）。

### Notes
- 单元测试 35 项（JS 协议层与注入层）+ 20 项（Kotlin 通知判定算法），全部在 CI 运行。
- 仅中文界面；`minSdk 26`，`compileSdk/targetSdk 35`；纯 Kotlin/Java 无 native 库，单一通用 APK。
- 首次提交前无法在本机构建（D13），构建结果以 CI 为准。
