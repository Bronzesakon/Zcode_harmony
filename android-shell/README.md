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

**一句话**：功能已全部落地、CI 全绿（JS 55 项 + Kotlin 约 56 项单测），`pre` 每次推送都把最新 APK **覆写**到滚动预发布 [android-pre](https://github.com/Bronzesakon/Zcode_harmony/releases/tag/android-pre)（固定链接 `…/releases/download/android-pre/zcode-remote.apk`，可直接覆盖安装）——**真机验证已过九轮**（一加 PLC110 / ColorOS 16 / API 36 / WebView 153–154；第九轮见「交接文本区 → 先读这三份」）。下表按"还剩什么没结论"排：

| # | 待验证 / 待排查 | 现状 | 怎么看 |
| --- | --- | --- | --- |
| P0 | 后台存活 30 分钟（迁移文档 §8 第 4 步，决定整条路线成立与否） | **已结案：保活成立**（pre.39 实测 29 分 26 秒；第九轮又回归复验一次 6 分 59 秒 / 41 次心跳 / 284 帧）：`后台期间注入层心跳 176 次（首次在退后台后 6s，原生泵发令 176 次）、收到 1428 帧、配对确认 121 次 → 保活成立`。心跳数 176 = 泵发令 176 = 10s×176 = 29.3 分钟，自洽即证明每次都是真跑而非回前台补跑。**判据本身此前是坏的**（回前台的瞬时抖动会把页面侧计数清零，pre.28/pre.30 因此误报「0 次」），pre.33 起计数单调、判定用原生侧 delta，本行即该修法的最终复验 | 见「交接文本区 → 一句话状态」与「首次真机验证」 |
| P1 | ColorOS 弹「“ZCode 远程”正在当前页面悬浮显示…是否关闭该应用？」 | **未定位**（性质已定性，见下） | 见「已知问题 A」 |
| P2 | 会话加载慢（连标题都要半天） | **壳侧已修完并逐项实测**（重复 bridge 循环收敛、页面持有判定生效、burst 让路生效）；**A/B 已证明剩余延迟不在壳**——关掉全部 bridge 后同样慢（`subscribeConversationV4` 4.7s、`readSession` 报 `Session is not active`），属桌面端 | 见「已知问题 B」 |
| P3 | 流体云是否真的出卡 | **已结案：真的出卡**（2026-09-12 真机截图：展开的圆角卡片 + 状态栏「运行中」芯片；`dumpsys notification` 里 `flags=…PROMOTED_ONGOING`） | — |
| P4 | 上传链路（相册 / SAF / 取消不卡住）与通知细节（分组、点击定位、完成提示音） | 未验证 | 见「网页文件上传」与「实现要点」 |
| P5 | 标题回退「新建任务」（用户 2026-09-12 两次报） | **壳侧自动恢复已实现（`538f67d`，待真机验收）**：进任务 2s 判定回退态、25s 仍在则自动刷新一次（同 session 仅一次、无全局冷却）。页面自身 9–22 秒自愈的机制不变（忙时约 2 分钟；再打开一次 <2 秒即正确）；桌面端拒答 `readSession` 的根因仍在那边 | 见「已知问题 D·1 / D1-b」与 [`标题迟加载-调研报告`](标题迟加载-调研报告-2026-09-12.md) |
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

#### D1-b（2026-09-12 10:00–10:06 真机受控复现）：标题回退是"首次打开"才有的窗口，热了就秒出

用户复报「点进对话仍显示新建任务」，于是做了受控复现。**同一条任务、同一个手机，冷/热两次打开的差别是决定性的：**

| 时刻 | 操作 | 2 秒后的标题 | 22 秒后 |
| --- | --- | --- | --- |
| 10:04:37 | 冷会话**首次**打开（桌面端还没加载这条 session） | `新建任务`（连输入框都变回「向 ZCode 提问…」= 页面把它当新任务） | `Android …`（正确） |
| 10:06:1x | 同一条任务**再打开一次**（session 已热） | `Android …`（**正确**） | — |

日志里对应的两次调用（同一 session、相隔两分钟、结果相反）：

```
10:00:43  页面调用失败  280ms：zcode-session.readSession · Session is not active: sess_5c07ceba…
10:04:46  页面调用慢  1060ms：zcode-session.readSession      ← 这次成功了
```

**机制**（页面代码 `R_e`，快照 `index-nOVzQNKW.js` @345935 附近，本轮又核对了一遍）：
标题只有两条来源——(i) 工作区任务列表里的元数据 `c = As(Rs(o,t), s)`，(ii) 兜底 `l = D_e(...)` 里的
`readSession`（**`c` 为真时 D_e 直接跳过 readSession**）。首次打开时 (i) 还没就绪（页面打开任务会
拆掉再重建订阅，见快照文档「订阅代号守卫」），于是走 (ii)，而桌面端对**还没加载进来的**会话回
`Session is not active` → `u` 为 null → 标题回退成 `taskList.newThread`（页面里就是「新建任务」）。
之后页面自己的 effect 重跑（依赖里含工作区 store），这次 `readSession` 成功 → 标题自己变对。
**窗口长度就是"桌面端什么时候愿意答"**：这次实测 9–22 秒；更早那次（我的任务正在忙）约 2 分钟。

**结论：这一环在页面 + 桌面端，壳侧没有干净的杠杆。**
- 壳能做的只有减少共用同一条 relay socket 上的争抢（已经做过：fault 重开链 3× → 1×）；
  但 22 秒这个量级远大于我们那十来条 RPC 的占用，主因是页面自己的订阅拆除重建 + 桌面端拒答。
- 想真正修掉，要桌面端回答一件事：**为什么一条"已完成/未加载"的会话不能被 `readSession` 读到**
  （或者：把用户正在看的会话标记成 active）。这一条 `README` 很早以前就问过桌面端，现在有了冷/热对照的硬证据。
- 用户侧的现实办法：**再点一次就好**（热的首次打开 <2 秒即正确）。

**壳侧唯一能做的"干预"需要用户点头**：注入层读 DOM 能看出标题停在了回退值，再加一次"点任务行"
就能让页面重新解析（我们的通知定位本来就会点这一行，属于既有先例 D11）。
代价是这**动了网页 UI**（违反「网页零改动」政策），且要能区分"用户真的在写新任务"与"回退"，
否则可能劫持用户的新建任务输入。**未实现，等用户决定。**

**已实现（2026-09-12 下午，`538f67d`，用户拍板后的时钟口径）**：注入层自动替用户"再刷一次"。
t0 = 页面发出 `subscribeConversationV4`（通知/流体云定位进任务走同一条路，自动覆盖）；
t0+2s 判定 DOM 回退对——可见头部标题恰为「新建任务」+ 输入框新任务占位符（`向 ZCode 提问…` 前缀，
宽窄布局两个变体都认）。真新建任务界面是问候布局（`chat.empty.greeting.*`），**没有这条头**，
判据结构上不会误伤（用户两张截图确认，本报告 §九）。t0+25s 仍在且守卫全过 → `location.reload()` 一次。
守卫：草稿非空不动、仅前台、壳侧 sessions-index 必须持有该 session 真标题（绝不碰桌面端还没命名的
新任务）、同一 session 只刷一次（sessionStorage 存证，跨刷新防循环）；**无全局冷却**（用户明确不要）。
同会话重订阅不重置时钟（卡死重试循环也要能到期）。注意刷新不加速桌面端（09-11 实录刷新后 1.7s
再次被拒），所以它是**恢复手段**：正常冷开仍由页面 9–22s 自愈，观察器在自愈或离开回退态时静默收手
（日志 `标题回退观察开始/结束（…）`，动作行 `自动刷新页面恢复`）。测试 62 项全绿；DOM 判据与
端到端行为待真机验收（复现命令见调研报告 §六）。

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
> 环境、adb 与取日志的约定 → 「本机开发」；发版 → 「发布流程」。**本区只留：状态、未结项、拦路石、命令。**

### 先读这三份（本轮沉淀，都不在交接区里重复）

| 文件 | 什么时候读 |
| --- | --- |
| [`标题迟加载-调研报告-2026-09-12.md`](标题迟加载-调研报告-2026-09-12.md) | **用户复报「点进对话仍显示新建任务」时读这一份**：现场、日志原文、页面代码、复现步骤、已排除项、缺什么、候选修法，全在里面 |
| [`晨间报告-2026-09-12.md`](晨间报告-2026-09-12.md) | 想快速知道「本轮 11 条要求交付到哪、验到什么程度」时读它（逐条状态表 + 真机证据） |
| README 正文「已知问题 D」 | 要机制级细节时读：D1（标题回退的原始分析）、D1-b（冷/热对照）、D2（长后台卡住 + fault 收敛） |

### 一句话状态

`pre` @ `538f67d`；**最后一个改动 `app/` 的提交是 `538f67d`**（标题回退自动恢复），
CI 全绿，滚动预发布直链已含它；真机装的是 **`1.0.0-pre.50`**（**不含此功能**，验收需覆盖安装）。

本轮（第九轮，过夜长任务）从 `a3a57f5` 起共 **27 个提交**，每次推送都触发过 CI（其中三轮失败：
资源链接、测试里的 `const`、动态配色 lambda 元数——都按 CI 报错修好后转绿）。
**用户给的 11 条改动/取证要求全部交付并真机验收**（清单与证据见晨间报告）：
去应用栏 + 状态栏三态动态取色、顶部避让 / 底部沉浸、MiuiX 设置页、长按快捷方式（官方图标）、
通知标题 `状态 · 任务名`、完成卡片、fault 收敛 3×→1×。

### 未结项（按优先级，按这个顺序做）

1. **标题迟加载：壳侧自动恢复已实现（`538f67d`），待真机验收**。时钟口径（用户定）：
   t0 = 进任务，+2s 判定回退态，+25s 仍在 → 自动刷新一次（同 session 仅一次，无全局冷却）。
   验收：覆盖安装最新 pre 包 → 冷状态首次打开一条会话 → 正常情况 9–22s 自愈
   （日志 `标题回退观察结束（healed）`）；若真卡住，25s 出现 `自动刷新页面恢复` 且刷新后头部变任务名。
   ⚠️ 机制别重查——调研报告 §三/§九 判据齐全；**DOM 判据（WebView 里按文本找「新建任务」头 +
   占位符）尚未在真机核过**，若不命中先用 chrome://inspect 看会话头的实际结构。
   桌面端拒答 `readSession` 的根因（调研报告 §八）仍待那边回答。
2. **「已完成」卡片尚未被一次真实完成触发**（`已完成 · 任务名`，15 秒自动收）。
   机制与 C1 出卡同一条 promoted 路径、单测钉住；要看现场就等一次任务完成，看
   `dumpsys notification --noredact` 里标题以「已完成 · 」开头的记录 + 日志 `任务完成: …`。
3. **键盘上抬本轮没重测**。上一轮已真机验证并结案；本轮只把「键盘弹起时」的底部内边距从
   `max(系统栏,键盘)` 改成 `键盘`，而键盘弹起时 `ime.bottom ≥ bars.bottom` 恒成立，数值与旧行为一致。
   用户若要求补测再做（进会话 → 点输入框 → 看页面是否上抬）。
4. **08:11 一次瞬时现象**：网页显示「运行中」但壳侧没有运行中卡片，08:20 重启进程后又有。
   已加 `工作区相位 …` 取证行（实测 `running×1`，说明分类没错）。**下次再遇到再查**，
   看 `工作区相位` + `订阅状态` 两行即可区分"帧没到"还是"到了没发通知"。
5. 「项目现状」里其余从未验证过的项：悬浮弹窗的四条 `dumpsys`（已知问题 A）、
   上传链路与通知细节（P4）。**流体云出卡（P3）已结案**：真机截图确认出卡 + 状态栏芯片。
6. 正式版 tag 命名空间：安卓 `tags: ['v*']` 与鸿蒙版共用，建议改 `android-v*`——**待用户点头**。

### 已完成（本轮，别再重做）

- 主界面去掉应用栏与溢出菜单；顶部那条带子的颜色由注入层回推「状态名 + 主题名」+ 固定表查得
  （`core/PageBarColor.kt`），三态都与网页顶面同色无缝（截图验证）。顶部 inset 作网页 padding、底部刻意不消费。
- MiuiX 设置页（取色/度量逐项照抄本机 `docs/miuix` 源码）+ 该页排除动态配色（否则开关是壁纸色）。**别再引入 miuix 库本身**，理由写在 `values/miuix_colors.xml` 头部。
- 两条静态快捷方式（重新扫码 / 打开设置），图标用 Google 官方 Material Icons 字形。
- 通知标题改 `状态 · 任务名`、正文只留进展、标题强制单行；`已完成的` 卡片（promoted + 15s + 启动清扫）。
- `MAX_REOPENS_PER_BRIDGE` 2 → **0**（首次 fault 不再重开；跨连接冷却不变）。
- 注入层页面状态观察器（含 document-start 竞态重试与无 MutationObserver 的降级）。
- 工具：`tools/check_resources.py`（校验 `@type/name`）、`tools/device_check.sh`（真机一键验收）。
- 真机抓到的回归已修：标题加前缀后 `EXTRA_TASK_TITLE` 塞的是装饰过的标题，导致「点通知跳任务」静默失效（新增 `locateTitle` 分开，单测钉住）。
- 标题回退自动恢复（`538f67d`）：进任务 2s 判定 + 25s 窗口自动刷新一次，用户拍板的时钟与守卫（含"无全局冷却"），见「已知问题 D1-b」已实现段。
- **C3「标题单行滚动」已定案**：流体云卡片是系统渲染的，官方硬性要求「不得设置任何 `customContentView`」，
  所以 marquee 与流体云互斥；用户 2026-09-12 明确「**流体云维持现状不动**」——**不要再改这一项**。

### 拦路石 / 待决策

- **下次过夜做真机测试，先做两件事**：`adb shell settings put system screen_off_timeout 86400000`（一天）
  **并插上电**。否则屏幕一黑，ColorOS 会断掉 Wi-Fi，整条 adb 链路连同无线调试一起消失，
  只能靠人工恢复（2026-09-12 02:27 就是这么掉了一个多小时）。注意 `svc power stayon true` **只在充电时**生效。
- **无线调试端口每次都变，别照抄旧端口**，用 mDNS 现取：
  ```bash
  ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"
  addr=$(MSYS_NO_PATHCONV=1 "$ADB" mdns services | awk '/adb-tls-connect/{print $NF; exit}')
  MSYS_NO_PATHCONV=1 "$ADB" connect "$addr"
  ```
- **坐标不要凭截图估**：截图是 1272×2800，而看到的渲染图更小，按渲染图估会偏约 1.4 倍
  （本轮踩过：长按落到隔壁图标、点输入框点到消息里的图片上）。用
  `uiautomator dump` 拿到真实 `bounds` 再点；WebView 里的元素 dump 不到时，先截图再按比例换算。
- **ColorOS 电池策略是前置条件，只有用户能改**：设置 → 电池 → 应用耗电管理 →「ZCode 远程」→
  允许完全后台行为 + 自启动。**Doze 白名单不够**。**每次重装 APK 都可能重置这个策略**。
- **手机控制端单占**：另一台控制端接入时本端被踢进终态（`KICKED`）且不自动重连；
  桌面端仍持有会话时点页面上的「重新连接」就能回来。
- **进程会在后台被外部事件杀掉**：例如 Google WebView 被 Play 商店更新（`reason=16 PACKAGE UPDATED`）。
  判读后台日志前先看 `dumpsys activity exit-info com.zcode.remote`。
- **本机网络**：`github.com` 间歇不可达（`git push` 要重试）；`api.github.com` 匿名额度 60 次/小时。
  取包优先用直链 `…/releases/download/android-pre/zcode-remote.apk`。

### 本轮最常用的几条命令

```bash
cd android-shell
python tools/check_kotlin_structure.py     # 一秒：括号配平 / 包名与目录一致 / 合并残留
python tools/check_resources.py            # 一秒：每个 @type/name 是否都有定义
node --test                                # 55 项（protocol 32 + inject 23）
python tools/watch_ci.py --watch           # 匿名读 CI 状态与编译错误注解
bash tools/device_check.sh                 # 真机一键验收（取包→装→清日志→跑→打印全部判据）
```

设备侧一律显式传 target（USB serial `3B6F5RE8GCL3LYY7`，或无线 `ip:port` 现取）：

```bash
MSYS_NO_PATHCONV=1 "$ADB" -s "$S" install -r -g zcode-remote.apk
MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell "rm -f /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log*"
MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell "am start -n com.zcode.remote/.MainActivity"
MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell "input keyevent KEYCODE_HOME"     # 退后台（别用 BACK）
# 判状态栏换色 / fault 收敛 / 相位 / 通知
MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell "L=/sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log; \
  grep -E '状态栏底色|工作区相位|reopening|放弃重开|冷却' \$L | tail -20; \
  dumpsys notification --noredact | grep -E 'android.title=|shortCriticalText|PROMOTED' | head -12"
```

**判据速查**（都比翻代码快）：

| 想知道 | 看什么 |
| --- | --- |
| 状态栏换色生效没 | 每次换色一行 `状态栏底色: <state>/<theme> → #RRGGBB`；手机窄屏应是 `main-header/…` |
| fault 收敛住没 | `reopening` 应恒为 0，只出现 `本次连接放弃重开` 与 `冷却` |
| 任务到底什么相位 | `工作区相位 <key>：N 个任务 · phase×count / …`（按工作区去重） |
| 后台保活成不成立 | `后台存活检查：时长 …、心跳 N 次、收 M 帧 → 保活成立` |
| 会话标题是不是回退态 | 头部「新建任务」+ 输入框「向 ZCode 提问…」= 回退；输入框「继续输入以排队后续修改」= 正常 |
| 标题回退自动恢复干活没 | 日志 `标题回退观察开始/结束（…）/自动刷新页面恢复`；自愈走 `结束（healed）`，刷新后头部应变任务名 |

**更新记录**：2026-09-11 建立本区；同日第二至七轮见 git 历史。
第八轮（pre.34–pre.39）：后台存活测满 29 分 26 秒判定保活成立、修掉会骗人的 close 归因、后台三把尺子。
**第九轮即本轮（pre.42–pre.50，27 个提交）**：11 条要求全部交付并真机验收（去应用栏 + 状态栏动态取色、
顶部避让/底部沉浸、MiuiX 设置页、长按快捷方式、通知标题与完成卡片、fault 收敛），
另定位并落盘「标题迟加载」（冷/热对照，修法待拍板）。本区按约定就地改写：
已完成的部分压缩成上面一节，取证细节移入两份独立报告。
**2026-09-12 下午追加**：`538f67d` 标题回退自动恢复落地（2s 判定 + 25s 刷新窗，时钟与守卫用户拍板），
未结项 1 转为**待真机验收**。
