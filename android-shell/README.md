# android-shell — ZCode 远程（安卓薄壳）

> **新会话接手：先跳到文末[「交接文本区」](#交接文本区新会话从这里开始)**（每次会话收尾时就地改写，非追加）。
> 本文件是唯一的状态与配置来源：进度与待验证项看「项目现状」与「已知问题」，
> 迁移清单看「交接清单」，环境、adb 与取日志约定看「本机开发」，发版看「发布流程」。已不再另设交接文档。

把上层鸿蒙工程「ZCode 远程」的薄壳思路搬到安卓：**WebView 加载 `zcode.z.ai/remote/v4` + 原生对接系统能力**，并补上鸿蒙版没有的**任务通知**与**后台保活**。

- 包名 / 应用名：`com.zcode.remote` / 「ZCode 远程」
- 技术栈：原生 Kotlin + AndroidX（不用 Flutter），`minSdk 26` / `targetSdk 35`
- 编译：**完全依赖 GitHub Actions**，本机不装 JDK / Android SDK

设计与决策记录已并入本文件（见「已敲定的决策」与「决策落点」）；上层那份 `../安卓薄壳迁移文档.md` 是**未入库的本地归档**，留着更详细的历史往返与调研出处，本文件不依赖它。

---

## 参考库与离线资料索引（本机，不入库）

`docs/` 与 `ColorOS_docs/` 都在 `.gitignore` 里，clone 仓库拿不到，换机器要手动拷贝。

| 目录 | 内容 | 用途 |
| --- | --- | --- |
| `docs/` | Android 官方文档快照（文件/图片选择 + M3 与 Android 16 界面规范） | 上传链路与界面规范的依据；清单见 `docs/README.md` |
| **`docs/miuix/`** | **miuix 组件库源码**（Compose Multiplatform，Apache-2.0） | **后续界面重写要用的组件库**，约束见下 |
| `ColorOS_docs/` | ColorOS/OPPO 文档 149 篇 + Android 官方 6 篇 | 流体云路线选型的依据；清单见 `ColorOS_docs/README.md` |

### `docs/miuix/`：后续界面重写的组件库

[`compose-miuix-ui/miuix`](https://github.com/compose-miuix-ui/miuix)，小米 MiuiX 设计语言的 Compose Multiplatform 实现。
抓于 **2026-09-11**（HEAD `5157b50`），浅克隆 + 全部 tag——可 `git checkout v0.9.3` 与已发布稳定版对照。

- **模块**：`miuix-ui`（核心组件，`basic/` 下 34 个：Button / Card / TextField / SearchBar / TabRow / TopAppBar / Scaffold / PullToRefresh / Snackbar / Slider / FloatingToolbar…）、`miuix-preference`、`miuix-icons`、`miuix-blur`、`miuix-squircle`、`miuix-nav`、`miuix-shader`。库自带的 VitePress 文档站在 `docs/`（`zh_CN/` 是中文）。
- **许可 Apache-2.0**，与 `docs/` 里那些「仅供本地查阅」的厂商快照不同——它是允许随仓库分发的，放在 `docs/` 下只是沿用既有约定。库自身标注 **experimental**（"APIs may change without notice"）。
- **Maven Central 最新发布是 `0.9.4-rc01`（2026-08-13），比这份 HEAD 旧**：想用未发布的新组件只能照着源码改，这是把源码留在本地的理由；只要稳定版可直接依赖 `top.yukonga.miuix.kmp:miuix-ui:<version>`。

**采用前三件硬约束**（不是取舍，是必须先解决的前置）：

| 项 | 本工程现状 | miuix 要求 |
| --- | --- | --- |
| 界面技术 | **XML / Views**（D3：原生 Kotlin + MDC-Android） | **只有 Compose**：采用即界面层转 Compose。可用 `ComposeView` 增量接入、不必一次性重写，但 M3 主题体系要换 |
| 工具链 | Kotlin 2.1.0 / AGP 8.7.3 / Gradle 8.9 / compileSdk 35 | Kotlin 2.4.20 / AGP 9.4.0 / Compose MP 1.12.0 / compileSdk 37 → 要升一档，且**本机不能构建**，只能推 CI 验 |
| minSdk | 26 | 主体 24（更宽松）；但 **`miuix-blur` 要 33** → 用模糊要么抬 minSdk，要么做条件分支 |

> 与「界面规范（Material 3）」不冲突：现有界面照旧按 M3 角色写；miuix 是**下一次重写**的备选，尚未成为决策（D1–D14 里没有它）。

---

## 项目现状（先读这一节）

**一句话**：功能已全部落地、CI 全绿（JS 50 项 + Kotlin 42 项单测），`pre` 每次推送都把最新 APK **覆写**到滚动预发布 [android-pre](https://github.com/Bronzesakon/Zcode_harmony/releases/tag/android-pre)（固定链接 `…/releases/download/android-pre/zcode-remote.apk`，可直接覆盖安装）——**真机验证已过八轮**（一加 PLC110 / ColorOS 16 / API 36 / WebView 153，含 adb 闭环、网页滚动条方案 A 与「会话加载慢」的逐项实测），下面这些还没有结论：

| # | 待验证 / 待排查 | 现状 | 怎么看 |
| --- | --- | --- | --- |
| P0 | 后台存活 30 分钟（迁移文档 §8 第 4 步，决定整条路线成立与否） | **已验证成立（pre.39，29 分 26 秒）**：`后台期间注入层心跳 176 次（首次在退后台后 6s，原生泵发令 176 次）、收到 1428 帧、配对确认 121 次 → 保活成立`。心跳数 176 = 泵发令 176 = 10s×176 = 29.3 分钟，自洽即证明每次都是真跑而非回前台补跑。**判据本身此前是坏的**（回前台的瞬时抖动会把页面侧计数清零，pre.28/pre.30 因此误报「0 次」），pre.33 起计数单调、判定用原生侧 delta，本行即该修法的最终复验 | 见「交接文本区 → 一句话状态」与「首次真机验证」 |
| P1 | ColorOS 弹「“ZCode 远程”正在当前页面悬浮显示…是否关闭该应用？」 | **未定位**（性质已定性，见下） | 见「已知问题 A」 |
| P2 | 会话加载慢（连标题都要半天） | **壳侧已修完并逐项实测**（重复 bridge 循环收敛、页面持有判定生效、burst 让路生效）；**A/B 已证明剩余延迟不在壳**——关掉全部 bridge 后同样慢（`subscribeConversationV4` 4.7s、`readSession` 报 `Session is not active`），属桌面端 | 见「已知问题 B」 |
| P3 | 流体云是否真的出卡 | 未验证（需 API 36） | 设置 → 诊断里的 `流体云: 可用 / 系统已关闭本应用的推广通知` |
| P4 | 上传链路（相册 / SAF / 取消不卡住）与通知细节（分组、点击定位、完成提示音） | 未验证 | 见「网页文件上传」与「实现要点」 |
| P5 | 标题回退「新建对话」（用户 2026-09-12 报） | **已定位，属页面 + 桌面端**：`readSession` 静默吞失败 + 该会话在桌面端非 active → 标题只能靠任务列表；壳侧已把争抢同一 socket 的握手减到 1/3 | 见「已知问题 D·1」 |
| P6 | 长后台后点进任务长时间不出内容（用户 2026-09-12 报） | **已定位并落地壳侧收敛**：回前台页面自恢复期间，壳为页面已持有的工作区重开 bridge → `rpc-transport-fault` → 每次重开 4 条 RPC 压在页面的 socket 上，持续约 2.5 分钟。`MAX_REOPENS_PER_BRIDGE` 2 → 0 | 见「已知问题 D·2」 |
| — | `MODE_SAVE`（网页请求保存文件） | **未实现**，返回 false 并记 warn | — |

### 已知问题 A：ColorOS「悬浮显示」弹窗

现象：**退出/切走应用时**弹出系统对话框「“ZCode 远程”正在当前页面悬浮显示，可能造成部分操作无响应，是否关闭该应用？」（带「上报此问题」勾选框）。

已定性：这是 ColorOS 的**悬浮窗/叠加层保护**，不是崩溃也不是 ANR（社区同类现象出现在确实带悬浮窗的应用上）。已经排除的：本应用清单**没有** `SYSTEM_ALERT_WINDOW`，全代码库无 `WindowManager.addView` / `TYPE_APPLICATION_OVERLAY` / `TYPE_TOAST` / `setFullScreenIntent`，保活服务只发通知不开窗（README 早期设想的「1px overlay 兜底」**并未实现**）。

剩余嫌疑：① 依赖库（如 `journeyapps:zxing-android-embedded`）向合并清单里带了权限；② 系统把流体云提升出来的胶囊/卡片算在应用头上；③ 用户把应用拖成了 ColorOS 的「自由浮窗」，与代码无关。**有 adb 后 5 分钟可定性**：

```bash
adb shell dumpsys window windows | grep -i -A3 zcode          # 有没有 overlay 类窗口
adb shell appops get com.zcode.remote                          # SYSTEM_ALERT_WINDOW 是否被开启
adb shell dumpsys notification --noredact | grep -i -A5 zcode  # 是否有 promoted 通知在展示
adb shell dumpsys activity activities | grep -i zcode          # 是否被系统置于浮窗/分屏
```

若确认是流体云提升触发，唯一开关点是 `LiveUpdate.requestPromotion`（可只对「等待确认」提升，或整体去掉）。

### 已知问题 B：会话加载慢的判读顺序

三轮真机日志把范围逐步收窄，**结论：壳侧已修完，剩余延迟在桌面端（有 A/B 证据）**。下一份日志按这个顺序读：

1. `网页开始加载` / `网页加载完成(第 N 次回调)，用时 N ms · <路径> · 控制台已捕获 M 行`（原生）。只有**第 1 次**回调能与加载起点比较：后续回调是 SPA 路由切换，此前复用同一起点计时会报出「用时 736007 ms」这种假数字。`控制台已捕获 M 行`是判断网页 console 有没有真的落进文件的唯一数字。
2. `页面加载计时：ttfb=… DOMContentLoaded=… load=… 资源 N 个 / KB`（注入层导航计时）→ 文档与网络层面（实测 ttfb≈400ms，**可排除**）。
3. `页面调用慢 Nms：zcode-agent.<方法>` / `页面调用失败 Nms：… · <桌面端消息>` → **页面自己**发给桌面端的请求与耗时。这是「点进任务半天不出内容」的答案所在。
4. `页面 RPC 10s：N 个（慢 M，失败 K）· <方法> 次数 …` → 每 10 秒一条的窗口汇总，区分「页面没发请求」与「发了但桌面端慢」。
5. `active subscribe start（页面已空闲 Xms）` / `主动订阅完成：用时 N ms` → 自己握手风暴的代价。**注意 `用时` 里包含让路时间**：页面忙时 burst 会逐工作区暂停等它，所以这个数可能到 ~19s（实测 9819ms → 让路生效时 18936ms），这是刻意的，不是变慢。
6. `页面已接管 <key>，关闭重复 bridge` / `页面自己持有 bridge：<key>` → 页面自己为某工作区开了 bridge（⑥ 的判定依据）。
7. `bridge degraded … rpc-transport-fault` / `本次连接放弃重开 <key>（已 fault N 次）` / `放弃 <key>：页面自己持有该工作区…` → 重复 bridge 的三条出路：fault 有上限、下次重连再试、或页面持有+被拒则永久放弃。
8. `页面开销 10s：收帧 N 个（解码合计 Yms）· 长任务 …` → 我们自己的主线程开销（实测解码合计个位数~百 ms、长任务 0~几 ms，**可排除**）。
9. `[web:行号] …` → 页面自己的 console（错误/警告分级，每次加载上限 200 行）。

**三轮的结论链**：

- **第一轮（pre.13/15）**：网络与解码都可排除；真机制是每次 relay 断线后 `resetClient()` 连「页面正在流哪个工作区」这份认知一起丢，重建后给页面自己占着的工作区开第二个 bridge → 桌面端 `rpc-transport-fault` → `reopen` 循环每 60–90 秒重演（21 次 fault / 7 次断线）。
- **第二轮（pre.21）**：补上页面自身 RPC 取证后立刻抓到真问题（`getEnterprisePricing` 反复 `coding_plan_system_busy`、`refreshCodingPlanApiKey` ~1.7s），但 `default` 仍每 ~47 秒 fault。
- **第三轮（pre.22/23）**：① fault 上限生效——`default` 第 3 次 fault 时记 `本次连接放弃重开（已 fault 3 次）`，此后 2 分钟 0 次 fault（此前每 ~47s 一次）；② 页面持有判定生效（`页面自己持有 bridge` + `页面已接管 …关闭重复 bridge`）；③ 起始门与 burst 让路生效（`推迟主动订阅：…在飞页面请求 8 个`；burst 用时 9819ms → 18936ms 说明让路在等页面）。
- **A/B（决定性）**：把「订阅所有工作区」关掉、`订阅状态 active=false bridges=0`（壳一条 bridge 都没有）后打开任务，页面自身 RPC **同样慢、同样失败**，`subscribeConversationV4` 甚至 4703ms（有 7 条 bridge 时是 2902ms）。所以**任务打开的延迟不是壳造成的**。

**桌面端侧的可疑点（不是本仓库的代码，供排查参考）**：打开任务时一批 RPC 同时落在 1.5–2.0s，像是被同一把锁串住——`git.refresh`(1.8s)、`readWorkspaceState`(1.9s)、`usage-stats.getEntitlementSnapshot`(2.0s)、`model-provider.refreshCodingPlanApiKey`(2.0s×2)、`setting.update`(1.8s)、`subscribeSessionsIndexV4`(1.6s)；`zcode-agent.subscribeConversationV4` 2.9–4.7s；并且 `zcode-session.readSession` **失败**：`Session is not active: sess_…`（完成态任务的会话在桌面端不是 active；页面能回退到历史，所以内容最终仍会出来）。

### 已知问题 C（已结案 · 无需干预）：后台期间页面自己把 relay socket 关掉（约每 100–120 秒一次）

> **结论：无需干预，本节只留存证。** 只发生在后台（前台 10 分钟零重连），每次代价是一次干净关闭 + 约 1–2 秒重连
> + 我们重放一轮订阅 burst；后台期间收帧与 ack 都在跑，通知的数据来源不断，用户可感知的只是回前台时页面上闪过
> 「正在尝试重连」。**不要再为它投入**：调用方已确凿定位（页面自己的 `reconnectAfterStaleWaiting`），四个外部
> 解释全部被实测否定，而壳侧唯一的引擎层杠杆是隐藏 API。

**这是用户看到的「正在尝试重连」的来源，也是本轮花时间最多的一项。结论：确凿定位到了调用方，但壳侧没有任何公开手段能阻止它——原因在页面/引擎内部。**

**一、是谁关的（已确凿）**

```
socket.close() 被调用（无参） 来自 at i4t.reconnectAfterStaleWaiting
   (…/assets/index-nOVzQNKW.js:8… ← …/assets/index-nOVzQNKW.js:897:319751)
```

`close()` 的实参是**无参**、关闭码 `1005`、`wasClean=true`，只在后台出现（前台 10 分钟一次都没有）。把 bundle 第 897 行按 0-based 列 319750 逐字符对位，落点是页面 relay 客户端的 `armHeartbeatAckWatchdog` 里那个 `setTimeout` 回调：

```js
this.heartbeatAckWatchdogTimer = void 0,
!(this.state!=='paired' && this.state!=='waiting') && this.reconnectAfterStaleWaiting(this.getReconnectJitterMs())
```

⚠️ **读这条日志前先知道一个坑**：`inject.js` 原先记的「谁调用了 close」是**错的**——它在包装函数自己的函数体里构造 Error，`stack.split('\n')[1]` 拿到的是包装函数那一行，所以两个版本都只报 inject.js 的同一行。修好后第一次真机日志就直接指名道姓（`05f...`→`pre.35`）。凡是要归因调用方的日志，都别用 frame[1] 这种写法。

**二、被实测否定的三个解释**（都在同一台机器、同一套 ColorOS 放行设置下）

| 假设 | 实测 | 结论 |
| --- | --- | --- |
| 我们的探针没发出去 | perf 行 `探针 1` 每 10 秒一次，`paired true socket 1` | 否定 |
| 桌面端没回 ack | perf 行 `链路 ack` 每 10 秒 ~1 次 | 否定 |
| ack 间隔偶发撞上 30 秒看门狗 | 把泵 15s→10s 后周期仍是 ~121 秒（23:37:36.636 / 23:39:37.673 / 23:41:38.573） | 否定 |
| 页面自己的心跳被节流到不跳 | perf 行 `页面心跳`（页面**发出**的 `pair_status_query`）在后台仍是 ~20–25 秒一次，非 0 | 否定 |

也就是说：后台这条链路是**健康的**（收帧、ack、页面心跳都在跑），而页面的 watchdog 仍然每隔约 100–120 秒走一次 `reconnectAfterStaleWaiting`。按 bundle 里的代码，watchdog 是 30 秒、且每次 `applyPairStatus`（收到**任意** `pair_status_ack`）都会先 `clearTimeout` 再重装——在 ack 每 10 秒到一次的节奏下它本不该响。唯一自洽的解释是**在这个被后台化的渲染器里，「重装/cancel 那 30 秒定时器」这条路径没有按预期生效**（被节流的定时器任务已经入队，清除拦不住；或页面自身的调度被推迟到约 4×30 秒），这属于页面与 Chromium 内部。

**三、壳侧试过的杠杆，以及为什么停手**

- 原生侧驱动心跳（每 10s 一次 `evaluateJavascript`）——能保证链路与通知的数据来源，但**不能**阻止页面重连（上面那张表的第 3、4 行）。
- 把可见性劫持推到引擎层：`WebView.setWindowVisibility(View.VISIBLE)`。CI 直接给出
  `e: MainActivity.kt:177:33 Unresolved reference 'setWindowVisibility'`——**该 API 是隐藏的**，应用的公开层拿不到；不反射隐藏 API（API 28+ 亦受限）就走不通。这条路已撤回，代码里保留结论。
- 拦掉页面的 `close()`：不可行。页面在 `reconnectAfterStaleWaiting` 里先 `this.socket = void 0` 再 `close()`、随后 `connect()`，吞掉 close 只会留下一条孤儿 socket 并让页面用另一条，反而更糟。

**四、我们这一侧的止损（已验收）**

每次 socket 重建都会让页面重开工作区，而 `default` 恰好每条连接都被桌面端 `rpc-transport-fault`。pre.31 起改为**跨连接冷却**（`FAULT_COOLDOWN_CONNECTIONS=3` / 10 分钟，见「实现要点」13），真机日志：
`19:35:35 冷却 …\workspace\default：连续 3 条 relay 连接都被桌面端 fault…`，随后 burst 记 `（冷却中 1 个）`。

**五、影响评估（决定要不要继续投入）**

只发生在后台；前台 10 分钟零重连。每次代价是一次干净关闭 + 约 1–2 秒重连 + 我们重放一轮订阅 burst（后台因定时器节流可能被拉长到 20–90 秒，这是「主动订阅完成：用时」偏大的原因）。后台期间通知的数据来源不断（帧与 ack 都在跑），所以用户可感知的只有回前台后页面上短暂出现过「正在尝试重连」。**除非改页面或换到能控制渲染器节流的位置，否则这一项就是接受现状 + 记录证据。**

### 已知问题 D：用户报的两条症状，机制与壳侧能动的部分（2026-09-12）

两条都是**页面/桌面端决定的现象**，壳只能减少自己造成的干扰。写在这里是为了下次不必重新查一遍。

#### D1「点进会话，标题拿不到，回退成『新建对话』」

**同「已知问题 B」第 1 条，机械原因在页面代码里，两处证据链：**

1. **标题只有两条来源，且两条都可能空。** 快照 `assets/index-nOVzQNKW.js` 偏移 `@345935`：
   `activeTaskTitle: u?.title?.trim() ? u.title : formatMessage({id: taskList.newThread})`
   ——即「两处都没有」时回退成「新建对话」。`u` 来自 `resolvedActiveTaskMeta`，其兜底由 `D_e()`
   （`@342149`）提供，里面有 `readSession({…, messageLimit:1})`，而它的
   `.catch(() => { n || s(null) })` **把失败静默吞成 null**，用户看不到任何报错。
2. **兜底那条路对「已完成」的会话本来就失败**：桌面端回 `Session is not active: sess_…`
   （这句话在页面代码里搜不到，是桌面端返回的）。真机日志反复出现这一行。
   于是**标题正确的唯一前提变成「该工作区的任务列表 / sessions-index 已就绪」**——
   列表没到，标题就停在回退值，而且**不会有任何报错**。

壳侧能改的只有「任务列表多快就绪」这一件事：我们的 burst 与页面的会话请求共用同一条 relay socket，
桌面端又按序处理（快照文档 §3/§5），**我们的每一发握手都排在用户刚点的那个任务前面**。
本次的收敛（见 D2）把这条干扰降到约 1/3。**剩下的部分要桌面端回答**：非 active 会话为什么 `readSession`
读不到，以及打开任务时任务列表为什么要等那么久。

#### D2「长时间后台后点进某个任务，长时间不出内容；刷新网页或重启 app 就好了」

真机日志（`pre.39`，2026-09-12 00:12 退后台 → 00:42:05 回前台，29 分 26 秒）：

| 时间 | 事件（`zcode-shell.log`） |
| --- | --- |
| 00:42:05 | 回前台，`后台存活检查 … 保活成立`；链路健康（`paired true`、`链路 ack 1`/10s、收帧持续） |
| 00:42:28 / 00:43:16 / 00:44:04 | `bridge degraded for E:\Mimo: rpc-transport-fault` → 各 `reopening … (attempt 1)` → `recovered`，第三次才 `本次连接放弃重开（已 fault 3 次）` |
| 00:42:28–00:44:04 | `default` 同样反复 fault / 重开 |
| 00:44:14 | `收帧 0 · 链路 ack 0` ——链路静默（页面正在自己重建） |
| 00:44:22–00:44:40 | 一连串 `网页加载完成(第 N 次回调)`，随后 `页面自己持有 bridge: …\default`、`passive: following sessions-index of …\default` |
| 00:44:41–00:44:55 | 页面自己的请求终于走通：`readWorkspaceState 1490ms`、`subscribeConversationV4 2441ms`（`readSession` 仍失败） |

**机制**：回前台后页面自己在做一次全量恢复（重连 + 重开工作区/任务），而壳同时在为「页面已经持有的工作区」
开重复 bridge → 桌面端 `rpc-transport-fault` → 壳重开（每次 4 条 RPC：hello / initialize /
subscribeSessionsIndex / listen）→ 这些 RPC 全部排在**同一条 socket** 上，压在页面恢复用的请求前面。
字段里这一串持续了**约 2.5 分钟**，期间用户看到的就是「点进去一直不出内容」。刷新网页（重开 socket、
清掉双方状态）或重启 app 都能立刻恢复，正符合「重试循环被外部打断就自愈」的形态。

**本次落地的壳侧收敛**：`zcode-protocol.js` 的 `MAX_REOPENS_PER_BRIDGE` 2 → **0**。
理由就是上表：重开**从来没有真正修好过**一个被拒的工作区（每次都 recovered，然后 40–75 秒后再 fault），
却每次都要在页面正忙的那条 socket 上插 4 条 RPC。改成「一条连接一次机会」后，同一窗口的干扰从 3 轮降到 1 轮；
重试交给下一条 relay 连接（**实测前台页面约 45–60 秒就会自建一条新连接**，所以最坏等一分钟），
跨连接的 `FAULT_COOLDOWN_CONNECTIONS=3` 冷却仍在，长期被拒的工作区 10 分钟后彻底让路。

**D3 完成卡片踩在规范边上（本轮新增，取舍已记录）**

`ColorOS_docs/06-…/01-创建实时更新通知（Views 实现指南）.md` 里除硬性要求外还有一句
「如果活动发生在过去，请勿使用实时更新」。完成卡片表示"刚刚结束"，属于终态提示
（官方模板也有"司机已到达"这类），但它确实是本应用里最可能被判为"不该提升"的一张。
之所以还是做（用户明确要求"完成后强提示"）：它只挂 15 秒，持久记录仍在
`task_completed` 渠道的普通通知里。**若哪天真机发现流体云不再出卡，第一步就是把这张 15 秒卡片去掉再试。**
同理，被提升的卡片「默认展开、不可折叠」，这也是"弹出展开为方框"效果的来源（不是我们控制的）。

**没有做、也不打算做的**：回前台后给页面一段「静默窗口」（推迟 burst）。实测页面恢复要 ~2.5 分钟，
任何合理长度的静默窗口都盖不住它，却会让**每次回前台**都可能丢掉刚完成任务的实时通知——这与壳存在的
理由冲突。要真正解决，得让桌面端能同时接受两条 bridge，或让页面别为同一工作区握手两次。


## 一次构建要多快

工作流按墙钟时间排布，`js` 与 `build` 是两个并行 job：

| job | 内容 | 首次 | 有缓存 |
| --- | --- | --- | --- |
| `js` | Node 协议层 + 注入层测试（50 项），不需要 JDK/SDK | ~1 min | ~40 s |
| `build` | 单次 Gradle 调用：release 单元测试（37 项）+ `assembleRelease` + 签名校验 | ~4 min | ~2m 45s |
| `prerelease` | 仅 `pre` 分支：把最新 APK **覆写**到滚动预发布 Release（固定下载链接） | ~20 s | ~20 s |
| `release` | 仅 `v*` tag：用 CHANGELOG 段落发正式 Release | ~20 s | ~20 s |

测试构成：JS 50 项（`tools/protocol.test.js` 32 + `tools/inject.test.js` 18，含手算黄金字节）；Kotlin 42 项（`NotifyStateTest` 22 + `PromotionPolicyTest` 7 + `UploadMimeTest` 8 + `SurvivalVerdictTest` 5）。**这些是唯一能在无设备条件下验证的东西**，真机行为一律以设备为准。

省时间的几个点：`js` 不与 Android 构建串行；`testReleaseUnitTest` 与 `assembleRelease` 放在**同一次 Gradle 调用**里（共享 `compileReleaseKotlin`，源码只编译一次、Gradle 只启动一次）；`fetch-depth: 1`；`actions/setup-java` 的 `cache: gradle` 会恢复 `~/.gradle`（依赖缓存 + 本地 build cache）；`org.gradle.configuration-cache=true` 且 `problems=warn`，所以配置缓存只可能加速、不会让构建失败。

**坑（改 workflow 前必读）**：`paths` 与 `paths-ignore` **不能同时**用于同一事件——GitHub 会创建一个**没有任何 job** 的 run（PyYAML 能解析，本地校验拦不住）。现在只用 `paths` 显式列出会影响 APK 的路径，纯文档改动（README / CHANGELOG / docs / ColorOS_docs）自然落在过滤外，省掉一次构建。

**编译错误怎么读（重要）**：Actions 的**日志**需要鉴权（匿名 404），但 job 页面是服务端渲染的，且 **annotation 会写进 job 页面的 HTML**。所以 workflow 在 Gradle 失败时会把关键错误行（`^e: `、`Execution failed for task`、单测断言等）grep 出来以 `::error::` 重新输出，并加 `--console=plain` 去掉 ANSI/CR 噪声——本机没有 JDK/SDK，这是唯一能读到编译错误的通道，用 `tools/watch_ci.py` 即可匿名读到。

---

## 发布流程：日常走 `pre`（滚动预发布），正式版从 `main` 打 tag

```
main ──────────────────────────────────● v1.0.0   正式 Release
        ╲                              ╱  （你决定何时把 pre 合入）
   pre ──●──●──●──● ──────────────────────►  android-pre   滚动预发布
                                              （同一个 Release，资产每次覆写）
```

- **日常开发只推 `pre`**。CI 通过后，最新 APK 被**覆写**到那条固定的滚动 Release（tag `android-pre`）上，不会每次 push 都堆一个新 Release：
  - 资产名固定 `zcode-remote.apk`（+ `.md5`），下载链接永远是 `…/releases/download/android-pre/zcode-remote.apk`；
  - 标题刷新为 `ZCode 远程 预发布 · <应用内版本> · <编译时间>`；
  - 说明刷新为「本轮变更」（与上一次构建之间的提交列表，单个提交时附正文）+ 推送时间 / 编译完成时间（UTC+8）/ 运行号；
  - tag 会被 force-move 到本次构建的提交 —— GitHub 用 tag 渲染 Release 页与源码链接，不移动就会挂着旧提交而资产已经是新的。
- **正式发布**：更新 `CHANGELOG.md` 顶部段落（必须是 `## [X.Y.Z]`，且与 `gradle.properties` 的 `zcodeBaseVersion` 一致）→ 把 `pre` 合入 `main` → 在 `main` 上 `git tag vX.Y.Z` → 推送。CI 会用 changelog 段落作为 Release 说明。
- 滚动 tag 故意**不带 `v` 前缀**（`android-pre`）：带 `v` 会匹配本工作流自己的 `tags: ['v*']` 正式发版触发条件，从而再起一次运行。CI 用 GITHUB_TOKEN 做的操作也不会递归触发工作流。
- `concurrency` 的 key 含 ref，所以推 `pre` 不会取消 `main` 上正在跑的构建。

### 版本号的唯一来源与覆盖安装

| 位置 | 内容 | 谁读它 |
| --- | --- | --- |
| `gradle.properties` 的 `zcodeBaseVersion` | 版本基准，如 `1.0.0` | Gradle 与 CI 共同读取 |
| `CHANGELOG.md` 顶部 `## [X.Y.Z]` | 正式版的 Release 说明 | 发 tag 时的 notes 脚本 |

`versionName` 由 CI 注入（`pre` 上是 `1.0.0-pre.<run>`，其它分支是基准值）；**`versionCode` 在所有 CI 构建里都取工作流运行号**。这一条是刻意的：Android 拒绝安装 versionCode 低于已装版本的 APK，所以固定 versionCode 会导致「正式版装上之后，再也装不上 pre 包」。用运行号保证单调递增，任何一次 CI 产物都能直接覆盖安装上一个。

---

## 取 APK

**推荐（pre 分支）**：固定链接 **[`…/releases/download/android-pre/zcode-remote.apk`](https://github.com/Bronzesakon/Zcode_harmony/releases/download/android-pre/zcode-remote.apk)**（Releases 页面上的「ZCode 远程 预发布」那条，资产每次构建覆写，链接不变）。

其它路径：**Actions → Android Shell Build → 最近一次运行 → Artifacts → `zcode-remote-apk-<sha>`**（同样含 `zcode-remote.apk` 与 `.md5`）。

---

## 签名密钥（与 zemote / 鸿蒙工程都无关）

这个应用有**自己的** release keystore，不复用 zemote 那把，也不用鸿蒙父工程的材料。原因：

- 鸿蒙工程用的是 DevEco **自动签名**材料（`~/.ohos/config/default_Zcode_harmony_….p12`，别名 `debugKey`，`SHA256withECDSA`）。它在 `build-profile.json5` 里的密码是 **DevEco 加密后的密文**，真值取不到，`keytool` 打不开；而且自动签名的 debug 密钥可能被 DevEco 重新生成，一旦更换，安卓侧就无法覆盖安装。HarmonyOS 的 `.p7b` profile 安卓也不使用。
- 复用自己的密钥才能保证：**今后所有版本的 APK 都能互相覆盖安装**（Android 只接受签名一致的升级包）。

材料（全部在 `scratch/`，已被 `.gitignore` 忽略，只在本机）：

| 文件 | 内容 |
| --- | --- |
| `scratch/zcode-remote-release.p12` | PKCS12 keystore，RSA 2048，SHA384withRSA，有效期 10950 天，别名 `zcode-remote` |
| `scratch/keystore-password.txt` | keystore 密码（32 位字母数字） |
| `scratch/keystore-base64.txt` | keystore 的 base64（**单行 3676 字符，无尾随换行**），配 Secrets 用 |

证书指纹（可公开，用于核对 CI 出的包是不是这把密钥签的）：

```
SHA-256  B1:15:06:32:51:37:AA:D5:54:6F:39:02:AC:7D:FF:79:48:42:71:83:E0:71:B7:19:46:09:13:76:71:C3:46:50
SHA-1    19:B4:73:84:3F:75:37:9C:BD:55:A3:AE:E6:D1:E4:1B:C9:03:40:CC
```

> ⚠️ **务必备份 `scratch/` 这三个文件**（例如放进密码管理器或加密备份）。keystore 一旦丢失，之后发布的 APK 都无法覆盖安装已有版本，只能卸载重装。

轮换密钥（只有在确有必要时）：用 DevEco 自带的 keytool 重新生成，然后**同步更新全部 4 个 Secrets**，否则新旧包签名不一致。

```bash
JBR="/e/DevEco Studio/jbr/bin/keytool.exe"
PW="<自己定的密码>"

"$JBR" -genkeypair -v \
  -keystore scratch/zcode-remote-release.p12 \
  -storetype PKCS12 -alias zcode-remote \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$PW" -keypass "$PW" \
  -dname "CN=ZCode Remote, OU=Mobile, O=ZCode, L=Unknown, ST=Unknown, C=CN"

base64 -w0 scratch/zcode-remote-release.p12 > scratch/keystore-base64.txt
"$JBR" -list -keystore scratch/zcode-remote-release.p12 -storepass "$PW"
# 应输出 PrivateKeyEntry，而不是只有证书条目
```

---

## 一次性配置：GitHub Secrets（4 个）

在 **`Bronzesakon/Zcode_harmony` → Settings → Secrets and variables → Actions → New repository secret** 逐个添加。名称必须完全一致。

| # | Name | 取值 | 说明 |
| --- | --- | --- | --- |
| 1 | `ANDROID_KEYSTORE_BASE64` | `android-shell/scratch/keystore-base64.txt` 的全部内容 | 单行 3676 字符，**不要手工加换行或空格**；`+` `/` `=` 都是正常字符 |
| 2 | `ANDROID_KEYSTORE_PASSWORD` | `android-shell/scratch/keystore-password.txt` 的内容 | |
| 3 | `ANDROID_KEY_PASSWORD` | **与 #2 完全相同** | PKCS12 要求 key 与 store 同密码 |
| 4 | `ANDROID_KEY_ALIAS` | `zcode-remote` | |

取值怎么拿到（注意别把密码贴进任何聊天或 issue）：

```bash
cd /e/Zcode_harmony/android-shell
cat scratch/keystore-password.txt          # #2 / #3 的值，手动输入到网页
cat scratch/keystore-base64.txt | clip     # #1 的值直接进剪贴板（Git Bash 的 clip 不会加换行）
```

粘贴 `#1` 时若用记事本中转容易被自动换行破坏；用上面的 `clip` 最稳，粘贴后确认输入框里是**连续一行**。

**不配置也能出包**：CI 会回退 debug 签名并在日志里给出 `::warning title=Debug-signed build::`，但那种 APK 与正式包签名不一致，只能卸载重装。

**配置是否生效怎么看**：构建日志里应出现 `Release signing configured from secrets.`，并且 `Rename APK, checksum and verify signature` 步骤会打印

```
zcode-remote.apk -> CN=ZCode Remote, OU=Mobile, O=ZCode, L=Unknown, ST=Unknown, C=CN
```

若这一步打印 `CN=Android Debug` 而 `SIGNING_CONFIGURED=true`，CI 会直接**报错失败**（避免把 debug 包当成正式包发出去）。

---

## 交接清单：不在 git 里、必须随项目一起带走的东西

这个子项目**不是自包含的**。clone 仓库只能拿到代码，下面这些要么在父仓库根、要么只在本机——换机器、换对话、或把子项目抽成独立仓库时，逐项对照。

### 在 git 里（clone 即随行）

`android-shell/app/src/**`（Kotlin、资源、`assets/inject.js`、`assets/zcode-protocol.js`）、`app/src/test/**`（3 个 Kotlin 单测）、`tools/**`、`gradle/wrapper/**` + `gradlew` + `gradlew.bat`（**必须保留**，CI 靠它构建；`gradlew` 必须是 LF，`.gitattributes` 已保证）、`build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`（版本基准 `zcodeBaseVersion` 在这里）、`.gitattributes` / `.gitignore` / `key.properties.example` / `CHANGELOG.md` / `README.md`。

### ⚠️ 在父仓库根，不在子项目目录内（最容易漏）

| 路径 | 为什么必须跟着走 |
| --- | --- |
| `.github/workflows/android-shell.yml` | 整个构建/签名/滚动发布流程。所有 step 都用 `working-directory: android-shell`，**路径写死**——子项目改名或换目录必须同步改 |
| `.github/scripts/android-shell-release-notes.ps1` | 正式发版（`v*` tag）时从 `android-shell/CHANGELOG.md` 取 Release 说明 |

若把子项目抽成独立仓库：把 `.github/` 一起搬过去，把 workflow 里的 `working-directory` 改成 `.`（或删掉），`paths` 过滤简化为 `**`。

### ⚠️ 不在 git 里，仅本机保留（必须手动拷贝/备份）

| 路径 | 大小 | 内容 | 处置 |
| --- | --- | --- | --- |
| `android-shell/scratch/` | 13 KB | 签名密钥：`zcode-remote-release.p12`、`keystore-password.txt`、`keystore-base64.txt`；另有一份 `zemote-planB.patch`（已放弃的 zemote CI 改造，仅供回溯，可随时删） | **务必备份密钥那三个**——丢了就无法再给老版本做覆盖安装。CI 从 4 个 Secret 读，本地构建不需要，但要带走 |
| `android-shell/docs/` | 124 MB | ① Android 官方文档快照（文件/图片选择 + M3 与 Android 16 界面规范），界面与上传规范化的依据；② **`docs/miuix/`：Compose Multiplatform 组件库源码（Apache-2.0，含全部 tag），后续界面重写用**——见「参考库与离线资料索引」 | 手动拷贝；厂商快照因版权不入库（miuix 本身是 Apache-2.0）。目录里有 `README.md`（抓取清单 + 结论摘要） |
| `android-shell/ColorOS_docs/` | 87 MB | ColorOS/OPPO 文档 149 篇 + Android 官方 6 篇（流体云、泛在服务、Live Updates），流体云路线选型的判断依据 | 手动拷贝；同上，目录内有 `README.md` |
| `../安卓薄壳迁移文档.md` | 50 KB | 迁移调研与历史回填的**完整归档**（§1–§12：技术事实清单、被推翻的三处假设、决策往返、CI 反馈回路）。耐久结论已全部并入本 README，**本文件不再依赖它**，留作细节出处 | 手动拷贝；不入库。原 `android-shell/` 下的旧快照已删（缺 §12） |
| `../项目文档.md` | 20 KB | 鸿蒙侧需求与决策历史 | 手动拷贝；不入库 |

### GitHub 仓库侧（不在文件系统里）

- **4 个 Secrets**（缺失时 CI 回退 debug 签名，见上一节）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS`。换仓库/换密钥时必须同步更新，否则新旧包签名不一致 → 只能卸载重装。
- **正式发版的 tag 命名空间撞车（待你决策）**：`v1.0.0` 已被**鸿蒙版**的 Release 占用（资产是 `entry-default-unsigned.hap`）。同一仓库两个应用共享 tag 空间，而安卓正式发版的触发条件是 `tags: ['v*']`——给鸿蒙版打 `v1.0.1` 会**误触发安卓构建**并在那个 tag 上发布安卓 APK。建议把安卓侧改成 `tags: ['android-v*']`（发版用 `git tag android-v1.0.0`）；未擅自改，因为这会改变你的发版习惯。日常预发布侧已无此问题：滚动 tag `android-pre` 不带 `v`，与 `v*` 互不触发。

---

## 首次真机验证：迁移文档 §8 第 4 步（关键里程碑）

这一步决定整条薄壳路线是否成立，因此把它做成了一次可读出结论的测量，而不是靠感觉：

1. 安装 APK，扫码接入（桌面端 ZCode → 远程控制 → 显示二维码）。
2. 停留到页面加载完成，确认通知权限弹窗出现并允许。
3. 打开 **设置 → 诊断（真机调试）**，确认「后台存活检查」显示 `已配对`，且「累计帧数」在增长。
4. 退到后台（**不要**在最近任务里划掉；有 adb 时用 `input keyevent KEYCODE_HOME`，别用 BACK——会被网页历史吃掉），等 **30 分钟**——期间最好让桌面端至少跑一个任务。**判据是"后台期间心跳有没有执行"，不是"新到了多少帧"**（帧可以在一个定时器都没跑的情况下继续到达）；应用处于后台时由前台服务每 15s 原生驱动一次心跳。
5. 回到应用，打开 **设置 → 诊断**，读第一行：

```
后台存活检查：时长 30 分 4 秒，后台期间注入层心跳 118 次（首次在退后台后 12s，原生泵发令 120 次）、收到 214 帧、配对确认 178 次 → 保活成立（后台心跳在跑）
```

判读方式（计数是**原生侧基准的 delta**，首跳延迟 `>60s` 即说明是恢复瞬间的补跑）：

| 结论 | 含义 |
| --- | --- |
| `保活成立（后台心跳在跑）` | 后台期间心跳真的执行了（首次心跳出现在退后台后几秒内），通知有真实数据来源 |
| `后台心跳在跑（首次延迟未知：窗口末尾有前后台抖动）` | 计数>0，但回前台那一瞬出现过一次 2ms 的「前台→后台→前台」抖动，注入层的首跳延迟被重置。**这是正常结果**，不是失败——判定改用计数负责 |
| `心跳只在恢复瞬间补跑，后台期间很可能没执行` | 逾期的定时器/消息在回前台那一瞬一起补跑；**先看 ColorOS 电池策略有没有放行**，不要在壳里找原因 |
| `后台期间心跳未执行（定时器与原生发令都没跑）` | 计数为 0；先确认 `后台心跳泵已启动` 之后 15 秒内有没有 `后台心跳泵 #1 次发令` |

> **别被 0 次骗过第二次**：pre.28/pre.30 两轮都报「心跳未执行」，而泵其实一直在跑——原因是回前台的瞬时抖动把页面侧的计数清零了，判定在 16ms 后读到 0。判据已改为原生侧 delta 并抽成可单测的 `core/SurvivalVerdict.kt`；若再看到 0 次，请同时看 `页面开销` 的时间间隔（15s = 泵在驱动、20s+ 或断档 = 真的没跑）。

同一行的下方还有「保留服务 / 注入脚本 / 工作区数量 / 日志文件路径」。要完整日志就点 **分享 / 导出日志文件**——日志写在应用外部存储目录并带崩溃堆栈，进程被杀也留得下，不需要电脑。

---

## 实现要点（改代码前先读）

1. **document-start 注入是硬性前提。** `WebViewCompat.addDocumentStartJavaScript` 必须在 `loadUrl` 之前装好，晚了就漏掉页面的首个 WebSocket 连接。系统 WebView 不支持时会回退到 `onPageStarted` 并在日志里警告「可能漏首帧」。
2. **协议层比预期深一层。** 通知要的 `sessions-index` 不在明文的 relay 载荷里，而是包在 `rpc-frame`（base64 + crc32 + 分片）里的 ChannelClient 值流；`assets/zcode-protocol.js` 实现了这一层，并有 50 项 Node 测试钉住线格式与注入层（含手算的黄金字节）。
3. **不要调用 `webView.onPause()`。** 它会挂起 WebView 的定时器，正好掐掉页面的 relay 心跳。
4. **返回键不销毁进程**，`moveTaskToBack(true)` 退到后台，保住连接。
5. **`_bridges` 与 `_bridgesById` 是两个索引**：前者按工作区键（生命周期/状态），后者按 `bridgeSessionId`（入站帧路由）。混用会让所有响应被静默丢弃——这个 bug 已被 Node 测试抓到过一次。
6. **可见性劫持救不了后台定时器，前台服务也救不了——只有 ROM 放行能救。** 劫持只让页面「以为自己可见」，从而不主动暂停；但应用在后台**整体可能拿不到执行权**（ColorOS 实测：计划 +15s 的定时消息迟到 10 分 30 秒、进程 CPU 十分钟零 tick，而同期进程未被冻结、系统未休眠、主线程空闲）。前台服务 + renderer priority 只保证**进程**活着，不让 **JS 定时器**跑起来。所以心跳改由前台服务每 15s 用 `evaluateJavascript` 原生驱动，而这套东西**只有在「设置 → 电池 → 应用耗电管理」放行之后才有意义**。三条踩过的坑，改这段前必读：
   - **两条跳都必须是异步消息**：泵自己的定时器、以及它触发的 JS 求值（`ShellRuntime.mainHandler`）。普通主线程消息在窗口不可见时会被 Choreographer 同步屏障饿死（早期实测：+15s 的投递迟到 3 分 26 秒，恰好在回前台那一瞬补跑）。
   - **页面自己的 10 秒定时器在后台确实停**：后台日志里 `页面开销` 的时间间隔变成 15 秒（只剩泵在驱动），回前台后又回到 10 秒——这是判断「谁在驱动心跳」的现成指纹。
   - **判定行要按「原生侧 delta」看，不要相信页面侧的绝对值**：注入层在收到「进后台」时不再清零自己的计数（清零会被回前台的瞬时抖动利用，把判定读成 0 次，见「已知问题」与 `core/SurvivalVerdict.kt`）。详见「交接文本区」。
   - **泵的节奏是 10 秒，不是 15 秒**：它顶替的就是页面那条被隐藏页面节流掉的 10 秒心跳，所以按 10 秒才算等价（前台实测 `页面心跳 1`/10 秒，后台降到 ~20–25 秒一次）。改 15 秒→10 秒**并没有**解决页面每 ~120 秒自关 socket（见「已知问题 C」的四次假设否定），不要以为它解决了——那一项已结案、**无需干预**。
   - **`页面开销` 行尾的 `链路 ack / 探针 / 页面心跳` 是后台取证的三把尺子**：`探针` 只在真正走到 `injectPayload` 时 +1（0 就是没发出去）；`页面心跳` 只数页面**发出**的 `pair_status_query`（我们自己的探针在 `injecting` 期间不进入观测）。这三项一起看，才能把「我们没发」「桌面端没回」「页面定时器停了」区分开。
   - **`WebView.setWindowVisibility` 是隐藏 API**：想从引擎层解除隐藏页定时器节流时会想到它，CI 会给出 `Unresolved reference 'setWindowVisibility'`。不要再试。
7. **主动订阅（D7）会带来一处副作用**：我们为其它工作区开的 bridge，其 rpc-frame 会被页面当成未知 bridge 缓存（参考实现的 `_pendingBridgePayloads`），长时间会有内存增长。设置里的「订阅所有工作区」开关可随时退回纯被动模式；每条连接的帧量很小，实测可接受。
8. **凭据不外泄。** 远程链接的 `sid/hash/mid` 严禁进日志/文档/输出；`Diagnostics.redact` 会剥掉 `/remote` 之后的查询串，`device_sid` 只学不用、绝不记录。
9. **颜色一律用 M3 颜色角色**（`?attr/colorSurface` 等），不要新增固定色值。固定浅色会让暗色模式从构造上就是坏的——这正是本轮修掉的问题（见"界面规范"一节）。新增界面元素时也请用 M3 字阶（`?attr/textAppearance*`）而不是手写 sp。
10. **`UploadMime.kt` 里的 `WILDCARD` 是拼接出来的，不要"顺手简化"成单个字面量。** 写成单个字面量时 `compileReleaseKotlin` 会在该列报 `Syntax error: Expecting a top level declaration`，连续三轮 CI 复现、报错逐字节相同，而文件字节是干净的纯 ASCII。Kotlin 的块注释可嵌套，嫌疑是词法器在注释深度上失手；拼接写法语义完全相同且已验证可编译。
11. **改完 Kotlin 先跑 `python tools/check_kotlin_structure.py`**：括号配平、包名与目录一致、合并残留，一秒出结果；CI 的 Static checks job 也会跑它。
12. **网页那条 14px 滚动条与"内容居中"在安卓上不可兼得，且**当前一律不碰**——不要再往注入层加滚动条 CSS。** 事实链：页面自己的样式表里有全局的 `*{scrollbar-width:auto;scrollbar-color:var(--color-border) transparent}` + `::-webkit-scrollbar{width:14px;height:14px}`（thumb `border:3px solid transparent` + `background-clip:padding-box`，可见部分 8px 圆角胶囊）；真机量到内容盒 1222px / 屏幕 1272px（dpr 3.5），thumb 29 设备像素宽、两侧内缩 3px——与上述规则逐像素吻合，所以底部输入框左右留白 16 vs 30 CSS px、中心偏左 7px。**Chrome 官方文档**明确："给 `::-webkit-scrollbar` 设 `width`/`height`，会把它变成 classic（占位）滚动条"。**Android WebView 更近一步**：它在引擎层把 overlay 滚动条渲染整体关掉了（WebView 负责人 torne@chromium.org 在 [issue 40226034](https://issues.chromium.org/issues/40226034)："WebView makes the blink scrollbars transparent [layer_tree_settings.cc:415] … This disables *all* rendering of overlay scrollbars in WebView"；该请求至今 P3/New），因为根滚动条约定由 Android View/主题绘制；也因此 `scrollbar-width:thin` 这类标准属性在真机上不生效（pre.17 实测无变化），唯一的把手是把 legacy 轨道宽度改小/改没（pre.16 零宽时滚动条消失、pre.18 2px 时中心偏移降到 0.86px）。鸿蒙 ArkWeb 没做这个关闭，所以同一页面在那边是 overlay、内容居中。
    **当前做法（方案 A，已实现）**：注入层 `§6` 做两件事——① 一条 `::-webkit-scrollbar{width:0!important;height:0!important}` 把网页那条轨道压成 0（**唯一动到网页的地方，且只动宽度**），内容盒随即回到满宽（真机复核：卡片左右留白 59/59、中心 635.5 = 屏幕中心）；② 自绘 overlay 指示条：`document` 上装**捕获阶段**的 passive `scroll` 监听（scroll 不冒泡但捕获路径照走，`event.target` 即滚动容器，因此不依赖任何选择器），一个复用 `position:fixed` 胶囊按容器矩形定位——可见 8px、距右缘内缩 3px、圆角 9999px、最短 32px（全是网页 thumb 自己的数值），颜色在每个滚动 burst 起始时从容器上读一次网页 token `--color-border`，滚动时显示、停止 700ms 后淡出；网页刻意隐藏滚动条处（`[class*=scrollbar-hide]`、pptx 渲染面、xterm 视口）跳过。性能：空闲零成本，滚动中每帧只写一次 transform，容器矩形每 burst 只量一次。**v1 只做指示、不可拖拽，只处理纵向**。
    **顺带一条取证捷径**：这类"网页布局为何如此"不必靠真机截图猜——静态资源是公开的（不带凭证），`curl -s https://zcode.z.ai/remote/v4/assets/index-<hash>.css` 就能读到页面自己的规则；hash 随构建变化，可从旧记录里取，或先从带凭证的 `/remote/v4` 页面里找（该 URL 含凭证，别回显）。
13. **页面自身的 RPC 要和我们的 bridge 分开看，且「页面覆盖情况」必须活过 relay 重连。** 被动观测不只读 sessions-index：它现在也记录页面自己的 promise 调用/回复（`_tracePageCall` / `_tracePageResult` → 日志里的 `页面调用慢`、`页面调用失败`、`页面 RPC 10s`），因为「点进任务不出内容」那个请求是**页面的**，壳自己的 bridge 永远看不到。四条硬约束：① 桌面端在一条 relay socket 上按序处理，我们的握手风暴会排在页面请求前面——所以**页面已覆盖的工作区绝不重复开 bridge**，而这份认知必须由 `inject.js` 的 `pageCoverage` 跨 `resetClient()` 存活（第一轮正是它在重连后丢失，导致重复 bridge 与 `rpc-transport-fault` 死循环，见「已知问题 B」）；② `_observeInboundRpc` 里 promise 回复按 `(bridgeSessionId, id)` 配对，**不要求先学到工作区**，否则页面 bridge 的回复会被静默丢掉——那样第 3/4 条日志永远为空；③ **「页面拥有某工作区」不能只凭一个证据**：页面开了 bridge（入站 `workspace-bridge-ready` 且 id 不在 `_requestedBridgeIds` 里）**且** 桌面端确实拒掉我们的 bridge，两个同时成立才永久放弃（`_pageOwned`）。只有前者就撤会静默丢掉通知覆盖（页面有 bridge ≠ 它在流 sessions-index）；只有后者就永久放弃则会把瞬时故障当成结论。另外任何工作区在**同一条 relay 连接**内 fault 超过 `_maxReopens`（默认 2）就停止重开；而**跨连接**的重复 fault 另有一套冷却（`FAULT_COOLDOWN_CONNECTIONS=3` / `FAULT_COOLDOWN_MS=10 分钟`）：同一条连接只算一记 strike，连续 3 条连接都 fault 就冷却 10 分钟、`start()` 与 `_scheduleReopen` 都不再为它开 bridge，到期自动重试。这一条是必需的——每次 socket 重建都会把「本连接内放弃」的预算清零，没有跨连接记忆时 `default` 这种「每条连接都被拒」的工作区会永远重开下去（真机已验收，见「已知问题 C」）。④ burst 的每一站之前都 `awaitPageIdle()`：页面有未回请求时暂停，让用户刚点开的请求先走；让路预算整个 burst 共享（8s），所以再忙的页面也只能把 burst 拉长有限时间——**这也意味着 `主动订阅完成：用时` 可能到 ~19s，是刻意的**。

---

## 任务通知（这是壳存在的理由）

三个渠道，**创建时**就定下重要性——Android 不允许事后改渠道重要性，所以「待确认要响、运行中要静」必须靠分渠道而不是靠切重要性：

| 渠道 | 重要性 | 用途 |
| --- | --- | --- |
| `running_tasks` | LOW（静音） | 每个运行中任务一条常驻通知（标题=任务名，正文=`状态 · 最新进展`）+ 一条「N 个任务运行中」群组摘要 |
| `task_attention` | DEFAULT（有声） | 任务等待确认时补发的**可拉掉**提醒 |
| `task_completed` | DEFAULT（有声） | 任务完成/失败的提醒（D10） |

- **状态词只有两个**（D9）：`运行中` / `等待确认`；正文取最新进展，过长截断。
- **完成判定**用参考实现的跳变：上一拍 `phase ∈ 运行态` → 这一拍 `∈ 终态`。同一任务重跑后会再次触发；`prewarming` 之类的中间态不算完成。
- **待确认按 `interactionId` 去重**，所以同一处交互不会反复响；确认后任务回到运行态，常驻通知正文随之更新。
- **点击通知**拉起应用并尝试定位任务（D11）：按任务标题在 DOM 里找可点击祖先并派发完整指针事件序列；页面改版即失效，会**安静降级**为「仅打开应用」。这条是最脆弱的功能，别指望它永远有效。
- 常驻通知的更新是**节流**的（约 900ms 合并一次），完成/待确认事件则立刻发（`ShellRuntime.enqueueOngoing`）——否则会被每秒数次 preview 更新淹没。
- 流体云（ColorOS 16）只提升最多 2 张卡，见「ColorOS 16 流体云」一节。
- 保活服务另有一条 `keepalive` 渠道（IMPORTANCE_MIN），空载时显示「连接中」。

---

## 界面规范（Material 3）

> **2026-09-12 补充：设置页改成 MiuiX 设计语言，主界面不再有应用栏。**
> 设置页的取色/度量逐项照抄 `docs/miuix`（compose-miuix-ui/miuix，Apache-2.0 本地快照）里
> `Colors.kt` / `Card.kt` / `Component.kt` / `SmallTitle.kt` / `TopAppBar.kt` 的默认值，
> 落在 `values/miuix_colors.xml`（+ `values-night/`）与 `values/miuix_styles.xml`；
> **没有引入 miuix 库本身**（它只有 Compose 实现，且要 Kotlin 2.4.20 / AGP 9.4.0 / compileSdk 37，
> 而本工程是 XML/Views + Kotlin 2.1.0 / AGP 8.7.3 / compileSdk 35，本机又不能编译，一次过夜把工具链
> 抬两档风险太大）。要真正换成 miuix 组件时，替换的是这一层的实现，规格不动。
> **主界面**则只剩网页：顶部那条带子的颜色由注入层回推的「页面状态 + 主题」查固定表得到
> （`core/PageBarColor.kt`），见「已知问题 D」与「实现要点」。

本轮（2026-09-11）把界面从「手写固定浅色」改成按 M3 规范：

| 维度 | 做法 |
| --- | --- |
| 颜色 | 全部使用 M3 **颜色角色**（`?attr/colorSurface` / `colorOnSurfaceVariant` / `colorOutlineVariant` / `colorSurfaceContainerLow` …），删除自建调色板。角色体系自带对比度保证（M3 文档：「动态配色旨在满足色彩对比度方面的无障碍标准」） |
| 动态配色 | `DynamicColors.applyToActivitiesIfAvailable`：Android 12+ 采用系统/壁纸派生的调色板，于是本应用与 ColorOS 的流体云、系统设置同一套配色；低版本回落 M3 基础配色（DayNight 齐全） |
| 暗色模式 | 由角色 + DayNight 自动正确。此前主题是 DayNight 但色值全钉死为浅色，暗色下是坏的同时系统栏图标还会不可见 |
| 系统栏 | `enableThemeEdgeToEdge()` + `padForSystemBars()`：targetSdk 35 强制 edge-to-edge，内容必须自己消费 inset；系统栏图标按 `uiMode` 在 light/dark 间切换 |
| 字体 | M3 字阶（`textAppearanceHeadlineSmall/TitleSmall/BodyLarge/BodyMedium`）替代手写 sp |
| 组件 | 设置页每小节一张 `MaterialCardView`（filled / 12dp 圆角 / `colorSurfaceContainerLow`）；行高 56dp、按钮最小高度 48dp 保证触达区；分隔线用 `colorOutlineVariant` |
| 无障碍 | 装饰性图标 `importantForAccessibility="no"` 且由 `?attr/colorOnSurface` 着色；工具栏返回键设 `navigationContentDescription` |

**已接受的取舍**：不再镜像鸿蒙版的固定 `#F8F8F8` 配色。两端长得一样是 nice-to-have；暗色正确、与平台一致是本轮目标。若要恢复品牌固定色，需同时提供 light/dark 两套角色值（只改一套会让对比度出问题）。

上传弹窗（`dialog_upload_source.xml`）是唯一例外：**颜色**已改角色（否则暗色下白卡会突兀），但**尺寸指标**（16dp 圆角 / 24dp 内边距 / 磁贴 14dp 与 12dp 间距）仍按用户要求对齐鸿蒙 `showUploadModal`。若要纯 M3，把圆角改成 28dp 即可。

---

## ColorOS 16 流体云（Android 16 Live Updates）

**路线选择**：走平台路径，**不走** OPPO 泛在服务卡片。后者需企业开发者账号 + 定邀白名单（`fwst@oppo.com`，T+2）+ 绑定包名与签名 SHA1 的授权码；而 ColorOS 16 直接消费标准 Android 16 Live Updates。依据与出处见本地归档 `ColorOS_docs/06-Android原生Live Updates（双兼容路径）/`。

实现要点：

- `AndroidManifest` 声明 `POST_PROMOTED_NOTIFICATIONS`（普通权限，声明即具备资格）。
- `notify/LiveUpdate.kt` 在常驻任务通知上请求提升，并设置状态栏芯片文本（`运行中` / `等待确认`，复用 D9 的两个状态词）。
- **为什么直接写 extras 而不调 androidx API**：`setRequestPromotedOngoing` / `setShortCriticalText` 的 androidx 封装只存在于 `androidx.core 1.17.0`，而该 AAR 声明 `minCompileSdk=36` 且 `minAndroidGradlePluginVersion=8.9.1`（读发布产物的 `aar-metadata.properties` 得到）——采用它要连带把 Gradle、AGP、compileSdk 一起上抬。而平台侧的全部效果就是写两个 extras（androidx 源码里 `setRequestPromotedOngoing` 就是 `extras.putBoolean`），所以按字面键 `android.requestPromotedOngoing` / `android.shortCriticalText` 直接写，API < 36 时惰性无害。**toolchain 升到 compileSdk 36 后，应换回官方 API。**
- 提升名额只给 **2 张卡**（`core/PromotionPolicy.kt`，纯逻辑 7 项单测）：先「等待确认」（用户要处理的），再按最近活动取运行中任务。其余任务仍是普通常驻通知。**组摘要刻意不提升**——Android 拒绝提升摘要。
- 样式用 `BigTextStyle`：它本身就是官方许可提升的四种样式之一，而编码任务没有有意义的百分比，不需要 `ProgressStyle`。

**「已完成」为什么不进流体云**：官方 UX 规范明确「实时更新必须表示正在积极进行的活动……如果活动发生在过去，请勿使用实时更新，请改用标准通知」。完成仍走既有标准通知（有声、可拉掉）。（若要完成态也出现在流体云，只能走 OPPO 卡片路线，即上面的路线 B。）

**资格自检**：`LiveUpdate.describeEligibility` 会把 10 项条件与「系统是否关闭了本应用的推广通知」（反射查 `NotificationManager.canPostPromotedNotifications`）写进诊断日志——真机上这是判断"流体云为什么没出现"的唯一线索。

---

## 网页文件上传

此前**完全没有 `WebChromeClient`**，所以网页里点上传是死的。现在：

- `onShowFileChooser` → 弹「选择上传方式」（相册 / 文件，文案与鸿蒙版逐字一致）→ 系统 Photo Picker（上限 5，对齐鸿蒙 `maxSelectNumber`）或 SAF 文档选择器；按 `fileChooserParams.mode` 决定单选/多选。
- `pendingFileCallback` 保证回调**恰好一次**：取消时回 `null`，`onDestroy` 兜底取消（否则会泄漏 WebView 并让该 input 永久卡住）。
- 三条路径**都不需要存储权限**（Photo Picker 只授权所选媒体、SAF 逐次授权），因此没有新增权限、没有改 Manifest。
- 类型过滤与鸿蒙**故意不一致**：鸿蒙把 ~400 条后缀喂给 `DocumentViewPicker`（该 API 必须给后缀），而 Android SAF 支持通配符且多数后缀在 `MimeTypeMap` 里无映射，照搬会把网页本来接受的文件藏起来。这里以网页 `accept` 为准，为空时放行通配（与桌面浏览器一致）。
- **唯一缺口**：`MODE_SAVE`（网页请求保存文件）返回 `false` 保持原行为，日志里会记一条 warn。

---

## 已敲定的决策（D1–D14，勿自行更改）

全部来自用户。右列是当时排除的选项，改回任何一项前先读理由（完整往返记录在本地归档 `../安卓薄壳迁移文档.md` §11）。

| # | 决策项 | 结论 | 被否决的选项 |
| --- | --- | --- | --- |
| D1/D2 | 技术路线 / 数据来源 | 薄壳（WebView 套壳）+ hook WebSocket 读协议帧 | 重资产（自实现协议）、**DOM 嗅探** |
| D3 | 壳的技术栈 | 原生 Kotlin（AndroidX） | Flutter + flutter_inappwebview（复用 CI 更省事，用户仍选原生） |
| D4 | 包名 / 应用名 | `com.zcode.remote` / 「ZCode 远程」 | 沿用 `com.zcode.control` |
| D5 | 子项目目录 | `android-shell/` | — |
| D6 | 前台服务类型 | `specialUse` | `connectedDevice`（语义偏外设）、`dataSync`（Android 15 有 6 小时上限） |
| D7 | sessions-index 订阅范围 | 主动订阅所有 workspace | 只被动嗅探当前 workspace、先被动后升级 |
| D8 | 常驻通知格式 | 标题=任务名；正文=`状态 · 最新进展` | 正文只放最新进展、状态放标题 |
| D9 | 常驻通知状态粒度 | 只区分「运行中 / 等待确认」 | 三态（含「刚刚完成」）、不体现状态 |
| D10 | 完成通知表现 | 有提示音 / 震动，可拉掉 | 静默（同 zemote）、设置里给开关 |
| D11 | 点击通知 | 拉起 App **并尝试定位到该任务**（失败优雅降级） | 只拉起不定位、分两阶段 |
| D12 | 可见性劫持 | 直接预防性实施，不做前置 spike | 先 spike 验证再决定 |
| D13 | 编译 | 完全依赖云端 CI，本机不装环境 | — |
| D14 | 签名 | **本项目专用** keystore（已修订，见「签名密钥」） | 沿用 zemote 那把（原定，因密码不可得且密钥生命周期不可控而否决） |

**为什么否决 DOM 嗅探**（D2 的依据，别再回退）：通知需要**逐任务**的标识/状态/预览，而列表可能虚拟化、后台不渲染时更读不到。鸿蒙版的状态栏脚本最初做「运行时取值」，反复迭代后收敛成**纯 DOM 标记存在性检查**——这正好证明该网页的 DOM 不适合承载精细语义。

**工程师补充决策**（可改，但需说明理由）：通知分三个渠道而非事后切重要性（渠道重要性创建后不可由 App 修改，见「任务通知」）；等待确认时**就地更新常驻通知 + 补发一条有声提醒**，而**不**把同一通知换到另一个渠道（渠道是通知的身份之一，换渠道不可靠）；常驻通知 ID 由 `sessionId` 稳定派生、`setGroup("running_tasks")` 自动折叠；通知权限在**首次进入控制页**时申请（等到有任务在跑才申请时，用户正在别的 App 里，弹窗体验差）。

## 决策落点（原迁移文档 §11.3 的四个待定问题）

| 问题 | 结论 | 理由 |
| --- | --- | --- |
| 扫码方式 | **ZXing（journeyapps embedded）** + 剪贴板粘贴 + 手动输入 | ZXing 不依赖 Google Play Services，国产 ROM 可用；三种入口互为兜底 |
| 设置页项 | 当前链接、换链接/重新加载、通知权限、清除通知、电池优化白名单、订阅所有工作区开关、诊断日志（查看/复制/分享）、版本 | 只保留能影响「通知能不能到」和「调试能不能做」的项；「仅 Wi-Fi 保活」「通知开关」价值低未做 |
| 语言 | 仅中文 | 与鸿蒙版一致 |
| minSdk | **26（Android 8.0）** | 通知渠道是 API 26+ 才有的概念，再低要为渠道写一套降级分支；26 覆盖已足够 |
| ABI 拆分 | 不做，单一通用 APK | 纯 Kotlin 无 native 库 |
| 应用内更新检测 | 第一版不做 | 壳的原生代码变更频率低，手动装 Release 即可 |

---

## 平台约束与已知边界

**安卓版本适配**（改通知 / 保活 / 系统栏前对照）：

| 版本 | 事项 |
| --- | --- |
| Android 13（API 33） | `POST_NOTIFICATIONS` 是运行时权限，必须动态申请 |
| Android 14（API 34） | 前台服务必须声明 `foregroundServiceType`；**`specialUse` 必须同时带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property**，漏了直接抛异常。实现里 `startForeground` 抛异常会被捕获并**降级为无保活**（通知仍可用），日志里能看到「startForeground 失败」 |
| Android 15（API 35） | `dataSync` 有 6 小时/24 小时上限（故选 `specialUse` 绕开）；**targetSdk 35 强制 edge-to-edge**，系统栏颜色属性失效，内容必须自己消费 window inset（见「界面规范」） |
| 通用 | 渠道重要性创建后不可由 App 修改；`setRendererPriorityPolicy` 要在 WebView 创建后尽早调用 |

**已被接受的风险与边界**（不是待办，是设计上认下的）：

1. **网页随时可能改。** hook 依赖网页内部的 WS 用法与 `sessions-index` 载荷结构，`zcode.z.ai` 重新部署可能让 hook 失效，无预警、无版本可跟（用户已评估并接受：该网页服务大量用户、稳定性高）。脆弱性来源由此从「协议版本变更」变成「别人托管的网页内部实现变更」。
2. **可见性劫持是对抗性手段**，且改不了浏览器内部的可见性判定——它只让页面自己不因为「我不可见了」而暂停；后台定时器不是"被节流"而是**整段停摆**，前台服务 + renderer priority 也救不了（实测见「实现要点」6）。**能救的只有 ROM 放行**（ColorOS：电池 → 应用耗电管理 → 允许完全后台行为），放行后心跳与订阅才能真正在后台存活。
3. **点击定位任务（D11）是本项目最脆弱的功能**：按标题文本匹配 DOM，页面改版即失效，已按要求优雅降级为「仅打开应用」。
4. **后台连接有天花板**：用户手动划掉 App 或 ROM 激进清理时，连接与通知必然中断。**门槛比原先估计的低得多**——本轮实测：只是按一下 HOME 退后台（不用划掉），ColorOS 就在 285ms 后以 `o-stop(40)` 杀进程（前台服务在跑、Doze 已白名单照样杀）；**放行「允许完全后台行为」之后同一台机器上后台 14 分钟连续可用**。根治要么走原生 relay 客户端（注意：进程拿不到执行权时它同样无效，zemote 就是反例），要么依赖服务端推送——后者需要桌面端发起，**超出本项目范围**。
5. **强制重连是「借页面之手」**：注入层只关闭 socket，重连依赖页面自身的重连逻辑；那段逻辑一变，这条恢复手段就失效。
6. **主动订阅（D7）的副作用**：为其它工作区开的 bridge 会带来额外开销——桌面端侧的常驻会话、以及页面把我们订阅到的帧当成未知 bridge 缓存带来的内存增长（见「实现要点」7）。设置里的「订阅所有工作区」开关可随时退回纯被动。

---

## 本机开发

本机（这台 Windows）**不装 JDK / Android SDK**，**不要尝试本地构建**——Kotlin 编译只能由 CI 完成，改完直接推 `pre`，在 CI 的 annotation 里读编译错误（workflow 会把 Gradle 的关键错误行提升为 `::error::`）。可本地运行的只有这些：

```bash
cd android-shell
node --test                              # 50 项：线格式、分片重组、通道客户端、会话索引、注入层、页面 RPC 取证
python tools/check_kotlin_structure.py   # 括号配平 / 包名与目录一致 / 合并残留（约 1 秒）
python tools/watch_ci.py [--watch]       # 读 CI 状态与失败原因（无需 gh / 无需 token）
python tools/doc_snapshot.py --probe     # 官方文档快照（docs/）的状态；详见脚本头部 docstring
```

提交时**绝不用 `git add -A`**：仓库里有 `docs/`、`ColorOS_docs/`、`scratch/` 等不入库的大目录，一律 `git add <明确路径>`。

工具链实况：

| 东西 | 位置 / 情况 |
| --- | --- |
| JDK | 本机唯一可用的是 DevEco Studio 自带的 JBR（`E:\DevEco Studio\jbr`）；它的 `keytool` 可以生成安卓 keystore（当前签名材料就是这么来的） |
| Android SDK / Gradle | **没有**；Gradle 由 CI 跑，本地 `gradlew` 不具备构建条件 |
| `hdc` | `E:\DevEco Studio\sdk\default\openharmony\toolchains`（鸿蒙侧） |
| `gh` / `jq` | **没有**。所以读 CI 用 `tools/watch_ci.py`，发布流程全部在 CI 里用 runner 自带的 `gh` |

### 真机调试（adb）

安卓真机是**一加 PLC110（Android 16 / API 36 / WebView 153）**。本机不装 Android SDK，但**有可用的 adb**，不必另装：

| 位置 | 版本 | 说明 |
| --- | --- | --- |
| `C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe` | Platform-Tools **36.0.0** | 推荐；Android 16 需要较新的 adb |
| `E:\leidian\LDPlayer9\adb.exe` | 34.0.4 | 雷电模拟器自带，备用 |

手机走**无线调试**（开发者选项 → 无线调试），局域网 IP 见手机页面（近期为 `192.168.0.185`，会变）。**Android 11+ 首次必须配对**：只做 `adb connect` 会被拒，现象是端口 TCP 通（PowerShell `Test-NetConnection` 返回 True）而 `adb connect` 报 `failed to connect`。

```bash
ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"

# 1) 手机：无线调试 → 「使用配对码配对设备」→ 得到 <配对 IP:端口> 与 6 位配对码
MSYS_NO_PATHCONV=1 "$ADB" pair <配对 IP:端口>        # 交互输入 6 位配对码
# 2) 手机：无线调试页面上的 <IP:端口>（与配对端口不是同一个）
MSYS_NO_PATHCONV=1 "$ADB" connect <IP:端口>
MSYS_NO_PATHCONV=1 "$ADB" devices -l                 # 确认出现设备
```

配对码是**一次性凭据**：不要入库，不要写进日志、文档或提交信息。

#### 约定：真机验证与读日志的闭环（照这个顺序做）

本机没有 Android SDK，所以 **adb 是唯一能连安卓真机的通道**（会话里的 `dsh-hdc-bridge` MCP 只覆盖鸿蒙设备）。每轮真机验证都走同一条闭环，别临时发明步骤：

1. **取包**：CI 产出的滚动预发布固定链接（见「取 APK」），或 `adb install -r` 一个已下载的 `zcode-remote.apk`。
2. **装包**：`adb install -r`（versionCode 取 CI 运行号、单调递增，永远能盖过上一版，无需卸载）。
3. **复现**：操作手机。需要看页面内部时用 WebView 远程调试（debug 包或设置里的「WebView 调试」开关 → 桌面 Chrome `chrome://inspect`）。
4. **取日志**（**约定：一律从这条固定路径读**，不要靠截图猜）：

   ```bash
   "$ADB" shell cat /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log
   "$ADB" shell cat /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log.1  # 轮转后的上一份
   "$ADB" logcat -d -s ZCodeRemote   # 与日志文件同源：Diagnostics 会镜像到 logcat，适合边操作边看
   ```

   - 日志文件 512 KB 轮转（`.1` 是上一份）；`Diagnostics` 另有 400 行内存环形缓冲，随「导出日志」一起给出。
   - 手机上也能不接电脑取日志：**设置 → 诊断 → 分享/导出日志文件**（FileProvider，含历史会话与崩溃堆栈）。
   - 清空：设置页的「清空日志」，或 `adb shell rm` 掉上面两个文件（应用下次写入会重建）。
5. **判读**：按「项目现状」与「已知问题 A/B」里写的口径读数字（例如后台存活的结论行、慢加载的四步判读顺序、悬浮弹窗的 `dumpsys` 组合）。
6. **落回文档**：结论写进「项目现状」表格与文末「交接文本区」（就地改写，不要追加）。

补充命令（排查系统层问题时用）：

```bash
"$ADB" shell dumpsys window windows | grep -i zcode    # 窗口类型（判断「悬浮显示」这类系统弹窗）
"$ADB" shell appops get com.zcode.remote               # 权限 / AppOps 实况
"$ADB" shell dumpsys notification --noredact | grep -i -A5 zcode   # 是否有 promoted（流体云）通知在展示
"$ADB" shell dumpsys activity activities | grep -i zcode           # 是否被系统置于浮窗/分屏
```

两个本机专属的坑：① Git Bash 会把设备绝对路径改写成 Windows 路径（`/sdcard/...` 变成 `C:/Program Files/Git/sdcard/...`），凡带设备路径的命令都要加 **`MSYS_NO_PATHCONV=1`**；② 本机有两套 adb（雷电 34 / UotanToolbox 36），出现 `cannot connect to daemon at tcp:5037` 时先 `kill-server`，再用上面推荐的那个 adb 重新 `start-server`。

**鸿蒙测试机不要弄混**（与本子项目无关）：`192.168.0.82:12345`（HBN-AL80 / API 24）、`192.168.0.79:41247`，另有若干串口；`dsh-hdc-bridge` MCP（`hdc_list_targets` / `hdc_shell` / `hdc_screenshot`）能直接操作它们，但**连不到安卓真机**。

## 目录

```
android-shell/
├── app/src/main/
│   ├── assets/
│   │   ├── zcode-protocol.js      # 线协议：值编解码、rpc-frame、ChannelClient、RemoteClient（含页面 RPC 取证）
│   │   └── inject.js              # document-start 注入：WS hook、可见性劫持、心跳、任务定位、滚动条方案 A
│   ├── java/com/zcode/remote/
│   │   ├── MainActivity.kt        # WebView 壳、注入安装、扫码/粘贴/手输、通知跳转
│   │   ├── SettingsActivity.kt    # 设置 + 诊断与存活读数
│   │   ├── ShellRuntime.kt        # 任务存储、通知驱动、存活取证、保活服务生命周期
│   │   ├── KeepAliveService.kt    # specialUse 前台服务
│   │   ├── WebAppBridge.kt        # window.ZCodeShell（postMessage / config）
│   │   ├── ZcodeRemoteApp.kt      # 日志与崩溃捕获、前后台追踪
│   │   ├── core/                  # NotifyState/TaskStore（纯逻辑，可单测）、Diagnostics、ShellLog、Prefs、RemoteUrl
│   │   └── notify/Notifier.kt     # 三渠道 + 常驻/完成/待确认通知
│   └── res/                       # 资源；应用图标直接复用鸿蒙工程的图层
└── tools/                         # Node 测试（协议层、注入层、假桌面）+ Kotlin 结构检查 / CI 读取 / 文档快照脚本
```

---
## 交接文本区（新会话从这里开始）

> **约定：本区每次会话收尾时**就地改写**，不是追加。**
> 改写时删掉已完成的条目、把现状改成新的、只保留仍然成立的信息——追加会让下一个会话先读到已经过期的结论
> （本项目此前就是因为这个才删掉了独立的交接文档，只留这一处）。
> **细节不要往这里堆**：架构与实现 → 「实现要点」；待验证项与判读口径 → 「项目现状 / 已知问题 A·B·C·D」；
> 环境、adb 与取日志的约定 → 「本机开发」；发版 → 「发布流程」。本区只留：状态、任务清单、下一步、拦路石。

> **本轮有一份面向用户的收尾报告：[`晨间报告-2026-09-12.md`](晨间报告-2026-09-12.md)**（做了什么 / 验到什么 / 还差什么）。

### 一句话状态

`pre` @ `5590810`（本轮从 `a3a57f5` 起共 19 个提交，**每次推送都触发 CI**；其中三次是失败轮
——资源链接、测试里的 `const`、动态配色的 lambda 元数——都按 CI 给出的错误修好后才转绿，失败与修法见阶段 1/5）。

**成果**：11 条改动/取证要求全部实现；CI 全绿；滚动预发布里是含全部修复的最新 APK
（最后一个动 `app/` 的提交是 `e52a7c4`，其后全是文档提交），链接恒定：
`…/releases/download/android-pre/zcode-remote.apk`。真机最后一版装的是 **`1.0.0-pre.43`**（02:25）。

**真机验收**：能靠日志与视图树证的部分全部证完（见「阶段验收记录」阶段 2–4）。
**两个阻塞**（都只有用户能解，见「拦路石」）：① 设备在会话开始前就是**安全锁屏**；
② 02:27 息屏超时到点，ColorOS 断掉 Wi-Fi，**设备整个从网络上消失**，之后只能靠人工恢复。

**上一轮（第八轮）的状态**（保留供对照）：CI 全绿（Kotlin 42 + JS 50），最新预发布 `1.0.0-pre.39`，
真机验证八轮（一加 PLC110 / ColorOS 16 / API 36 / WebView 153–154），后台存活测满 29 分 26 秒。

**本轮（第九轮）是一次「过夜长任务」**：用户一次性给出 11 条改动/取证要求 + 14 条流程要求（见下面的「任务清单」），
要求全程走 GitHub `pre` 分支 → CI 编译 → 从 release 下载 → 真机调试的闭环，**禁止本地编译**（本机无 JDK/SDK），
每一步阶段验收成功都要回到本区更新状态。

### 本轮任务清单（用户原话整理 + 验收口径 + 状态）

> 这份清单是**上下文被压缩后的召回锚点**：用户的要求逐条列在这里，不要凭印象重述。
> 状态取值：`未开始` / `进行中` / `已实现待 CI` / `CI 绿` / `真机已验证`。

#### A. 两条故障的定位（用户要求「查找定位为什么」）

| # | 用户原话（摘） | 验收口径 | 状态 |
| --- | --- | --- | --- |
| A1 | 「手机端点击进入会话时，会话标题不能获取，回退到『新建对话』的错误兜底状态……工作区有网页代码嗅探下来的本地文件可供分析」 | 给出机制级结论 + 证据（快照偏移 / 真机日志行） | **已定位**，见「已知问题 D·1」 |
| A2 | 「长时间后台时，点进某一个任务会出现长时间无法加载内容的情况，此时重新刷新网页或重启 app 都可以恢复」 | 给出机制级结论 + 壳侧可动的那一部分并落地 | **已定位 + 已落修复 + 真机首轮验证**（reopen 22 → 0），长窗口复验见「阶段验收记录」 |

#### B. 界面改动

| # | 用户原话（摘） | 验收口径 | 状态 |
| --- | --- | --- | --- |
| B1 | 「按照 miuix 重构设置页」 | 设置页按 MiuiX 设计语言重排（规格取自本机 `docs/miuix` 源码） | **真机已验证**（截图：16dp 白卡 / #F7F7F7 底 / 灰蓝小节标题 / 10×16 箭头 / 开关是 MiuiX 蓝 #3482FF） |
| B2 | 「取消现在的顶部标题和右侧下拉进入设置/刷新网页/重新扫码的菜单行」 | 主界面不再有工具栏与溢出菜单；那三个入口改由长按菜单 + 原生面板承担 | **真机已验证**（视图树无 Toolbar） |
| B3 | 「直接就是仿照鸿蒙项目的监听页面刷新状态然后动态给状态栏赋值背景色」 | 注入层回推「状态名 + 主题名」，原生查固定色表换色（运行时零取色） | **真机已验证**（`boot/… → #161616`、`main-header/dark → #202020`） |
| B4 | 「注意状态栏的底部边距作为网页的顶部边距，即注意避让不要冲到屏幕顶端」 | 网页顶部 padding = 状态栏（含挖孔）inset，那条带子由状态栏底色填充 | **真机已验证**（WebView 顶边 = 141px = 状态栏高） |
| B5 | 「设置底部的手势横条沉浸，网页直接底部对齐屏幕底部边距，导航条原位置悬浮在底部即可」 | 底部 inset 不消费（键盘除外）：网页贴到屏幕底边，手势条悬浮其上 | **真机已验证**（截图：网页贴底、手势条悬浮其上）；键盘上抬见阶段 7 末尾的说明 |
| B6 | 「新增桌面端 Shortcut 即应用长按出现的菜单，将重新扫码和打开设置作为按钮」 | `res/xml/shortcuts.xml` 两条静态快捷方式，走 MainActivity | **真机已验证**（`cmd shortcut get-shortcuts` 两条都在、Intent 对） |
| B7 | 「图标都去找 Android 的原生图标库不要自己瞎画」 | 用 Google 官方 Material Icons 字形（`ic_shortcut_scan` / `ic_shortcut_settings`），路径逐字照抄 | **真机已验证**（长按菜单截图：二维码与齿轮两个字形都正确渲染） |

#### C. 通知 / 流体云

| # | 用户原话（摘） | 验收口径 | 状态 |
| --- | --- | --- | --- |
| C1 | 「把流体云的状态如运行中的前提字样移动到标题行（显示工作区对话任务名称）的开头」 | 卡片标题 = `运行中 · 任务名`；正文只留实时进展 | **真机已验证**（`dumpsys notification`） |
| C2 | 「任务完成后，流体云立即显式从屏幕顶端的横条弹出展开为方框，强提示任务已完成（即把运行中状态改为已完成三个字标记然后其他按正常通知内容来）」 | 完成瞬间额外发一张 promoted（ongoing）卡片，标题 `已完成 · 任务名`，正文照旧；15 秒后自动收 | **机制已验证**（同一 promoted 路径已在 C1 出卡验证过；`已完成 · …` 这一支靠单测钉住），**尚未被一次真实完成触发** |
| C3 | 「希望标题限制在一行内，从状态提示的分割点往后的标题文字显示如果空间不够就无限单行滚动显示，留出尽可能多的空间给下面的实时对话」 | 标题强制单行（折叠所有空白/换行）；滚动由系统卡片渲染器负责 | **系统做不到**：截图证明流体云卡片把标题**折成两行**（既不单行也不 marquee）。剩下两条路见「拦路石」，**待用户拍板** |

#### D. 流程 / 环境要求（用户原话）

| # | 要求 | 状态 |
| --- | --- | --- |
| D1 | 「全程走 github 的 pre 分支推送触发的 ci 流程编译，从 release 下载安装并调试形成闭环，切勿本地编译」 | **全程走通**：pre.42 → pre.50 每一版都是从 `android-pre` 直链下载安装到真机 |
| D2 | 「adb bridge 可用，192.168.0.185:45903 链接网络 adb，屏幕常亮，禁止息屏测试因为会困在锁屏状态」 | 前半段受阻（接手即锁屏，02:27 息屏掉出 Wi-Fi），**07:49 用户插 USB 后全程可用**；无线调试端口每次变，用 mDNS 现取 |
| D3 | 「可以退出应用回到后台测试」 | **用过**：设备掉线前就是「息屏 + 后台 + 心跳泵在跑」的状态，A2 的长窗口复验正是这么做的 |
| D4 | 「每推进一个阶段的任务都要跑通 ci 构建和测试验证，正好做提交的快照管理」 | **阶段 1–5 都完成**，详见「阶段验收记录」 |
| D5 | 「把所有发给你的话详细整理落盘任务清单到 readme 文档的末尾交接区，便于召回；每一个阶段验收成功，也立即在交接区更新状态」 | **本节即该落盘**，每阶段回来更新 |
| D6 | 「我早醒希望能看到一个符合我上述需求的应用（七点半是最晚交付时间），和一份最终早晨任务报告」 | 进行中 |

### 本轮的实现落点（改了哪些文件）

| 文件 | 改了什么 |
| --- | --- |
| `assets/inject.js` | 新增第 6 节「页面状态 → 原生状态栏表面」：DOM 存在性检查 + 同源媒体查询（`max-width:767px` / `prefers-color-scheme`）判态，`MutationObserver` + matchMedia change 事件驱动，去重后回推 `pagestate{状态名,主题名}`；`__zcodeShellReportPageState()` 供原生回前台时强制重读；心跳探针落到 socket 空档时降为 debug（原先是 warn） |
| `assets/zcode-protocol.js` | `MAX_REOPENS_PER_BRIDGE` 2 → **0**：一个工作区在一条 relay 连接上首次 fault 就不再重开（字段证据见「已知问题 D·2」） |
| `core/PageBarColor.kt`（新） | 三态 × 深浅的固定色表 + `stateOf/themeOf` 名字解析 + `describe` 日志行 + `appearanceLightStatusBars` |
| `core/WindowInsets.kt` | `padForSystemBarsAndIme` → `padForStatusBarAndIme`：顶部照旧（状态栏 + 挖孔），**底部只留键盘**，手势条不再消费 |
| `MainActivity.kt` | 删掉工具栏与溢出菜单；接 `pagestate` 回推 → 换状态栏底色 + 图标明暗；`ACTION_SCAN` 快捷方式；`onPageStarted` 回落 boot 面 |
| `activity_main.xml` | 去掉 `MaterialToolbar`；根布局底色 = 状态栏那条带子的颜色（运行时赋值）；失败面板补「设置」按钮 |
| `SettingsActivity.kt` + `activity_settings.xml` | 按 MiuiX 重排（无 MaterialToolbar，改用自带小应用栏 + 卡片列表） |
| `values/miuix_colors.xml` + `values-night/` + `values/miuix_styles.xml` + `drawable/miuix_card_background.xml` | MiuiX 取色表（逐项照抄 `Colors.kt`）、度量与行样式 |
| `res/xml/shortcuts.xml` + 两个 `ic_shortcut_*.xml` + `ic_chevron_right.xml` | 长按菜单与官方图标字形 |
| `core/NotifyState.kt` / `core/TaskStore.kt` / `notify/Notifier.kt` | 标题 = `状态 · 任务名`（D15，状态字样进标题行）、正文只留进展、标题强制单行、完成后额外发一张 promoted 的 `已完成` 卡片（15s） |
| 单测 | JS 50 → 54（页面状态四则）；Kotlin 42 → 约 55（标题/正文新语义、完成卡片 id 不重叠、状态栏色表 6 则） |

### 阶段验收记录（每阶段验收成功后就地更新）

#### 阶段 1：代码落盘 + CI 全绿（2026-09-12 01:5x）

`pre` @ `bc55168`，CI run 34630474054 **全绿**：`js`（静态检查）+ `build`（`testReleaseUnitTest` + `assembleRelease`）+ `prerelease`。
APK 已覆写到滚动预发布，真机装的是 **`1.0.0-pre.42`**（versionCode 42）。

途中两次 CI 失败都是真问题，已修并按原样记下：
1. **资源链接失败**（`failed linking references`）：`<item name="app:tint">` 这类"带前缀的库属性"
   不写在 style 里 —— 改到使用处的 `app:tint`，未用的样式整条删掉。
   同一轮还把工作流的错误抓取修了：aapt2 的行是 `<文件>:<行>: error: …`，原来只锚行首 `^(e: |error: )` 会整段漏掉。
2. **两处 Kotlin 错误**：`0xFFF8F8F8.toInt()` 不是编译期常量（测试里写了 `const val`）；
   `NotifyState.formatBody` 换签名后 `PromotionPolicyTest` 还留旧调用。
   → 为此新增 `tools/check_resources.py`（校验 `@type/name` 是否都有定义）并接进 40 秒的 `js` job。

#### 阶段 2：真机验收（2026-09-12 02:00–02:05，pre.42）

**⚠️ 前置障碍：设备在会话开始前就已经息屏，且处于**安全锁屏**（`secure=true, showing=true`）。
我无法解锁（也不应尝试猜密码），所以**纯视觉项**（截图观感类）本轮无法完成 —— 见「拦路石」。
好消息是：**应用在锁屏后面照常运行**（进程、WebView、注入层、订阅都活着），
于是本轮用日志与视图树把**能证的部分全证了**。

| 项 | 证据 | 结论 |
| --- | --- | --- |
| B2 去应用栏 | `dumpsys activity top` 视图树：`#root` 下只有 `#webview` 与两个 `G`（GONE）的原生面板，没有 Toolbar | **已验证** |
| B4 顶部避让状态栏 | 同上：`WebView 0,141-1272,2800` —— 顶边 141px = 40.3dp ≈ 状态栏高度，网页从状态栏下方开始 | **已验证** |
| B5 底部沉浸 | 同上：WebView 底边 = 2800px = 屏幕底边，底部 inset 没有被消费，手势条悬浮其上 | **已验证** |
| B3 状态栏动态取色 | 日志三行：`状态栏底色: boot/system → #161616`（onCreate，未知主题按系统）、`boot/dark → #161616`（注入层首次回推）、`main-header/dark → #202020`（控制页挂载 + 手机窄屏 → 用网页自己的标题栏色）。去重生效，只三行 | **已验证（逻辑）**，接缝观感待截图 |
| B6/B7 长按菜单 | `cmd shortcut get-shortcuts --user 0 com.zcode.remote`：`shortcut_scan`（短标签 重新扫码 / 长标签 重新扫描桌面端的二维码 / `iconRes=drawable/ic_shortcut_scan` / `act=com.zcode.remote.action.SCAN`）、`shortcut_settings`（设置 / 打开「ZCode 远程」设置 / `drawable/ic_shortcut_settings` / `action.OPEN_SETTINGS`） | **已验证**（图标外观待截图） |
| B1 MiuiX 设置页 | `am start -a com.zcode.remote.action.OPEN_SETTINGS` → SettingsActivity 建成并拿到窗口，logcat 无 FATAL/InflateException；视图树度量逐项对规格：应用栏 182px=**52dp**、返回钮 168px=**48dp**、卡片左边距 42px=**12dp**、行内边距 56px=**16dp**、分隔线 3px=**0.75dp** 且左右各内缩 16dp、箭头 35x56px=**10x16dp**、MaterialSwitch 52x48dp、九个行 id 齐全、内容高 3522px（可滚） | **结构已验证**，观感待截图 |
| C1 状态字样进标题行 | `dumpsys notification --noredact`：`android.title=运行中 · <任务名>`、`android.text=<进展>`（正文已无状态前缀）、`android.shortCriticalText=运行中`、`flags=ONGOING_EVENT|ONLY_ALERT_ONCE|**PROMOTED_ONGOING**`；常驻服务通知正文 `• 运行中 · <任务名> — <进展>` | **已验证**；顺带说明 **P3 的一半**：系统确实接受了提升（是否真出流体云卡片仍需看屏幕） |
| A2 fault 收敛 | 新日志 `reopening` **0 次**、`rpc-transport-fault` **1 次**（首 fault 即 `本次连接放弃重开（已 fault 1 次）`）；对照 pre.39 的旧日志同量级窗口 **22 次 reopen** | **已验证（首轮）**，长窗口复验见下 |

#### 阶段 3：长后台窗口复验 A2（2026-09-12 02:05–02:20）

设备正好处在 A2 的前置条件里（息屏 + 应用在后台 + 后台心跳泵在跑），不需要任何交互即可复验。

10 分钟窗口的结果：`reopening` **0**、`rpc-transport-fault` **1**（首 fault 即收手）、`冷却` 0、
`relay socket open` 1 / `closed` 0（窗口内没有发生 relay 重建，所以也没有"下一条连接重试"的机会）。
**对照**：旧版（pre.39）在同一类窗口里 `reopening` **22 次**。

**读法**：这不是"fault 变少了"，而是"**同一次 fault 不再引出重开链**"。
旧版在 02:01 那次 fault 之后会在 2 秒后 reopen、再 fault、再 reopen（一轮 8 条 RPC），
新版在 02:01 就停了，此后 10 分钟 + 期间 0 次 fault。弱点是窗口内没有 relay 重建，
所以"下一条连接给一次机会"这条分支未被本轮观察到；那条分支由单测
（`the first fault on a workspace is not reopened`、`a workspace the desktop refuses on every connection goes on cooldown`）覆盖。

#### 阶段 4：注入层健壮性修复 + pre.43 真机复验（2026-09-12 02:25–02:27）

自查发现 `installPageStateReporter()` 的一个静默致命点：`document-start` 若在 `<html>` 建成前运行，
它会直接 return 且不重试 —— 那一刻命中，状态栏这一整份文档就再也不会跟随页面。本机 WebView 154 没命中，
所以真机是好的，但这属于赌运气。已改成：`<html>` 未就绪 → 挂 `DOMContentLoaded` 重试；无
`MutationObserver` → 降级但仍跟踪断点与主题（旋转/深色切换照常）；正常 → 照旧。JS 单测 54 → 55。

顺带复审出一个会在真机上惹人的问题并修掉：**完成卡片是 promoted ⇒ 必须 ongoing ⇒ 用户划不掉**。
若进程在 15 秒窗口内被杀（ColorOS 上完全可能），`setTimeoutAfter` 来不及生效，那张「已完成」就会赖在通知栏，
只能强停应用才清得掉。已在 `ensureChannels()` 里加启动清扫：用 `getActiveNotifications()` 找出落在
完成卡片 id 区间、而本进程并未持有的通知并取消 —— 不需要持久化，因为 id 区间是可推导的。

真机（pre.43，02:25 安装）复验：

```
02:25:26.745  进程启动 v1.0.0-pre.43
02:25:26.956  状态栏底色: boot/system → #161616
02:25:27.420  状态栏底色: boot/dark → #161616
02:25:29.946  状态栏底色: main-header/dark → #202020
```
以及重载一整轮（顺带验证了 `onPageStarted` 回落 boot 面的那条改动）：
```
02:26:29.094  网页开始加载
02:26:29.095  状态栏底色: boot/system → #161616     ← 新文档先落 boot，不留上一份文档的标题栏色
02:26:33.783  状态栏底色: main-header/dark → #202020
```
另外注意 02:25 那次重启后的 burst：`default` **没有** fault（`bridge ready` + `主动订阅完成：用时 10684ms`），
说明 fault 是场景性的（页面已在别处持有该工作区时才会发生），不是每次握手必然发生。

#### 阶段 5：复审驱动的三处收口（2026-09-12 03:00–04:00，pre.44 → pre.47）

设备掉线后没有真机可测，于是把时间用在**逐处复审**上。查出来的都不是 CI 能发现的东西：

1. **注入层的一个静默致命点**（已修，见阶段 4 开头）：`<html>` 未就绪时观察器一次装不上就永远没有。
2. **完成卡片可能赖在通知栏**：promoted ⇒ 必须 ongoing ⇒ 用户划不掉。进程若在 15 秒窗口内被杀，
   `setTimeoutAfter` 来不及生效，那张「已完成」就只能靠强停应用清掉。已在 `ensureChannels()` 里
   用 `getActiveNotifications()` 清扫落在完成卡片 id 区间、而本进程并未持有的通知
   （不需要持久化——区间可推导，且与运行中卡片不重叠已有单测）。
3. **设置页同一页两套蓝**：页面是 MiuiX 的 `#3482FF`，但 `MaterialSwitch` 取的是 M3 **角色**
   （`colorPrimary` / `colorOnPrimary` / `colorSurfaceContainerHighest` / `colorOutline`），
   Android 12+ 的动态配色会把这些角色换成壁纸派生色。**关键在优先级**：动态配色是以
   ThemeOverlay 套在主题**之上**的，在 `themes.xml` 里显式盖没有用。所以两处一起改：
   新增 `Theme.ZcodeRemote.Miuix`（设置页专用，四个角色指向 MiuiX 值）+ `ZcodeRemoteApp` 里用
   `DynamicColorsOptions.setPrecondition` 把 SettingsActivity 排除出动态配色。主界面继续用动态配色。

**这一轮 CI 又教了两件事**（都已记进代码注释）：
- aapt2 的「`<item name="app:tint">`」——**style 的 item 名不写库属性的前缀**，改到使用处的 `app:tint`；
- Material 的 `DynamicColors.Precondition` 是**两个参数**（`Activity` + `@ColorScheme int`），
  一元 lambda 会被 CI 判 `Function1 vs Function2`。

#### 阶段 6：视觉与交互验收完成（2026-09-12 07:49– ，pre.47，USB）

用户插上 USB 后设备解锁，视觉项全部补完。装了滚动预发布里的 **`1.0.0-pre.47`**（含全部修复）。

**① 主界面（截图）**：状态栏那条带子是纯白，紧接其下的**网页自己的标题栏也是纯白 —— 没有接缝**；
没有应用栏，网页标题「ZCode 远程控制」就是第一行内容；网页一直贴到屏幕最底边（手势条悬浮其上）。
日志同时再次印证换色（这次是**浅色**，此前只验过深色）：`boot/system → #F8F8F8`、`boot/light → #F8F8F8`、
`main-header/light → #FFFFFF`。

**② 流体云卡片（截图）**：**真的出卡了**——桌面上方一张展开的圆角卡片，标题 `运行中 · Android 会话标题与状态栏重构调试`、
正文只有进展（`最新版 **pre.47** 已装上（含全部修复）。现在清日志、启动、截图。`）、右侧应用图标；
**状态栏顶部的芯片也在**（黑底药丸里的 `Z` + 「运行中」）。`dumpsys notification` 对应
`id=127606 flags=…PROMOTED_ONGOING`、`shortCriticalText=运行中`。→ C1 与 P3 双双结案。

**③ MiuiX 设置页（截图）**：观感与规格一致 —— #F7F7F7 页面底、16dp 圆角白卡、左右 12dp、
小节标题（连接/通知/后台保活/高级）用 MiuiX 的 `onBackgroundVariant` 灰蓝、行标题 17sp Medium、
摘要 14sp 灰、行尾 10×16dp 箭头、分隔线左右各内缩 16dp、内容可滚。
**开关是 MiuiX 蓝 `#3482FF`** ——「把 SettingsActivity 排除出动态配色」那一步确实生效（否则会是壁纸派生色）。

**④ 扫码快捷方式（功能验证）**：`am start -a com.zcode.remote.action.SCAN -n …/.MainActivity`
→ `mCurrentFocus=com.zcode.remote/com.journeyapps.barcodescanner.CaptureActivity`，扫码器真的起来了。
（图标外观未单独截图：资源已注册、构建期已校验，风险近乎为零。）

**⑤ 真机抓到一个我自己引入的回归，已修**（见下一条提交）：点流体云卡片 → 通知定位失败
`未能在页面上定位任务: 运行中 · …`。根因是 D15 把状态前缀加进标题后，`EXTRA_TASK_TITLE` 里塞的
还是被装饰过的 `item.title`，而定位器拿它去页面里找 DOM 文本——页面从不渲染「运行中 · 」。
修法：新增 `RunningNotification.locateTitle`（= 页面上的原样文本）供定位用，显示仍用 `title`，
并补一则单测钉住这个不变式。**这类 bug 单测与 CI 都抓不到，只有真机端到端能抓到。**

**⑥ C3 的新证据（比之前更准）**：流体云卡片**并没有**把标题限制成一行，也没有滚动，
而是**折成两行**（截图里 `运行中 · Android 会话标题与` / `状态栏重构调试`）。
也就是说系统渲染器既不单行也不 marquee——用户要的「单行 + 超长滚动」在流体云卡片上做不到。
可选的两条路：**(a) 保持现状（两行折行）**；**(b) 我们把这串文字按显示宽度截断到一行 + 「…」**，
把省下的那一行让给正文（符合"留尽量多空间给实时对话"，代价是丢掉尾部文字）。**这条要用户拍板。**

#### 阶段 7：A2 长后台复验 + 视觉项全部补完（2026-09-12 07:57–08:25，pre.47 → pre.50）

**A2 长后台复验（07:57 退后台 → 08:04 回前台，6 分 59 秒）**：`后台存活检查：… 注入层心跳 41 次
（首次在退后台后 6s，原生泵发令 41 次）、收到 284 帧、配对确认 32 次 → 保活成立` —— 改过注入层与通知链路之后
**P0 没有回归**。同一窗口里 relay 断开重连了两次（`socket closed` 08:03:34 → `open (#5)` 08:03:36），
于是**第一次观测到了"下一条连接再给一次机会"这条分支**：

```
08:02:57  bridge degraded for E:\ScribePad: rpc-transport-fault
08:02:57  本次连接放弃重开 E:\ScribePad（已 fault 1 次）     ← 连接 #4：一次就收手
08:03:34  relay socket closed (code=1005 clean=true)         ← 页面自己的重连（已知问题 C）
08:03:36  relay socket open (#5)
08:04:31  bridge degraded for E:\ScribePad: rpc-transport-fault
08:04:31  本次连接放弃重开 E:\ScribePad（已 fault 1 次）     ← 连接 #5：同样一次就收手
```
`reopening` **0 次** / `本次连接放弃重开` 5 次 / `rpc-transport-fault` 6 次 —— 跨 5 条连接都没有重开链。
对照旧行为（同一工作区每 45–75 秒一轮、每轮 2 次重开）：**这就是用户报的"点进去半天不出内容"的燃料被掐掉了**。

**长按菜单（截图）**：长按图标弹出的菜单里，「重新扫码」与「设置」两条都在，
**图标也正确渲染**（Material 的二维码字形 + 齿轮字形，随启动器深浅自动适配）。

**三种状态的无接缝全部看到**：`boot`（页面自己的「已配对，正在加载工作区…」连接向导，状态栏带子 #F8F8F8）、
`main-header` 浅色（#FFFFFF）、`main-header` 深色（#202020）——三种状态下带子都与紧邻其下的网页顶面同色。

**A1 的现象在当前状态下不复现**：点进「已置顶」里那条运行中的任务，会话头显示的是**任务名**
（截断成「Android …」），**不是「新建对话」**，而且会话内容完整（连我刚跑的命令都在里面）。
这与 D1 的定位一致：回退只发生在"任务列表还没就绪"的那个窗口（例如刚加载完/长后台刚回来）。

**留下一个待观察项（新加了追踪手段）**：08:11 有一次「网页显示运行中、壳侧没有运行中卡片」，
08:20 重启进程后又有了（`id=127606 flags=…PROMOTED_ONGOING`）。为了能追它，加了按工作区去重的
`工作区相位 <key>：N 个任务 · phase×count / …` 一行；实测 `E:\Zcode_harmony：30 个任务 ·
running×1 / completedSuccess×23 / error×2 / completedInterrupted×4` —— **说明 `RUNNING_PHASES`
的分类是对的**（running 确实在我们认的集合里），所以那次缺失是瞬时的、不是分类错误。
下次若再遇到，看这一行 + `订阅状态` 就能判断是"帧没到"还是"到了但没发通知"。

**键盘上抬没有重测**：这一项在上一轮已真机验证过并结案；本轮的改动只把**键盘弹起时**的底部内边距
从 `max(系统栏, 键盘)` 变成 `键盘`——键盘弹起时 `ime.bottom ≥ bars.bottom` 恒成立，
所以那条路径的数值与旧行为完全一致（变的只是键盘收起时的底部）。

07:57 起把应用退到后台（`应用进入后台，保活服务继续运行` + `后台心跳泵已启动`），
按用户原本的场景复验：后台 ≥10 分钟 → 回前台 → 看 `reopening` 是否始终为 0、fault 是否只发生一次。

**卡在安全锁屏上**，见「拦路石」第一条。解锁后要补的清单：

1. 状态栏与网页顶面**有没有接缝**（三种状态各截一张：开屏 boot / 控制页窄屏 header / 错误页）；
2. MiuiX 设置页观感（浅色 + 深色各一张），并核对 16dp 圆角与 12dp 卡片边距的视觉密度；
3. 长按图标菜单里两个快捷方式的**图标是否正常显示**（框架图标能否被启动器着色的验证点）；
4. 键盘上抬后页面是否仍正常（底部 inset 已改为"只留键盘"）；
5. 页面底部输入区与手势条的重叠程度是否可接受（必要时加回一点底部内边距）；
6. 「已完成」卡片：等一次真实任务完成，看是否如预期弹出并在 15 秒后收起。

设备当前就处在 A2 的前置条件里（息屏 + 应用在后台 + 后台心跳泵在跑），所以不需要任何交互就能复验：
观测 `reopening` 是否始终保持 0，以及 fault 是否按「一条连接一次、跨连接累计 3 次进冷却」收敛。


### 下一步（按优先级）

> 阶段 1–5 都已完成（CI 全绿 + 第一批真机验收）；下面从"恢复设备之后"开始。

1. **先恢复设备，再跑一键验收**：唤醒解锁 → 重开「无线调试」（或插 USB）→
   `bash tools/device_check.sh`（自动取最新 APK、覆盖安装、清日志、跑起来、打印全部判据）。
   **端口每次都变，别照抄旧端口**——让 mDNS 自己报（这是 2026-09-12 半夜掉线后总结出来的）：

   ```bash
   ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"
   addr=$(MSYS_NO_PATHCONV=1 "$ADB" mdns services | awk '/adb-tls-connect/{print $NF; exit}')
   MSYS_NO_PATHCONV=1 "$ADB" connect "$addr" && MSYS_NO_PATHCONV=1 "$ADB" devices
   ```
   装之前先把息屏超时设长（`settings put system screen_off_timeout 86400000`）并插电，
   否则息屏 = 断 Wi-Fi = 整条链路消失（02:27 就是这么掉的，见「拦路石」）。
2. **补"用眼睛看"的六项**（脚本末尾会列）：状态栏与网页顶面有无接缝、MiuiX 设置页两种主题观感、
   长按菜单图标外观、键盘上抬、页面底部输入区被手势条盖住多少、`已完成` 卡片弹出与 15 秒收起。
3. **补 A2 的交互复现**：后台 ≥10 分钟 → 回前台点开一个任务，看是否还有"半天不出内容"；
   关注 `本次连接放弃重开` / `冷却` / `reopening` 三行（后者应始终为 0）。
4. **补 C3 的现场判读**：长标题在流体云卡片上是否滚动。若系统不滚动，需要用户在
   「要滚动」与「要流体云」之间拍板（官方原文禁止自定义视图，见「拦路石」）。
5. 「项目现状」里其余从未验证过的项：悬浮弹窗的四条 `dumpsys`（已知问题 A）、流体云是否真的出卡（P3）、
   上传链路与通知细节（P4）。

### 拦路石 / 待决策

- **🔴🔴 02:27 起：手机整体从网络上消失了 —— 根因已定位，是"屏幕灭了"。**
  因果链：会话开始时设备就已息屏；我 01:57 把 `screen_off_timeout` 设成 30 分钟
  （`svc power stayon true` 只在充电时生效，这台没插电），**02:27 正好到点屏幕又灭了**，
  随后手机从 Wi-Fi 上消失（`ping` 100% 丢包、`adb mdns services` 空、`adb connect` 被拒）。
  **教训（下次过夜任务必做）**：`settings put system screen_off_timeout 86400000`（一天），
  并**插上电**；否则息屏 → ColorOS 断 Wi-Fi → 整条真机链路直接没了，且只能靠人来恢复。
  我还改过 `screen_off_timeout` 这一项设备设置（原值未记录），你按习惯改回即可（一般 30000～120000）。
- **（旧记录，已由上面那条取代）手机整体从网络上消失了**（`ping` 100% 丢包、`adb mdns services` 空、`adb connect` 被拒），
  两条传输同时 offline —— 不是 adb 单点问题，像是息屏久了 Wi-Fi 掉线或被系统省电切断。
  也就是**我连不上设备了**，任何真机验收（含下面的视觉项）都暂停。恢复办法只有你那边（唤醒手机 / 重开无线调试）：
  醒来后插上 USB 或重开「无线调试」，`adb connect 192.168.0.185:<新端口>` 即可继续。
  已验到的部分不受影响（第五节与本节记录都在）。
- **🔴 设备处于安全锁屏，纯视觉验收做不了。**
  会话开始时设备就已经息屏，`dumpsys window policy` 显示 `mKeyguard showing=true secure=true` ——
  也就是用户警告过的那个陷阱已经发生了。我不能也不应猜密码，所以**截图类验收（状态栏接缝观感、
  MiuiX 观感、菜单图标外观、键盘上抬、完成卡片弹出、点任务的交互复现）全部挂起**。
  应用本身在锁屏后面运行正常（进程 / WebView / 注入层 / 订阅都活），所以能用日志与视图树证的部分已经证完。
  **需要用户二选一**：① 解锁一次（我把设备保持常亮继续验收）；或 ② 授权我执行
  `adb shell locksettings set-disabled true`（临时取消锁屏，验完立刻 `set-disabled false` 还原）。
  这条要拍板才能动：它降低的是用户设备的解锁门槛。
- **C3 的「无限单行滚动」不是壳能决定的事（已拿到官方原文）**：
  `ColorOS_docs/06-…/01-创建实时更新通知（Views 实现指南）.md` 的「要求」清单里有一条
  **「不得设置任何 `customContentView`（无 `RemoteViews`）」**；而 `ellipsize="marquee"`
  只有自定义 RemoteViews 才能控制。两者互斥，所以本次只做「标题单行」这一半，
  **要滚动还是要流体云需要用户拍板**（用户没拍之前按"要流体云"实现）。
  同文档另两条值得记住：被提升的卡片「默认展开、不可折叠」（正是"弹出展开为方框"的效果来源），
  以及「如果活动发生在过去，请勿使用实时更新」——完成卡片踩在这条边上，见「已知问题 D」末尾的取舍说明。
- **`已完成` 卡片的 15 秒窗口**：promoted 要求 `ongoing`，而结束态又要能自动消失，所以用了
  `setTimeoutAfter` + 本地 Handler 双保险。若真机上系统不认 `setTimeoutAfter`（AOSP 对 FGS 通知有豁免，
  我们这张不是 FGS），本地那一路仍会清掉，但进程被杀时会留下一张「已完成」常驻卡片——真机要专门试一次。
- **fault 收敛的代价**：`MAX_REOPENS_PER_BRIDGE=0` 之后，一个被桌面端拒掉的工作区在**下一条 relay 连接**
  才会重试（字段实测前台页面约 45–60 秒就会自建一条新连接，所以最坏是等一分钟）。若真机上发现某工作区
  的通知长时间不来，先看 `本次连接放弃重开` 与 `冷却 …` 两行，再决定要不要把「重试窗口」做短。
- **ColorOS 电池策略是前置条件，只有用户能改**：设置 → 电池 → 应用耗电管理 →「ZCode 远程」→ 允许完全后台行为
  + 自启动。**Doze 白名单不够**。另注意：**每次重装 APK 都可能重置这个策略**。
- **手机控制端单占**：另一台控制端接入时本端被踢进终态（`已被其他设备接管 / KICKED`）且不自动重连。
  页面上的「重新连接」按钮在桌面端仍持有会话时点一下就能回来，否则要在桌面端重新扫码。
- **本机网络**：`github.com` 间歇不可达（`git push` 需重试）；`api.github.com` 匿名额度 60 次/小时，
  取包优先用直链 `…/releases/download/android-pre/zcode-remote.apk`。
- **正式版 tag 命名空间**：安卓 `tags: ['v*']` 与鸿蒙版共用空间，建议改成 `android-v*`——等用户点头。

### 本轮最常用的几条命令

```bash
cd android-shell
python tools/check_kotlin_structure.py     # 一秒出结果：括号配平 / 包名与目录一致 / 合并残留
python tools/check_resources.py            # 一秒出结果：每个 @type/name 是否都有定义
node --test                                # 55 项（protocol 32 + inject 23）
python tools/watch_ci.py --watch           # 匿名读 CI 状态与编译错误注解
bash tools/device_check.sh                 # 真机一键验收（取包→装→清日志→跑→读全部判据）
curl -sL -o zcode-remote.apk https://github.com/Bronzesakon/Zcode_harmony/releases/download/android-pre/zcode-remote.apk
```

> `tools/device_check.sh` 是 2026-09-12 加的：它把"接上手机按顺序敲十来条 adb"固定成一个命令，
> **不依赖屏幕是否点亮**（读日志、视图树、快捷方式注册、通知内容）。它跑不了的只有"用眼睛看"的六项，
> 脚本末尾会把它们列出来。设备不可达时它会直接给出恢复步骤，而不是一路打空。

真机侧用 `mcp__adb-bridge__*`（`adb_status` 先拿 adbPath；`target` 一律显式传 `192.168.0.185:45903`；
屏幕上一切用 `adb_screenshot` 看，不要凭猜）：

```
adb(args=["install","-r","-g","zcode-remote.apk"])
adb(args=["shell","rm -f /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log*"])
adb(args=["shell","am start -n com.zcode.remote/.MainActivity"])
adb(args=["shell","input keyevent KEYCODE_HOME"])          # 退后台（别用 BACK）
adb(args=["shell","grep -E '页面开销|状态栏底色|pagestate' /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log | tail -20"])
```

**判「状态栏换色有没有生效」**：日志里每次换色一行 `状态栏底色: <state>/<theme> → #RRGGBB`。
手机（窄屏）进入控制页应看到 `main-header/light → #FFFFFF`（暗色 `main-header/dark → #202020`），
开屏与状态页应是 `boot/… → #F8F8F8` / `#161616`。**一行都不出现** → 注入层没回推，先查
`页面状态观察器`（缺失时是 debug 行）与 document-start 注入是否成功。

**更新记录**：2026-09-11 建立本区；同日第二至七轮见 git 历史。
第八轮（pre.34–pre.39）：后台存活测满 29 分 26 秒并判定保活成立、修掉会骗人的 close 归因、新增后台三把尺子。
第九轮即本轮：**过夜长任务**——两条故障定位（A1/A2）、主界面去工具栏 + 状态栏动态取色、MiuiX 设置页、
长按快捷方式、通知标题/完成卡片改版；任务清单与阶段状态就在上面，**每阶段验收后回来就地更新**。
