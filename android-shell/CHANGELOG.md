# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发版流程：日常推 `pre` 分支（CI 把最新 APK **覆写**到滚动预发布 Release `android-pre`：固定资产名 + 移动 tag + 刷新标题/说明与推送、编译时间）；正式版更新本文件顶部段落（须与 `gradle.properties` 的 `zcodeBaseVersion` 一致），把 `pre` 合入 `main` 后 `git tag vX.Y.Z` 推送，CI 读取 `## [X.Y.Z]` 段落作为 Release 说明。

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

### Fixed（本轮第三批 · 真机第一轮反馈）
- **输入法弹出时页面输入框不上抬**：`targetSdk 35` 强制 edge-to-edge 后 `decorFitsSystemWindows=false`，`adjustResize` 不再改变窗口大小，键盘只以 inset 形式送达；根布局此前只消费 systemBars/displayCutout，键盘 inset 无人处理，页面测量到的视口高度从未变化。改为 `padForSystemBarsAndIme()`：底部按 `max(系统栏, 键盘)` 加内边距，WebView 随之变矮 → `innerHeight` 下降 → 贴底输入框随键盘上抬。返回 `CONSUMED` 是刻意的：放行原始 inset 会让 Chromium 以为自身顶部被状态栏遮住（实际工具栏已在它上面），从而错移视觉视口。

### Added（本轮第三批 · 为定位问题而加的取证）
- **网页控制台接入诊断日志**：`WebChromeClient.onConsoleMessage` 把页面自己的 console 写进 `Diagnostics`（错误/警告分级、单行折叠、每次加载上限 200 行 × 400 字符），于是「会话加载慢」这类只能由页面回答的问题不再依赖 adb。
- **注入层性能计时**：每个入站帧的解码开销（`JSON.parse` + rpc-frame 重组，全在页面主线程）、`longtask` 计数/最长值、页面自身导航计时（ttfb / DOMContentLoaded / load / 资源数与体积 / 最慢资源）。每 10 秒输出一行「页面开销」（仅当该窗口有流量），与收帧计数在同一条时间线上。
- **网页加载耗时**：原生侧 `onPageStarted/onPageFinished` 记录毫秒数，与注入层的导航计时对照，可区分「网络慢」/「relay 慢」/「页面 JS 慢」。
- **主动订阅改为等页面空闲**：此前配对完成 1.5s 后即无条件为所有工作区开 bridge，与页面自己首屏加载会话争抢同一条 relay socket 和同一个 JS 线程（日志实测 7 个工作区约 10 秒连续握手）。现改为最后一次收帧静默 ≥800ms 才启动，页面持续繁忙时最多推迟 12s，推迟过程留 debug 记录。
- 日志脱敏加强：除 `/remote?...` 外，任何 http(s) URL 的查询串都替换为 `?<redacted>`（网页 console 可能带出完整 URL，而日志是要交给用户分享的文件）。

### Changed（本轮第三批 · 发布流程）
- **预发布改为滚动 Release**（借鉴 sevnX 的 CI 形状）：不再每次 push 新建 `v<base>-pre.<运行号>` 的 tag 与 Release，改为固定在 tag `android-pre` 上——`gh release upload --clobber` 覆写 `zcode-remote.apk`（+`.md5`），`git push --force` 把 tag 移到本次提交（GitHub 用 tag 渲染 Release 页与源码链接，不移动就会挂着旧提交），再 `gh release edit` 刷新标题与说明。下载链接从此恒定：`…/releases/download/android-pre/zcode-remote.apk`。
  - 标题：`ZCode 远程 预发布 · <应用内版本> · <编译时间>`；说明：`## 本轮变更`（与上一次构建之间的提交列表，单个提交时附正文）+ 推送时间 / 编译完成时间（UTC+8）/ 运行号 / 覆盖安装提示。
  - tag 故意不带 `v` 前缀：带 `v` 会命中本工作流自己的 `tags: ['v*']` 正式发版触发条件，从而再起一次运行。

### Notes
- 单元测试 35 项（JS 协议层与注入层）+ 37 项（Kotlin 通知判定/提升策略/MIME 归一），全部在 CI 运行。
- 仅中文界面；`minSdk 26`，`compileSdk/targetSdk 35`；纯 Kotlin/Java 无 native 库，单一通用 APK。
- 首次提交前无法在本机构建（D13），构建结果以 CI 为准。

### Changed（本轮第四批 · 2026-09-12，主界面去应用栏 + 状态栏动态取色）

- **主界面不再有应用栏与溢出菜单**（D16）。顶部那条带子改由根布局底色充当，颜色是**网页自己的顶面表面色**：注入层按 DOM 存在性（`.zcode-boot-loading` / `.bg-background-win-alt`）与**网页自己的断点**（`matchMedia('(max-width: 767px)')`）回推「状态名 + 主题名」，原生查固定色表 `core/PageBarColor.kt` 换色，运行时零取色（与鸿蒙版同一套口径）。三态：`boot`（#F8F8F8/#161616）、`main-header`（#FFFFFF/#202020，手机窄屏）、`main-surface`（#ECECEE/#2B2B2B，宽屏）。宽窄不按设备形态分叉——平板竖屏也会落进网页的手机布局。
- **顶部 inset 当网页的顶部内边距**：状态栏（含挖孔）的底边就是网页的上边缘，网页标题行不会被状态栏时钟压住；**底部反过来刻意不消费**——网页贴到屏幕最底边，手势条悬浮其上（键盘例外，见下）。
- **长按图标菜单（静态 Shortcut）**：`res/xml/shortcuts.xml` 提供「重新扫码」「打开设置」，图标取 Google 官方 Material Icons 字形（`qr_code_2` / `settings`，路径逐字照抄并注明出处）。两者都显式指向 MainActivity，冷启动有返回落点、热启动走 `onNewIntent`。
- **设置页按 MiuiX 设计语言重排**（取色与度量逐项照抄本机 `docs/miuix` 源码的 `Colors.kt` / `Component.kt` / `Card.kt` / `SmallTitle.kt` / `TopAppBar.kt`）：52dp 小应用栏 + 14sp Bold 小节标题 + 16dp 圆角卡片（左右 12dp、行内 16dp、0.75dp 分隔线）+ 17sp Medium 标题与 14sp summary + 10×16dp 箭头。**没有引入 miuix 库本身**：它只有 Compose 实现且要求 Kotlin 2.4.20 / AGP 9.4.0 / compileSdk 37，本工程是 XML/Views + 2.1.0 / 8.7.3 / 35 且本机不能编译。设置页另有 `Theme.ZcodeRemote.Miuix` 并把该 Activity 排除出动态配色——否则按角色取色的 `MaterialSwitch` 会跟着壁纸变蓝，一页两种蓝。
- **状态字样从进展行移到标题行**（D15）：常驻通知标题 = `运行中 · 任务名`，正文只留实时进展；标题强制单行（折叠空白与换行），把卡片的行数尽量留给正文。`displayTitle` 保持原样——通知定位要拿它去匹配页面文本。
- **任务完成时额外弹一张 promoted 卡片**：标题 `已完成 · 任务名`，正文照旧，15 秒后由 `setTimeoutAfter` + 本地 Handler 双保险收回；启动时用 `getActiveNotifications()` 清扫上次进程遗留的同类卡片（常驻通知用户划不掉，进程若在窗口内被杀会留下它）。
- 工作区首次 `rpc-transport-fault` 即不再重开（`MAX_REOPENS_PER_BRIDGE` 2 → 0）。字段证据：重开从来没有真正修好过被拒的工作区（每次 recovered 后 40–75 秒又 fault），却每次都要在页面正忙的那条 relay socket 上插 4 条 RPC；重试交给下一条连接（实测前台约 45–60 秒会自建一条），跨连接的 3 次冷却不变。
- 注入层新增页面状态观察器：`MutationObserver` + 两条媒体查询 change 事件驱动、rAF/微任务合帧去重；`document-start` 若在 `<html>` 建成前运行则挂 `DOMContentLoaded` 重试；缺 `MutationObserver` 时降级为「只跟踪断点与主题」而不是彻底放弃。
- 心跳探针落到 socket 空档时不再报 warn（那是页面自建重连时的常态），真 RPC 丢失仍然报。

### Added（本轮第四批）

- **`tools/check_resources.py`**：校验 res/ 与清单里每个 `@type/name` 都有定义（跳过 `@android:`/`@+id/`/`?attr/` 与库的带点 style 父名），接进 40 秒的 `js` job——本机没有 SDK，这类笔误以前只能在三分钟的 Gradle 里发现。
- **`tools/device_check.sh`**：真机验收一键脚本（取包 → 覆盖安装 → 清日志 → 启动 → 打印状态栏换色/fault 收敛/快捷方式注册/设置页能否起来/通知标题与提升/视图树边界/回前台存活检查），**不依赖屏幕点亮**；设备不可达时直接给出恢复步骤。
- 工作流的错误抓取补上 aapt2 的格式：它的行是 `<文件>:<行>: error: <原因>`，原先只锚行首 `^(e: |error: )` 会整段漏掉，于是「failed linking references」看不到是哪一条引用。

### Notes（本轮第四批）

- 单测 JS 50 → 55、Kotlin 42 → 55（标题/正文新语义、完成卡片 id 区间不与运行中重叠、状态栏色表、页面状态回推四则、观察器降级与竞态重试）。
- 真机验收（一加 PLC110 / ColorOS 16 / API 36 / WebView 154，`1.0.0-pre.42`/`pre.43`）用**日志与视图树**完成：状态栏三态换色、`WebView 0,141-1272,2800`（顶边=状态栏高、底边=屏幕底边）、两条快捷方式已注册、设置页度量逐项对上规格且无崩溃、通知标题与 `PROMOTED_ONGOING` 正确、fault 收敛（reopen 0 次 vs 旧版 22 次）。**纯视觉与交互项未完成**：设备在会话开始前即处于安全锁屏，02:27 又因息屏掉出 Wi-Fi（详见 README「拦路石」）。
- 「标题超长无限单行滚动」做不到：官方 Live Updates 硬性要求「不得设置任何 `customContentView`」，而 `ellipsize="marquee"` 只有自定义 RemoteViews 能控制 —— 与流体云互斥，需在两者间取舍。
