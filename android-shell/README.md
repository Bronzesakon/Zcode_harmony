# android-shell — ZCode 远程（安卓薄壳）

> **新会话接手：先跳到文末[「交接文本区」](#交接文本区新会话从这里开始)**（每次会话收尾时就地改写，非追加）。
> 本文件是唯一的状态与配置来源：进度与待验证项看「项目现状」与「已知问题」，
> 迁移清单看「交接清单」，环境、adb 与取日志约定看「本机开发」，发版看「发布流程」。已不再另设交接文档。

把上层鸿蒙工程「ZCode 远程」的薄壳思路搬到安卓：**WebView 加载 `zcode.z.ai/remote/v4` + 原生对接系统能力**，并补上鸿蒙版没有的**任务通知**与**后台保活**。

- 包名 / 应用名：`com.zcode.remote` / 「ZCode 远程」
- 技术栈：原生 Kotlin + AndroidX（不用 Flutter），`minSdk 26` / `targetSdk 35`
- 编译：**本机可出包**（`E:\AndroidSdk` + `E:\Autopsy-4.22.1\jre` + 预置 Gradle 8.9，见「本机开发」；2026-09-15 起不再依赖 CI 编译）；GitHub Actions 仍保留，推 `pre` 会自动出包并覆写滚动预发布

设计与决策记录已并入本文件（见「已敲定的决策」与「决策落点」）；上层 `../安卓薄壳迁移文档.md` 是**未入库的本地归档**，本文件不依赖它。

---

## 参考库与离线资料索引（本机，不入库）

`docs/` 与 `ColorOS_docs/` 都在 `.gitignore` 里，clone 仓库拿不到，换机器要手动拷贝。

| 目录 | 内容 | 用途 |
| --- | --- | --- |
| `docs/` | Android 官方文档快照（文件/图片选择、M3 与 Android 16 界面规范） | 上传链路与界面规范依据；清单见 `docs/README.md` |
| **`docs/miuix/`** | **miuix 组件库源码**（Compose Multiplatform，Apache-2.0） | **下次界面重写用**，约束见下 |
| `ColorOS_docs/` | ColorOS/OPPO 文档 149 篇 + Android 官方 6 篇 | 流体云路线选型依据；清单见 `ColorOS_docs/README.md` |

**本工程自己写的归档报告**（在上面两个目录里，同样不入库）——正文为了可读性把取证流水压成了结论，原始复现过程留在这几份，需要考古时再翻：

| 文件 | 内容 |
| --- | --- |
| `docs/晨间报告-2026-09-12.md` | 过夜长任务那一轮的收尾报告（11 条要求的交付与真机验收证据） |
| `docs/标题迟加载-调研报告-2026-09-12.md` | 「点进会话标题拿不到」的完整调研（含冷/热受控复现流水） |
| `docs/05-远程网页-remote-v4-20260911/` | 远程网页的静态资源快照（页面自己就是客户端，**代码即规格**）+ 两份分析：注入点/主题/滚动条审计、进对话无内容与左下角转圈 |

### `docs/miuix/`：后续界面重写的组件库

[`compose-miuix-ui/miuix`](https://github.com/compose-miuix-ui/miuix)，小米 MiuiX 设计语言的 Compose Multiplatform 实现。
抓于 **2026-09-11**（HEAD `5157b50`），浅克隆 + 全部 tag——可 `git checkout v0.9.3` 与已发布稳定版对照。

- **模块**：`miuix-ui`（核心组件，`basic/` 下 34 个：Button / Card / TextField / SearchBar / TabRow / TopAppBar / Scaffold / PullToRefresh / Snackbar / Slider / FloatingToolbar…）、`miuix-preference`、`miuix-icons`、`miuix-blur`、`miuix-squircle`、`miuix-nav`、`miuix-shader`。
- **自带 VitePress 文档站**在仓库 `docs/`（`zh_CN/` 为中文）。
- **许可 Apache-2.0**，与 `docs/` 里那些「仅供本地查阅」的厂商快照不同——它是允许随仓库分发的，放在 `docs/` 下只是沿用既有约定。库自身标注 **experimental**（"APIs may change without notice"）。
- **Maven Central 最新发布是 `0.9.4-rc01`（2026-08-13），比这份 HEAD 旧**：想用未发布的新组件只能照着源码改，这是把源码留在本地的理由；只要稳定版可直接依赖 `top.yukonga.miuix.kmp:miuix-ui:<version>`。

**采用前三件硬约束**（前置，非取舍）：

| 项 | 本工程现状 | miuix 要求 |
| --- | --- | --- |
| 界面技术 | **XML / Views**（D3：原生 Kotlin + MDC-Android） | **只有 Compose**：采用即界面层转 Compose。可用 `ComposeView` 增量接入、不必一次性重写，但 M3 主题体系要换 |
| 工具链 | Kotlin 2.1.0 / AGP 8.7.3 / Gradle 8.9 / compileSdk 35 | Kotlin 2.4.20 / AGP 9.4.0 / Compose MP 1.12.0 / compileSdk 37 → 要升一档，且**本机不能构建**，只能推 CI 验 |
| minSdk | 26 | 主体 24（更宽松）；但 **`miuix-blur` 要 33** → 用模糊要么抬 minSdk，要么做条件分支 |

> 与「界面规范（Material 3）」不冲突：**设置页已改用 MiuiX，主界面仍按 M3 角色写**；miuix 仍是后续重写的组件来源。

---

## 项目现状（先读这一节）

**一句话**：主体功能已落地，测试全绿（**JS 94 项 + Kotlin 92 项**）；真机当前跑的是**本机出的 `1.0.0-local.129`**（不走 CI、不写预发布），而 `pre` 每次推送仍会把最新 APK **覆写**到滚动预发布 [android-pre](https://github.com/Bronzesakon/Zcode_harmony/releases/tag/android-pre)（固定链接 `…/releases/download/android-pre/zcode-remote.apk`，可直接覆盖安装）——**真机验证已过十五轮**（一加 PLC110 / ColorOS 16 / API 36 / WebView 153–154）。**第十五轮两条已定案**：① 壳不再拆页面连接（「只读壳」，见「实现要点」14）；② 可见性劫持已停用，且**回前台"加载不出来"这一条已被真机证实修好**（页面自己走回 `recoverConnection`）。**仍未解的是"后台约 60 秒墙"**——两轮实测都撞到，且已排除壳干预与劫持两个原因；**2026-09-16 凌晨已定案根因**：退后台约 60–70s 后 **Chromium 的网络栈整条停止工作**（页面新建 `fetch` 76 秒既不成功也不失败），而**同一刻原生 Java 侧裸 TCP 67ms、HTTPS 200**——所以页面侧任何自救都不可能成功，**只有换承载连接的那一层**。落地形态是「链路判死 → 原生接管 → 回前台交还」，v132/v133 已真机跑通触发与交还（后台上承 139s、心跳 13、ack 14），取证与复现步骤见 **`docs/16-后台60秒墙-根因取证与原生承载.md`**。下表按"还剩什么没结论"排：

| # | 待验证 / 待排查 | 现状 | 怎么看 |
| --- | --- | --- | --- |
| P0 | 后台存活 30 分钟（迁移文档 §8 第 4 步，决定整条路线成立与否） | **已结案：保活成立**（pre.39 实测 29 分 26 秒；第九轮又回归复验一次 6 分 59 秒 / 41 次心跳 / 284 帧）。判据：后台注入层心跳 176 次 = 原生泵发令 176 次（首次在退后台后 6s），10s×176 ≈ 29.3 分钟自洽，证明每次都是真跑而非回前台补跑。判据已在 pre.33 修正（计数单调、用原生侧 delta），本行即该修法的最终复验 | 见「交接文本区 → 一句话状态」与「首次真机验证」 |
| P1 | ColorOS 弹「“ZCode 远程”正在当前页面悬浮显示…是否关闭该应用？」 | **未定位**（性质已定性，见下） | 见「已知问题 A」 |
| P2 | 会话加载慢（连标题都要半天） | **壳侧已修完并逐项实测**（重复 bridge 循环收敛、页面持有判定生效、burst 让路生效）；**A/B 证明剩余延迟不在壳**——关掉全部 bridge 后同样慢（`subscribeConversationV4` 4.7s、`readSession` 报 `Session is not active`），属桌面端 | 见「已知问题 B」 |
| P3 | 流体云是否真的出卡 | **已结案：真的出卡**（2026-09-12 真机截图：展开的圆角卡片 + 状态栏「运行中」芯片；`dumpsys notification` 里 `flags=…PROMOTED_ONGOING`） | — |
| P4 | 上传链路（相册 / SAF / 取消不卡住）与通知细节（分组、点击定位、完成提示音） | 未验证；**全程日志已备好**（`538f67d` 后：选择器请求→方式→文件详情→页面发送，见「网页文件上传」末节），等一次真机上传即可同时验收 | 见「网页文件上传」与「实现要点」 |
| P5 | 标题回退/内容不加载（用户 2026-09-12 两次报） | **现行方案：5 秒单次刷新**（旧的 +3s/+10s 两级直刷已作废）：信标后查 DOM（头部回退「新建任务」= 状态 A / 输入框灰禁 = 状态 B）或协议（页面桥零入站帧，17:29 实录的 DOM 判不出形态）任一异常即刷，保留最小间隔防死循环 | 见「已知问题 D·1 / D1-b」与 [`标题迟加载-调研报告`](标题迟加载-调研报告-2026-09-12.md) |
| P6 | 长后台后点进任务长时间不出内容（用户 2026-09-12 报） | **已定位并落地壳侧收敛**：回前台页面自恢复期间，壳为页面已持有的工作区重开 bridge → `rpc-transport-fault`，4 条 RPC 压在页面 socket 上约 2.5 分钟。`MAX_REOPENS_PER_BRIDGE` 2 → 0 | 见「已知问题 D·2」 |
| P7 | 附件上传/长文本在三个客户端全部失败（`attachmentBeginV4` 30s 无应答） | **已定性为桌面端选择性静默丢弃**（13:09 实录：7 次/40s 零应答，同窗口 relay 调度器与其它 RPC 正常）；与客户端实现无关（安卓/鸿蒙/zemote 同证）。待桌面端给出触发条件 | 见「已知问题 E」 |
| — | `MODE_SAVE`（网页请求保存文件） | **未实现**，返回 false 并记 warn | — |

### 已知问题 A：ColorOS「悬浮显示」弹窗

现象：**退出/切走应用时**弹出系统对话框「“ZCode 远程”正在当前页面悬浮显示，可能造成部分操作无响应，是否关闭该应用？」（带「上报此问题」勾选框）。

已定性：这是 ColorOS 的**悬浮窗/叠加层保护**，不是崩溃也不是 ANR。已排除：本应用清单**没有** `SYSTEM_ALERT_WINDOW`，全代码库无 `WindowManager.addView` / `TYPE_APPLICATION_OVERLAY` / `TYPE_TOAST` / `setFullScreenIntent`，保活服务只发通知不开窗（README 早期设想的「1px overlay 兜底」**并未实现**）。

剩余嫌疑：① 依赖库（如 `journeyapps:zxing-android-embedded`）向合并清单带了权限；② 系统把流体云提升出的胶囊/卡片算在应用头上；③ 用户把应用拖成了 ColorOS 的「自由浮窗」。**有 adb 后 5 分钟可定性**：

```bash
adb shell dumpsys window windows | grep -i -A3 zcode          # 有没有 overlay 类窗口
adb shell appops get com.zcode.remote                          # SYSTEM_ALERT_WINDOW 是否被开启
```

> 另两条取证命令（`dumpsys notification` 查 promoted 通知、`dumpsys activity` 查浮窗/分屏）与完整闭环见〈真机验证与读日志的闭环〉。

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

**结论链（三轮 + A/B）**：

- **第一轮（pre.13/15）**：网络与解码可排除；真机制是每次 relay 断线后 `resetClient()` 连「页面正在流哪个工作区」的认知一起丢，重建后给页面自持有的工作区开第二个 bridge → 桌面端 `rpc-transport-fault` → `reopen` 循环每 60–90 秒重演（21 次 fault / 7 次断线）。
- **第二轮（pre.21）**：补上页面自身 RPC 取证后立刻抓到真问题（`getEnterprisePricing` 反复 `coding_plan_system_busy`、`refreshCodingPlanApiKey` ~1.7s），但 `default` 仍每 ~47 秒 fault。
- **第三轮（pre.22/23）**：三项收敛全部生效——fault 上限（`default` 第 3 次 fault 后记「本次连接放弃重开」，此后 2 分钟 0 fault）、页面持有判定、起始门与 burst 让路（burst 用时 9819ms → 18936ms 说明让路在等页面）。
- **A/B（决定性）**：关掉「订阅所有工作区」（`订阅状态 active=false bridges=0`）后打开任务，页面自身 RPC **同样慢、同样失败**（`subscribeConversationV4` 甚至 4703ms，有 7 条 bridge 时是 2902ms）→ **任务打开的延迟不是壳造成的**。

**桌面端侧的可疑点（不是本仓库的代码，供排查参考）**：打开任务时一批 RPC 同时落在 1.5–2.0s，像是被同一把锁串住——`git.refresh`(1.8s)、`readWorkspaceState`(1.9s)、`usage-stats.getEntitlementSnapshot`(2.0s)、`model-provider.refreshCodingPlanApiKey`(2.0s×2)、`setting.update`(1.8s)、`subscribeSessionsIndexV4`(1.6s)；`zcode-agent.subscribeConversationV4` 2.9–4.7s；并且 `zcode-session.readSession` **失败**：`Session is not active: sess_…`（完成态任务的会话在桌面端不是 active；页面能回退到历史，所以内容最终仍会出来）。

### 已知问题 C（已结案 · 无需干预）：后台期间页面自己把 relay socket 关掉（约每 100–120 秒一次）

**现象**：只在后台发生（前台 10 分钟零重连），页面每隔约 100–120 秒自己关掉 relay socket 再重连一次；每次代价是一次干净关闭 + 约 1–2 秒重连 + 我们重放一轮订阅 burst，用户可感知的只有回前台时页面上闪过「正在尝试重连」。

**结论**：关 socket 的调用方确凿是页面自己（bundle 里 `i4t.reconnectAfterStaleWaiting`，由页面 relay 客户端的心跳 watchdog 触发；`close()` 无参、关闭码 `1005`、`wasClean=true`）。
壳侧没有任何公开手段能阻止它——引擎层唯一的杠杆 `WebView.setWindowVisibility` 是**隐藏 API**，公开层拿不到；拦页面的 `close()` 更不可行（页面先 `this.socket = void 0` 再 `close()`，吞掉只会留下孤儿 socket）。**不要再为它投入。**

**判据（排除「我们导致的」）**：后台这条链路是**健康的**——我们的探针每 10s 发出、桌面端 ack 每 10s 到、页面自己的
`pair_status_query` 在后台仍 ~20–25s 一次；把泵周期 15s→10s 后重连周期不变（仍 ~121 秒）。四个外部解释（探针没发、
桌面端没回 ack、ack 撞 30 秒看门狗、页面心跳被节流）全部被实测否定。唯一自洽的解释在页面/Chromium 内部：「重装/cancel
那个 30 秒看门狗定时器」这条路径在被后台化的渲染器里没有按预期生效。

**机制后果（不变式）**：每次 socket 重建都会让页面重开工作区，而 `default` 恰好每条连接都被桌面端 `rpc-transport-fault`；因此
pre.31 起改为**跨连接冷却**（`FAULT_COOLDOWN_CONNECTIONS=3` / `FAULT_COOLDOWN_MS=10 分钟`，见「实现要点」13），burst 重放记 `（冷却中 N 个）`。

完整取证流水（栈帧、四假设否定表、逐次时间戳）见本机 `docs/` 归档（不入库）。

### 已知问题 D：用户报的两条症状，机制与壳侧能动的部分（2026-09-12）

两条症状都是**页面/桌面端决定的现象**，壳只能减少自己造成的干扰；留档是为了下次不必重新查一遍。

#### D1「点进会话，标题拿不到，回退成『新建对话』」

**同「已知问题 B」第 1 条，机械原因在页面代码里。** 标题只有两条来源：工作区任务列表元数据，或快照 `@345935` 的兜底
`activeTaskTitle: u?.title?.trim() ? u.title : formatMessage({id: taskList.newThread})`（两处都空即回退成「新建对话」）。
`u` 来自 `resolvedActiveTaskMeta`，其兜底 `D_e()`（`@342149`）的 `readSession({…, messageLimit:1})` 用 `.catch(() => { n || s(null) })` **静默吞成 null**（用户看不到报错）；
而兜底那条路对「已完成」的会话本来就失败——桌面端回 `Session is not active: sess_…`。于是**标题正确的唯一前提变成「该工作区的任务列表 / sessions-index 已就绪」**，列表没到标题就停在回退值且不报错。

壳侧能改的只有「任务列表多快就绪」：burst 与页面会话请求共用同一条 relay socket，桌面端又按序处理，**我们每一发握手都排在用户刚点的那个任务前面**；
本次收敛（见 D2）把这条干扰降到约 1/3。**剩下的要桌面端回答**：非 active 会话为什么 `readSession` 读不到，以及打开任务时任务列表为什么要等那么久。

#### D1-b：标题回退是"首次打开"才有的窗口，热了就秒出

**机制**：标题优先取工作区任务列表元数据，元数据为真时直接跳过 `D_e()` 里的 `readSession` 兜底。**冷窗口**＝首次打开时页面会
拆掉再重建订阅，元数据还没就绪，于是走 `readSession`，而桌面端对**还没加载进来的**会话回 `Session is not active` → 标题
回退成「新建任务」；之后页面自身 effect 重跑（依赖含工作区 store），这次 `readSession` 成功，**热了就秒出**。

**窗口长度就是"桌面端什么时候愿意答"**：实测 9–22 秒，忙时约 2 分钟；热的首次打开 <2 秒即正确。**这一环在页面 + 桌面端，壳侧没有干净的杠杆。**

**现行实现（5 秒单次刷新，旧版 +3s/+10s 两级已作废）**：注入层自动替用户"再刷一次"。t0 = 页面进对话的信标——`subscribeConversationV4`
**或** `conversationRowsRangeV4`（页面有两种进对话行为：任务列表点进去发前者，会话视图打开/恢复只发后者；**单一信标会整窗漏掉**）；
通知/流体云定位进任务走同一条路，自动覆盖。t0+5s 到点看**页面自述的就绪证据**（`store.connect.completed`/订阅确认、时间线有行、信标后页面桥有入站帧），
一条都没有就直接 `location.reload()`（DOM 的「标题回退 / 输入框未就绪」只用于日志标注形态，不作判据）。草稿守卫/前台守卫/真标题交叉验证/每会话限制**全部抛弃**；
终止性只保留 **15s 最小刷新间隔**（sessionStorage 存证跨刷新）与连续上限：刷新后页面 +5s 必然还没加载完，没有间隔就是每 5 秒一刷、页面永远加载不完。
净效果：卡住每 ~15s 重试，加载成功即永久停。

**要真正修掉需桌面端回答**：为什么一条"已完成/未加载"的会话不能被 `readSession` 读到（或把用户正在看的会话标记成 active）。
**用户侧现实办法：再点一次就好。** 壳侧唯一能做的"干预"（注入层读 DOM 看出回退后补点一次任务行）**动了网页 UI**、违反「网页零改动」，
**未实现，等用户决定**。

（旧版的 10s 内容检查在真机被判不充分：卡死形态标题正确、输入框可用，DOM 层与健康页无异；现行只此一档 5 秒单次刷新。）

#### D2「长时间后台后点进某个任务，长时间不出内容；刷新网页或重启 app 就好了」

**机制**：回前台后页面自己在做一次全量恢复（重连 + 重开工作区/任务），而壳同时在为「页面已经持有的工作区」开重复
bridge → 桌面端 `rpc-transport-fault` → 壳重开（每次 4 条 RPC：hello / initialize / subscribeSessionsIndex / listen）
→ 这些 RPC 全部排在**同一条 socket** 上，压在页面恢复用的请求前面。实测这一串持续**约 2.5 分钟**，期间用户看到的
就是「点进去一直不出内容」；刷新网页（重开 socket、清掉双方状态）或重启 app 都能立刻恢复。

**本次落地的壳侧收敛**：`zcode-protocol.js` 的 `MAX_REOPENS_PER_BRIDGE` 2 → **0**（即首次 fault 不再重开）。依据：
重开**从来没有真正修好过**一个被拒的工作区（每次都 recovered、40–75 秒后再 fault），却每次都在页面正忙的那条 socket
上插 4 条 RPC；改成「一条连接一次机会」后同窗口干扰 3 轮 → 1 轮，重试交给下一条 relay 连接（前台页面约 45–60 秒
自建新连接，最坏等一分钟）。跨连接的 `FAULT_COOLDOWN_CONNECTIONS=3` 冷却仍在，长期被拒的工作区 10 分钟后彻底让路。
**认知（不变式）：页面已覆盖的工作区绝不重复开 bridge。**

**已否决（避免重走）**：回前台给页面一段「静默窗口」（推迟 burst）——页面恢复要 ~2.5 分钟，任何合理静默窗口都盖不住，却会让**每次回前台**
都可能丢掉刚完成任务的实时通知，与壳存在的理由冲突。要真正解决，得让桌面端能同时接受两条 bridge，或让页面别为同一工作区握手两次。

### 已知问题 D3：完成卡片（流体云完成提示）

完成卡片表示"刚刚结束"，踩在规范边上（`ColorOS_docs/06-…/01-创建实时更新通知（Views 实现指南）.md` 里除硬性要求外还有
「如果活动发生在过去，请勿使用实时更新」一句），是本应用里最可能被判为"不该提升"的一张。因用户明确要求"完成后强提示"
仍做，但**只挂 15 秒**，持久记录仍在 `task_completed` 渠道的普通通知里。**若哪天真机发现流体云不再出卡，第一步就是把
这张 15 秒卡片去掉再试**（该卡片是否被真实完成事件正常触发，尚未真机验证）。被提升的卡片走 promoted 路径，
「默认展开、不可折叠」是它自带的，"弹出展开为方框"效果即来源于此，不是我们控制的。

### 已知问题 E：附件上传在所有客户端无应答——`zcode-agent.attachmentBeginV4`（2026-09-12，桌面端）

**现象**：手机端上传 `zcode-remote-log.txt`（34.9KB text/plain），本地落盘与 `页面上传调用开始：zcode-agent.attachmentBeginV4` 均正常，
但页面 **40 秒内重试 7 次（约 8 秒一次）全部无应答**——RPC 窗口只有计数（`attachmentBeginV4 1/2`），无慢无失败，`发帧` 计数
证明每次都真发出去了；最终页面 `prompt-attachment-transfer.cancel` 放弃。**同一窗口桌面端 relay 调度器是活的**（pair ack 正常、
sessions-index 增量正常，13:09:22 还主动给我们重复 bridge 回了 `rpc-transport-fault`），**相邻窗口其它 RPC 正常应答**
（`readSession` 1010ms 成功、`conversationRowsRangeV4`/`attachmentReadV4` 1–1.8s 成功）。「桌面忙」不构成解释——用户在桌面空闲窗口实测仍失败。

**定性：桌面端对 `attachmentBeginV4`（可能含 `createTempTextAttachment` 一族）选择性静默丢弃。** 三种独立实现（安卓 WebView（本壳）、
鸿蒙壳、zemote（Dart，`TimeoutException after 0:00:30.000000`））同样失败——**与客户端实现无关**。**待桌面端回答**：这个调用在
什么条件下被丢弃？是否与会话的 agent 状态（active/空闲/已完成）或目标会话有关？`readSession` 对非 active 会话尚有
`Session is not active` 的显式拒绝，这里却是沉默，客户端无从做重试决策。壳侧待办：无（三种客户端同证）。

**后续（同日下午，桌面端重启后）**：上传管线全程打通（新对话与本对话都能发文件），但桌面端只注入空占位
`[Attached text/plain: attachment-N]`（两个独立会话的 agent 同证），**文本附件到不了 agent 手里**；**而暂存层内容完整**——
`~/.zcode/cli/artifacts/<sessId>/prompt-attachment-upload-*.txt` 里 106KB base64 data URI，解码即日志全文（本会话已实测解码 776 行）。
图片附件注入正常。⇒ 桌面端 bug 精确锁定：**「文本附件 → agent 上下文注入」这一跳丢内容（staging ✓ → injection ✗）**。agent 侧绕过：直接读 artifacts 目录解码。

### 已知问题 F：注入层偶发整体失效——滚动条回归原生 + 状态栏退回 boot 底色（2026-09-12，已改「稳定注入」，撤销事后自愈）

**现象**：某些加载里页面「回退」到右侧自带 14px 滚动条（零宽样式失效）**且**状态栏取色不对（页面状态不再上报，原生侧停在 boot 底色）；页面自身照常可用，重启客户端后恢复。**定性**：零宽样式
（style 节点）与状态上报（MutationObserver）机制互不相干，**同时**失效只剩一种解释——**那一次加载里注入层整体没跑**（页面自己有 WebSocket，注入层缺席也能照常浏览）；
activity 重建路径已排除（onCreate 每次 `applyUrl → installInjection`，日志每次加载都有「已安装 document-start 注入」）。git 历史两版滚动条：`fd05a69` 零宽 v1 → `43c6998` 全撤销 → `18968b1` 方案 A 悬浮自绘（现行）。

**最终方案（2026-09-12 晚，用户拍板）：撤销事后自愈 / 看门狗路线（勿重走），改「稳定注入」三道时机**——只要页面开始加载，注入就有三道互相独立的时机：

1. **document-start（主道）**：每次主帧加载前**无条件重装注册**（删掉原 `injectedHost` host 去重守卫，防任何路径漏装；脚本字符串缓存）。注册挂 WebView 实例、跨 loadUrl 生效。
2. **onPageStarted 补注**：document-start 因任何未知形态失效时，这里是最早可得时机。
3. **onPageFinished 兜底**：再兜一道。

脚本自带 `__zcodeShellInstalled` 幂等守卫（健康文档里第 2/3 道近零成本跳过）。document-start 真缺席时由补注装上，**也不漏线**：
hook 挂在 `WebSocket.prototype` 上、对补注前已存在的页面连接同样生效，它下一次 send/close 会把活实例送进来，当场「原型层收编」
（补挂监听 + 设为 activeSocket）——**零重连**，观测与壳桥接即刻恢复；只有收编**之前**窗口里的入站帧缺失（最长约一个心跳周期）。
`reloadPage`（错误面板重试/ACTION_RELOAD）现在也会先装注入再刷新。

**判据**：日志出现「注入未在 document-start 生效，由加载期补注」与「已从原型层收编现有 socket」= 抓到失效现场；没有这行而注入正常 = 稳定注入在工作。**根因（document-start 为何个别加载不生效）待取证行定案**——在引擎/上层，离线无法定案。

## 一次构建要多快

工作流按墙钟时间排布，`js` 与 `build` 是两个并行 job：

| job | 内容 | 首次 | 有缓存 |
| --- | --- | --- | --- |
| `js` | Node 协议层 + 注入层测试（94 项），不需要 JDK/SDK | ~1 min | ~40 s |
| `build` | 单次 Gradle 调用：release 单元测试（92 项）+ `assembleRelease` + 签名校验 | ~4 min | ~2m 45s |
| `prerelease` | 仅 `pre` 分支：把最新 APK **覆写**到滚动预发布 Release（固定下载链接） | ~20 s | ~20 s |
| `release` | 仅 `v*` tag：用 CHANGELOG 段落发正式 Release | ~20 s | ~20 s |

测试构成：JS **94 项**（`tools/protocol.test.js` + `tools/inject.test.js`，含手算黄金字节；其中 6 项钉「对话流 → 卡片正文」的帧契约：snapshot 取最后一段 `assistantText`、`row.delta` 只拼同一行、工具/子代理/reasoning 不覆盖正文、原生种子优先且上限 `CONVERSATION_MAX`；4 项钉**只读壳契约**：前台心跳零写入、回前台零写入、进对话卡住不重载、卡住不连刷；**另 4 项钉后台承载**（2026-09-16 加）：交还兜底「有线/有帧就不重载」与「零 socket 零帧才重载一次」、推动函数 event 档只派发合成 `online`、close 档真关线）；Kotlin **92 项 / 9 个测试类**（`@Test` 实数：`NotifyStateTest` 30 + `RelayWireTest` 21 + `UploadMimeTest` 8 + `ControllerTasksStateTest` 8 + `PromotionPolicyTest` 7 + `PageBarColorTest` 6 + `SurvivalVerdictTest` 5 + `RelayBridgeTest` 5 + `Tier2ProofTest` 2）。数字以实测为准（`node --test` 报 tests 行；Kotlin 数 `@Test`），**改测试后请同步这一行**。**这些是唯一能在无设备条件下验证的东西**，真机行为一律以设备为准。

省时间的几个点：`js` 不与 Android 构建串行；`testReleaseUnitTest` 与 `assembleRelease` 放在**同一次 Gradle 调用**里（共享 `compileReleaseKotlin`，源码只编译一次、Gradle 只启动一次）；`fetch-depth: 1`；`actions/setup-java` 的 `cache: gradle` 会恢复 `~/.gradle`（依赖缓存 + 本地 build cache）；`org.gradle.configuration-cache=true` 且 `problems=warn`，所以配置缓存只可能加速、不会让构建失败。

**坑（改 workflow 前必读）**：`paths` 与 `paths-ignore` **不能同时**用于同一事件——GitHub 会创建一个**没有任何 job** 的 run（PyYAML 能解析，本地校验拦不住）。现在只用 `paths` 显式列出会影响 APK 的路径，纯文档改动（README / CHANGELOG / docs / ColorOS_docs）自然落在过滤外，省掉一次构建。

**编译错误怎么读（重要）**：Actions 的**日志**需要鉴权（匿名 404），但 job 页面是服务端渲染的，且 **annotation 会写进 job 页面的 HTML**。所以 workflow 在 Gradle 失败时会把关键错误行（`^e: `、`Execution failed for task`、单测断言等）grep 出来以 `::error::` 重新输出，并加 `--console=plain` 去掉 ANSI/CR 噪声——本机没有 JDK/SDK，这是唯一能读到编译错误的通道。

```bash
cd android-shell
python tools/watch_ci.py     # 单次读取：run 号 / 短 SHA / 分支 / 推送时间 + 每个 job 状态 + 失败注解
                             # 退出码 0=全绿，1=失败/已取消/零 job/--watch 超时
                             # --watch 长驻会被宿主取消（你一发言它就连同子进程被杀），优先短间隔单次调用
```
commit 与本机 HEAD 不一致时它会警告——**推完立刻读会读到上一次那个同样全绿的 run**，两条的 job 名和结论一模一样，别靠它判断"我的提交绿了"。

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

这个应用有**自己的** release keystore，不复用 zemote 那把，也不用鸿蒙父工程的材料——鸿蒙工程用的是 DevEco **自动签名**材料（`~/.ohos/config/default_Zcode_harmony_….p12`），密码在 `build-profile.json5` 里是 DevEco 加密后的密文（`keytool` 打不开），且 debug 密钥可能被 DevEco 重新生成。复用自己的密钥才能保证**今后所有版本的 APK 都能互相覆盖安装**（Android 只接受签名一致的升级包）。

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

`android-shell/app/src/**`（Kotlin、资源、`assets/inject.js`、`assets/zcode-protocol.js`）、`app/src/test/**`（8 个 Kotlin 单测类）、`tools/**`、`gradle/wrapper/**` + `gradlew` + `gradlew.bat`（**必须保留**，CI 靠它构建；`gradlew` 必须是 LF，`.gitattributes` 已保证）、`build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`（版本基准 `zcodeBaseVersion` 在这里）、`.gitattributes` / `.gitignore` / `key.properties.example` / `CHANGELOG.md` / `README.md`。

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
| `android-shell/docs/`、`android-shell/ColorOS_docs/` | 124 MB + 87 MB | 厂商文档离线快照（Android 官方 + ColorOS/OPPO + miuix 源码） | 手动拷贝；内容清单见开头「参考库与离线资料索引」 |
| `../安卓薄壳迁移文档.md` | 50 KB | 迁移调研与历史回填的**完整归档**（§1–§12：技术事实清单、被推翻的三处假设、决策往返、CI 反馈回路）。耐久结论已全部并入本 README，**本文件不再依赖它**，留作细节出处 | 手动拷贝；不入库。原 `android-shell/` 下的旧快照已删（缺 §12） |
| `../项目文档.md` | 20 KB | 鸿蒙侧需求与决策历史 | 手动拷贝；不入库 |

> 厂商快照两个目录在 `.gitignore` 里，clone 拿不到——**交接时整目录拷走**。

### GitHub 仓库侧（不在文件系统里）

- **4 个 Secrets**（缺失时 CI 回退 debug 签名，见上一节）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS`。换仓库/换密钥时必须同步更新，否则新旧包签名不一致 → 只能卸载重装。
- **正式发版的 tag 命名空间撞车（待你决策）**：`v1.0.0` 已被**鸿蒙版**的 Release 占用（资产是 `entry-default-unsigned.hap`）。同一仓库两个应用共享 tag 空间，而安卓正式发版的触发条件是 `tags: ['v*']`——给鸿蒙版打 `v1.0.1` 会**误触发安卓构建**并在那个 tag 上发布安卓 APK。建议把安卓侧改成 `tags: ['android-v*']`（发版用 `git tag android-v1.0.0`）；未擅自改，因为这会改变你的发版习惯。日常预发布侧已无此问题：滚动 tag `android-pre` 不带 `v`，与 `v*` 互不触发。

---

## 首次真机验证：迁移文档 §8 第 4 步（关键里程碑）

这一步决定整条薄壳路线是否成立，做成一次可读出结论的测量。装 APK → 扫码接入（桌面端 ZCode → 远程控制 → 显示二维码）→ 在 **设置 → 诊断（真机调试）** 确认「后台存活检查」`已配对` → 退到后台（用 `input keyevent KEYCODE_HOME`，**不要**用 BACK，也别在最近任务里划掉）等 **30 分钟**（期间桌面端最好跑一个任务），后台由前台服务每 15s 原生驱动一次心跳。

回前台读诊断首行，一次实测：`后台存活检查：时长 30 分 4 秒，后台期间注入层心跳 118 次（首次在退后台后 12s，原生泵发令 120 次）、收到 214 帧、配对确认 178 次 → 保活成立（后台心跳在跑）`。**判据是"后台期间心跳有没有执行"，不是"新到了多少帧"**（帧可以在一个定时器都没跑的情况下继续到达）。

判定一律按**原生侧 delta**，不要相信页面侧绝对值：pre.28/pre.30 两轮误报「心跳未执行」，就是回前台的瞬时抖动把页面侧计数清零、16ms 后读到 0；判据已抽成可单测的 `core/SurvivalVerdict.kt`。若再见 0 次，看 `页面开销` 时间间隔（15s = 泵在驱动，20s+ 或断档 = 真没跑）。诊断行下方还有「保留服务 / 注入脚本 / 工作区数量 / 日志文件路径」；要完整日志点 **分享 / 导出日志文件**（写在外部存储、带崩溃堆栈，进程被杀也留得下，不需要电脑）。

> 步骤与判据出处：`../安卓薄壳迁移文档.md` §8——该文件是**未入库的本地归档**，仓库里没有。

---

## 实现要点（改代码前先读）
1. **document-start 注入是硬性前提**：`WebViewCompat.addDocumentStartJavaScript` 必须在 `loadUrl` 之前装好，晚了漏掉页面首个 WebSocket；系统 WebView 不支持时回退 `onPageStarted` 并在日志里警告「可能漏首帧」。
2. **协议层比预期深一层**：通知要的 `sessions-index` 不在明文 relay 载荷里，而包在 `rpc-frame`（base64 + crc32 + 分片）的 ChannelClient 值流中；`assets/zcode-protocol.js` 实现这一层。两个 Node 套件（`tools/protocol.test.js` + `tools/inject.test.js`，现共 **88 项**）钉住线格式、分片重组、通道客户端、会话索引与注入层行为，含手算黄金字节。
3. **不要调用 `webView.onPause()`**：它会挂起 WebView 定时器，正好掐掉页面的 relay 心跳。
4. **返回键不销毁进程**：`moveTaskToBack(true)` 退到后台，保住连接。
5. **`_bridges` 与 `_bridgesById` 是两个索引**：前者按工作区键（生命周期/状态），后者按 `bridgeSessionId`（入站帧路由）；混用会让所有响应被静默丢弃（Node 测试抓到过一次）。
6. **后台定时器只有 ROM 放行 + 原生驱动能救（可见性劫持已停用，见 D12）**：劫持只让页面「自以为可见」
   （Chromium 的节流与冻结看的是**真实**可见性，不看这个 JS API）——而且**页面本来就会自己挂起**：
   统一生命周期 observer（bundle `off=4712239`）把 `hidden/pagehide/freeze → suspend()`，
   而 `suspend()`（`off=4700510`）里就是 `stopHeartbeat()` + `setState('suspended')`。
   我们那个劫持把 `hidden` 吞掉，等于**既不让页面按设计挂起、又拿不到节流豁免**，两头都亏，
   故已停用（真机 23:08:53 已看到页面自己走 `recoverConnection → reconnectNow`，见「第十五轮」7）。
   后台还可能整体拿不到执行权（ColorOS 实测 +15s 定时消息迟到 10 分 30 秒、进程 CPU 十分钟零 tick）；
   前台服务 + renderer priority 只保证**进程**活着、不让 **JS 定时器**跑，故心跳改由前台服务每 **10 s**
   用 `evaluateJavascript` 原生驱动（顶替页面被节流掉/主动停掉的 10 秒心跳），且**只有在「设置 → 电池 →
   应用耗电管理」放行之后才有意义**。改这段前必读：① 泵自己的定时器与它触发的 JS 求值
   （`ShellRuntime.mainHandler`）**两条跳都必须是异步消息**，否则被 Choreographer 同步屏障饿死
   （+15s 投递曾迟到 3 分 26 秒）；② `页面心跳` 在后台的正确读法：**劫持开着时**它是"被节流到 ~1 次/分钟"，
   **劫持停用后**它是"页面主动挂起 ⇒ 0~2/窗口"（两轮实测数据见「第十五轮」6）；
   ③ `页面开销` 行尾的 `链路 ack / 探针 / 页面心跳` 是后台取证三把尺子（`探针` 是**壳发出的**
   `pair_status_query`，只读壳下**前台恒为 0**、退后台才 +1；`页面心跳` 只数页面**自己发出**的
   `pair_status_query`——两者同时为 0 就是"页面链路与我们的补帧都没在走"）；④ `WebView.setWindowVisibility`
   是隐藏 API，CI 报 `Unresolved reference`，**不要再试**；⑤ 注入层收到「进后台」时**不再清零**自己的计数
   （清零会被回前台的瞬时抖动利用、把判定读成 0）。
7. **主动订阅（D7）的副作用**：我们为其它工作区开的 bridge，其 rpc-frame 会被页面当未知 bridge 缓存（参考实现的 `_pendingBridgePayloads`），长时间内存增长；设置里的「订阅所有工作区」开关可随时退回纯被动模式，每条连接帧量很小，实测可接受。
8. **凭据不外泄**：远程链接的 `sid/hash/mid` 严禁进日志/文档/输出；`Diagnostics.redact` 会剥掉 `/remote` 之后的查询串，`device_sid` 只学不用、绝不记录。
9. **颜色一律用 M3 颜色角色**（`?attr/colorSurface` 等），不要新增固定色值——固定浅色会让暗色模式从构造上就是坏的。新增界面元素用 M3 字阶（`?attr/textAppearance*`）而不是手写 sp。
10. **`UploadMime.kt` 里的 `WILDCARD` 是拼接出来的，不要"顺手简化"成单个字面量**：写成单个字面量时 `compileReleaseKotlin` 在该列报 `Syntax error: Expecting a top level declaration`，连续三轮 CI 复现、报错逐字节相同；拼接写法语义完全相同且已验证可编译。
11. **改完 Kotlin 先跑 `python tools/check_kotlin_structure.py`**：括号配平、包名与目录一致、合并残留，一秒出结果；CI 的 Static checks job 也跑它。
12. **滚动条：网页自己那条 14px 经典条是我们隐藏掉的，不要再加第二条。** 引擎层已确认 Android WebView 把 overlay 滚动条整体关掉（`layer_tree_settings.cc:415`，见 issue 40226034，至今 P3/New），唯一把手是 legacy 轨道宽度；而"丑陋滚动条"**是网页自己写的**（`index-BMndL2ru.css` @368578：`*{scrollbar-width:auto}` + `::-webkit-scrollbar{width:14px}` + thumb `var(--color-border)`/`border:3px solid #0000`/`radius:9999px`/`min 32px`）——Chromium **只要自定义 `::-webkit-scrollbar` 就强制经典占位条**，14×dpr3.5 = 49 物理像素，丢宽的是内部 `[data-v4-timeline-scroll]` 容器（**根文档不丢**：真机 `innerWidth==clientWidth==363`）。**现行做法**：照抄网页自己的内嵌模式（bundle 函数 `J0e()` @1422852）把轨道归零——`html,body,*{scrollbar-width:none!important;scrollbar-gutter:auto!important}` + `::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}`——再用自绘 overlay 指示条补回视觉：可见 8px、距右缘内缩 3px、圆角 9999px、最短 32px、颜色读容器上的 `--color-border`、停止 700ms 后淡出、捕获阶段 passive `scroll` 监听（`event.target` 即滚动容器，不依赖选择器）、跳过 `[class*=scrollbar-hide]` / pptx 渲染面 / xterm 视口。⚠️ **滑块宽度必须用常量算**（`rail - 2*inset`）：与网页逐字一致的那套 `border:3px solid transparent` + `background-clip:padding-box` 在真机上不生效，滑块会撑满 14px 轨道、视觉粗一倍。**硬判据**：`滚动条几何: 容器占宽=0 容器宽=363 轨道=14px 内缩=3px 滑块可见宽=8px(28物理) 实测滑块宽=8px 圆角=9999px 最短=32`。**v1 只做指示、不可拖拽，只处理纵向。**
13. **页面自身的 RPC 要与我们的 bridge 分开看，且「页面覆盖情况」必须活过 relay 重连。** 被动观测也记录页面自己的 promise 调用/回复（`_tracePageCall` / `_tracePageResult` → 日志里的 `页面调用慢`、`页面调用失败`、`页面 RPC 10s`），因为「点进任务不出内容」那个请求是**页面的**，壳的 bridge 永远看不到。硬约束：① 页面已覆盖的工作区**绝不重复开 bridge**，这份认知由 `inject.js` 的 `pageCoverage` 跨 `resetClient()` 存活（第一轮正是它在重连后丢失，导致重复 bridge 与 `rpc-transport-fault` 死循环）；② `_observeInboundRpc` 里 promise 回复按 `(bridgeSessionId, id)` 配对，**不要求先学到工作区**，否则页面 bridge 的回复会被静默丢掉；③ 「页面拥有某工作区」不能只凭一个证据——页面开了 bridge（入站 `workspace-bridge-ready` 且 id 不在 `_requestedBridgeIds` 里）**且**桌面端确实拒掉我们的 bridge，两者同时成立才永久放弃（`_pageOwned`）；④ 同一条 relay 连接内 fault 超过 `_maxReopens`（**现行值 0**，即首次 fault 不再重开）就停止重开，跨连接另设冷却 `FAULT_COOLDOWN_CONNECTIONS=3` / `FAULT_COOLDOWN_MS=10 分钟`（连续 3 条连接都 fault 就冷却 10 分钟，到期自动重试）；⑤ burst 的每一站之前 `awaitPageIdle()`，让路预算整个 burst 共享（8s），故 `主动订阅完成：用时` 可能到 ~19s 是**刻意的**。
14. **只读壳（`SHELL_READ_ONLY = true`，2026-09-15 定案）：壳永不关闭页面的 socket、永不自动重载页面。** 真机把三种自伤来源抓齐了——`socket.close() 被调用 … 来自 …` 那行会点名调用者：`nudgeReconnect ← fallbackCheck`（"进对话 5s 铁判准"，**40 秒里 10 次**）、`forceReconnect ← G.__zcodeShellSetAppForeground`（**回一次前台拆一次**，20:53:04）、`forceReconnect ← heartbeatTick ← G.__zcodeShellHeartbeat`（18:24 / 18:35 / 19:40 / 20:56）。拆掉的每一次都是**页面正用着的那条** relay 连接，用户看到的"发消息转圈 / 要重连 n 次才出来 / 返回页面是它自己在重连"全是它的下游。对照实验（`diag_cmd passive_off`，这些手段全部失去 socket 句柄）：页面自己的订阅 ack 之后 **5 分钟零生命周期事件**。**壳对页面的写入面只允许五处**：① document-start 的滚动条 CSS；② **退后台之后**每 10s 一帧 `pair_status_query`（前台一帧都不写——页面自己的 10s 心跳在前台是准的，见第 6 条）；③ KICKED 终态时回前台的自愈重载（终态页面自己回不来，只有手动"重新连接"）；④ 通知点击后的定位点击；⑤ **回前台死链兜底重载**（2026-09-15 晚加，**待真机验证**：静默 >60s ＋ 5s 观察窗零入站帧 ＋ 对话 0 行，三条齐了才重载，5 分钟限流；判据是"页面已经失败"，不是"我们怀疑它失败"）。**要新增任何写操作之前，先回答两句："页面自己做不到这件事吗？"以及"我怎么知道它已经失败了？"**
15. **「后台 60 秒墙」= Chromium 的网络栈在后台死掉，不是 App 没网、不是壳的问题（2026-09-16 凌晨定案，别再重查）。** 判据是同刻三件事：墙内页面**新建** `fetch` 挂住 76s（既不成功也不失败）；同刻**原生 Java** 裸 TCP `ok 67ms`、HTTPS `HTTP 200 276ms`；同刻原生 WebSocket 1 秒内 `★配对成功（matched）`。墙的形状是**僵尸连接**：`socket readyState=1(OPEN)`、`paired true`、壳每 10s 仍在发探针，却连 ack 都没有，且**没有任何 close 事件**；连页面自己 `close()` 都卡在 CLOSING（帧发不出去）。**这一条把"页面侧自救"整类方案全部证伪**（合成 `online` 送到了页面也没用），所以：① **落地形态**是「判死 → 原生接管 → 回前台交还」（「未结项」2 的路线 B 的真正形态，触发条件见 `ShellRuntime.maybeStartNativeCarrier`：入站帧静默 ≥35s，**不是**老的"退后台 5 秒"）；② 反向不变式：**页面链路一旦自己活了（入站帧 <35s），原生立刻交还**，任何时刻只有一侧持连接；③ 判死时页面那条连接早已是僵尸，且它的网络栈是死的 ⇒ **`KICKED` 帧根本送不到页面**，页面不会进终态（这正是老路径 K1 风险的解）；④ **回前台不需要重载**：原生先交还，页面可见性恢复后 Chromium 网络栈复活，页面自己的 `recoverConnection` 会重拨；只有页面**已经掉进失败态**（`i4t.dispose` 之后自己回不来）才由 `__zcodeShellAfterCarrierReturn` 兜底重载一次（交还后 8s、无 OPEN socket、零入站帧，5 分钟限流）；⑤ 诊断判据三件套（**后台可调用**，`am broadcast` 不碰可见性）：`net_probe`（原生网络）、`bg_http`（Chromium 侧新连接）、`bg_state`（链路现场），用法见 `docs/16-…md` §4.3。
16. **原生承载的硬前置：window 控制面（2026-09-16 00:52 的真机 A/B 定案，别再漏）**。原生只做
    relay 配对（"裸终端"）会让**桌面端在 4.3 秒后拆掉自己的 window host**（`[task-realtime] unregistered host`
    + `host process (local-1) exited with code 1`），而且这**早于任何开桥**；host 一没，桌面端远程控制整体失效、
    页面 bootstrap 也失败。对照基线（只让页面自己开桥）45 秒无异常。机制：页面的身份来自 window 控制面
    （`bootstrap` → `/ws/remote-control/window/<token>` 拿 `mobileConnectionId` → 桥请求带
    `X-ZCode-Mobile-Connection-Id`），桌面端据此把接入归属到窗口；裸 relay 配对没有归属，就被收掉。
    **所以 `ShellRuntime.carrierEnabled` 默认 false**（打开就会毁掉桌面端，不能留默认开启）；
    要转正必须先补那五步（读 token → bootstrap → window socket → 带头的 workspace-bridge → 再订会话），
    取证与步骤见 `docs/16-…md` §8。**实验期间 `carrier_on` 之后，记得重启一次 ZCode 把 host 拉回来。**

---

## 任务通知（这是壳存在的理由）

三个渠道，**创建时**就定下重要性——Android 不允许事后改渠道重要性，所以「待确认要响、运行中要静」必须靠分渠道：

| 渠道 | 重要性 | 用途 |
| --- | --- | --- |
| `running_tasks` | LOW（静音） | 每个运行中任务一条常驻通知（显示标题 `状态 · 任务名`，正文=`状态 · 最新进展`）+ 一条「N 个任务运行中」群组摘要 |
| `task_attention` | DEFAULT（有声） | 任务等待确认时补发的**可拉掉**提醒 |
| `task_completed` | DEFAULT（有声） | 任务完成/失败的提醒（D10） |

- **标题与定位键必须是两个字段**：`EXTRA_TASK_TITLE`（点通知后用于在 DOM 里定位任务的**查找键**）只能塞**未装饰的原始任务名**；给人看的「显示标题」可装饰/截断为 `状态 · 任务名` 并强制单行。曾把装饰过的标题当查找键，导致「点通知跳任务」**静默失效**——现由 `locateTitle` 分开（单测钉住）。
- **状态词只有两个**（D9）：`运行中` / `等待确认`；正文取最新进展，过长截断。
- **完成判定**用参考实现的跳变：上一拍 `phase ∈ 运行态` → 这一拍 `∈ 终态`。同一任务重跑后会再次触发；`prewarming` 之类的中间态不算完成。
- **待确认按 `interactionId` 去重**，所以同一处交互不会反复响；确认后任务回到运行态，常驻通知正文随之更新。
- **点击通知**拉起应用并尝试定位任务（D11）：按查找键在 DOM 里找可点击祖先并派发完整指针事件序列；页面改版即失效，会**安静降级**为「仅打开应用」。这条是最脆弱的功能，别指望它永远有效。
- 常驻通知的更新是**节流**的（约 900ms 合并一次），完成/待确认事件则立刻发（`ShellRuntime.enqueueOngoing`）——否则会被每秒数次 preview 更新淹没。
- 完成时另有 **15 秒流体云卡片**（D3，见下节）；流体云（ColorOS 16）只提升最多 2 张卡。
- 保活服务另有一条 `keepalive` 渠道（IMPORTANCE_MIN），空载时显示「连接中」。

---

## 界面规范（M3 基线 + MiuiX 例外）

**主界面**：M3 基线——顶部应用栏与溢出菜单已去掉，只剩网页；那条带子的颜色由注入层回推的「页面状态 + 主题」查固定表得到（`core/PageBarColor.kt`）。
**设置页**：改用 MiuiX 设计语言（**唯一例外**）——取色/度量逐项照抄 `docs/miuix` 快照的 `Colors.kt` / `Card.kt` / `Component.kt` / `SmallTitle.kt` / `TopAppBar.kt` 默认值，落在 `values/miuix_colors.xml`（+ `values-night/`）与 `values/miuix_styles.xml`，并**排除动态配色**（否则开关是壁纸色）。**没有引入 miuix 库本身**：它只有 Compose 实现，且要 Kotlin 2.4.20 / AGP 9.4.0 / compileSdk 37，而本工程是 XML/Views + Kotlin 2.1.0 / AGP 8.7.3 / compileSdk 35，一次把工具链抬两档风险太大；要换成 miuix 组件时替换的是这层实现，规格不动。

M3 基线的做法（2026-09-11 落地）：

| 维度 | 做法 |
| --- | --- |
| 颜色 | 全部使用 M3 **颜色角色**（`?attr/colorSurface` / `colorOnSurfaceVariant` / `colorOutlineVariant` / `colorSurfaceContainerLow` …），删除自建调色板；角色体系自带对比度保证 |
| 动态配色 | `DynamicColors.applyToActivitiesIfAvailable`：Android 12+ 采用系统/壁纸派生调色板（与 ColorOS 流体云、系统设置同一套配色）；低版本回落 M3 基础配色（DayNight 齐全）。**设置页排除动态配色** |
| 暗色模式 | 由角色 + DayNight 自动正确。此前主题是 DayNight 但色值全钉死浅色，暗色下是坏的、系统栏图标还会不可见 |
| 系统栏 | `enableThemeEdgeToEdge()` + `padForSystemBars()`：targetSdk 35 强制 edge-to-edge，内容必须自己消费 inset；系统栏图标按 `uiMode` 在 light/dark 间切换 |
| 字体 | M3 字阶（`textAppearanceHeadlineSmall/TitleSmall/BodyLarge/BodyMedium`）替代手写 sp |
| 组件 | 设置页每小节一张 `MaterialCardView`（filled / 12dp 圆角 / `colorSurfaceContainerLow`）；行高 56dp、按钮最小高度 48dp 保证触达区；分隔线用 `colorOutlineVariant` |
| 无障碍 | 装饰性图标 `importantForAccessibility="no"` 且由 `?attr/colorOnSurface` 着色；工具栏返回键设 `navigationContentDescription` |

**已接受的取舍**：不再镜像鸿蒙版的固定 `#F8F8F8` 配色（两端一样是 nice-to-have；暗色正确、与平台一致是本轮目标）。若要恢复品牌固定色，需同时提供 light/dark 两套角色值。

上传弹窗（`dialog_upload_source.xml`）是**尺寸上的例外**：**颜色**已改角色（否则暗色下白卡会突兀），但**尺寸指标**（16dp 圆角 / 24dp 内边距 / 磁贴 14dp 与 12dp 间距）仍按用户要求对齐鸿蒙 `showUploadModal`；若要纯 M3，把圆角改成 28dp 即可。

---

## ColorOS 16 流体云（Android 16 Live Updates）

**路线选择**：走**平台路径**（标准 Android 16 Live Updates），**不走** OPPO 泛在服务卡片。后者需企业开发者账号 + 定邀白名单（`fwst@oppo.com`，T+2）+ 绑定包名与签名 SHA1 的授权码；平台路径的依据与出处见本地归档 `ColorOS_docs/06-Android原生Live Updates（双兼容路径）/`。

- `AndroidManifest` 声明 `POST_PROMOTED_NOTIFICATIONS`（普通权限，声明即具备资格）；`notify/LiveUpdate.kt` 在常驻任务通知上请求提升，并设状态栏芯片文本（`运行中` / `等待确认`，复用 D9 两状态词）。
- **硬性条件：不得设置任何 `customContentView`**（卡片由系统渲染），因此标题 marquee/单行滚动与流体云**互斥**，用户已定「流体云维持现状不动」。样式用 `BigTextStyle`（官方许可提升的四种样式之一）。
- **为什么直接写 extras 而不调 androidx API**：`setRequestPromotedOngoing` / `setShortCriticalText` 的封装只存在于 `androidx.core 1.17.0`，该 AAR 声明 `minCompileSdk=36` / `minAndroidGradlePluginVersion=8.9.1`，采用它要连带抬 Gradle/AGP/compileSdk；而平台侧效果本就是写两个 extras（`android.requestPromotedOngoing` / `android.shortCriticalText`），故直接写、API < 36 惰性无害。**toolchain 升到 compileSdk 36 后应换回官方 API。**
- 提升名额只给 **2 张卡**（`core/PromotionPolicy.kt`，7 项单测）：先「等待确认」，再按最近活动取运行中；**组摘要刻意不提升**（Android 拒绝提升摘要）。
- **完成卡片（D3）踩在规范边上**：官方 UX 规范说「活动发生在过去……请勿使用实时更新」，但用户要求"完成后强提示"，故保留一张 `已完成 · 任务名` 的 **15 秒**提升卡，持久记录仍在 `task_completed` 普通通知里。**若哪天流体云不再出卡，第一步就是去掉这张 15 秒卡再试**；被提升的卡片默认展开、不可折叠。
- **资格自检**：`LiveUpdate.describeEligibility` 把 10 项条件与「系统是否关闭本应用推广通知」（反射查 `NotificationManager.canPostPromotedNotifications`）写进诊断日志——这是「流体云为什么没出现」的唯一线索。

---

## 网页文件上传

此前**完全没有 `WebChromeClient`**，网页里点上传是死的。现在按五环打通：

1. **选择器请求**：`onShowFileChooser` 弹「选择上传方式」。
2. **方式**：相册（系统 Photo Picker，上限 5，对齐鸿蒙 `maxSelectNumber`）或文件（SAF 文档选择器），按 `fileChooserParams.mode` 决定单选/多选；文案与鸿蒙版逐字一致。
3. **文件详情**：`pendingFileCallback` 保证回调**恰好一次**——取消回 `null`，`onDestroy` 兜底取消（否则泄漏 WebView 并让该 input 永久卡住）。
4. **页面发送**：把所选文件回填给网页 input。
5. **附件通道**：上传分块走 relay socket，大字节突发即"正在发文件"的信号。

三条路径**都不需要存储权限**（Photo Picker 只授权所选媒体、SAF 逐次授权），因此没有新增权限、没有改 Manifest。类型过滤与鸿蒙**故意不一致**：鸿蒙把 ~400 条后缀喂给 `DocumentViewPicker`（该 API 必须给后缀），而 Android SAF 支持通配符且多数后缀在 `MimeTypeMap` 里无映射，照搬会把网页本来接受的文件藏起来；这里以网页 `accept` 为准，为空时放行通配（与桌面浏览器一致）。**唯一缺口**：`MODE_SAVE`（网页请求保存文件）**未实现**——返回 `false` 保持原行为，日志里记一条 warn。

全程可 grep 的关键字：`上传请求：网页打开选择器`、`上传方式：相册/文件`、`已选择 N 个文件，交回网页`、`文件选择已取消`、`页面上传调用开始/完成/失败`、`页面开销` 行的 `发帧 N 个`；「安卓端 pick 到网页内发送」每环都有行，真机上传一次即可逐环验收。

---

## 已敲定的决策（D1–D14，勿自行更改）

全部来自用户。右列是当时排除的选项，改回任何一项前先读理由（完整往返记录在本地归档 `../安卓薄壳迁移文档.md` §11，该文件未入库）。

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
| D12 | 可见性劫持 | 直接预防性实施，不做前置 spike | ~~先 spike 验证再决定~~ **反悔：2026-09-15 停用**（`SHELL_VISIBILITY_HIJACK=false`）——它挡不住 Chromium 的定时器节流，却把页面自救用的生命周期事件全吞掉 |
| D13 | 编译 | 完全依赖云端 CI，本机不装环境 | **已改：2026-09-15 起本机可出包**（见「本机开发」），CI 仍保留 |
| D14 | 签名 | **本项目专用** keystore（已修订，见「签名密钥」） | 沿用 zemote 那把（原定，因密码不可得且密钥生命周期不可控而否决） |

**为什么否决 DOM 嗅探**（D2 依据，别再回退）：通知要**逐任务**的标识/状态/预览，而列表可能虚拟化、后台不渲染时更读不到；鸿蒙版状态栏脚本从「运行时取值」反复迭代收敛为**纯 DOM 标记存在性检查**，正证明该网页 DOM 不适合承载精细语义。

**工程师补充决策**（可改，需说明理由）：通知分三渠道而非事后切重要性（渠道重要性创建后 App 不可改，见「任务通知」）；等待确认时**就地更新常驻通知 + 补发一条有声提醒**，而**不**把同一通知换渠道（渠道是通知身份之一，换渠道不可靠）；常驻通知 ID 由 `sessionId` 稳定派生、`setGroup("running_tasks")` 自动折叠；通知权限在**首次进入控制页**时申请（等有任务在跑才申请时用户正在别的 App，弹窗体验差）。

## 决策落点（原迁移文档 §11.3 的六个待定问题）

| 问题 | 结论 | 理由 |
| --- | --- | --- |
| 扫码方式 | **ZXing（journeyapps embedded）** + 剪贴板粘贴 + 手动输入 | 不依赖 Google Play Services，国产 ROM 可用；三入口互为兜底 |
| 设置页项 | 当前链接、换链接/重新加载、通知权限、清除通知、电池优化白名单、订阅所有工作区开关、诊断日志（查看/复制/分享）、版本 | 只留能影响「通知能否到达」「能否调试」的项；「仅 Wi-Fi 保活」「通知开关」价值低未做 |
| 语言 | 仅中文 | 与鸿蒙版一致 |
| minSdk | **26（Android 8.0）** | 通知渠道是 API 26+ 概念，再低要为渠道写降级分支；26 已足够 |
| ABI 拆分 | 不做，单一通用 APK | 纯 Kotlin 无 native 库 |
| 应用内更新检测 | 第一版不做 | 壳原生代码变更频率低，手动装 Release 即可 |

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

1. **网页随时可能改。** hook 依赖网页内部 WS 用法与 `sessions-index` 载荷结构，`zcode.z.ai` 重新部署可能让 hook 失效，无预警、无版本可跟（用户已评估接受）。脆弱性来源由此从「协议版本变更」变成「别人托管的网页内部实现变更」。
2. **可见性劫持是对抗性手段**，改不了浏览器内部判定——它只让页面不因「不可见」而暂停；后台定时器在实测里被压到约 1 次/分钟（见「实现要点」6）。**能救的只有 ROM 放行**（ColorOS：电池 → 应用耗电管理 → **允许完全后台行为**），放行后心跳与订阅才能后台存活。⚠️ **第十五轮已把这个劫持停用**（见 D12）：它谎报 `visible` 的同时吞掉了页面自救要用的 `visibilitychange`，回前台反而恢复不了。
3. **点击定位任务（D11）最脆弱**：按标题文本匹配 DOM，改版即失效，已按要求降级为「仅打开应用」。
4. **后台连接有天花板**：用户划掉 App 或 ROM 激进清理时，连接与通知必然中断；**门槛比原估计低得多**——仅按 HOME 退后台（不划掉），ColorOS 就在 285ms 后以 `o-stop(40)` 杀进程（前台服务在跑、Doze 白名单照样杀），**放行「允许完全后台行为」后同机后台 14 分钟连续可用**。根治要么原生 relay 客户端（拿不到执行权时同样无效，zemote 是反例），要么服务端推送（需桌面端发起，**超出本项目范围**）。
5. **强制重连是「借页面之手」**：注入层只关 socket，重连依赖页面自身逻辑；那段逻辑一变即失效。
6. **主动订阅（D7）的副作用**：为其它工作区开的 bridge 带来额外开销（桌面端常驻会话、页面把订阅帧当未知 bridge 缓存导致内存增长，见「实现要点」7）；设置里的「订阅所有工作区」开关可随时退回纯被动。

---

## 本机开发

本机（这台 Windows）**现在能本地出包了**（2026-09-15 第十四轮打通，环境与坑见该轮记录）：
`E:\AndroidSdk` + `E:\Autopsy-4.22.1\jre`（**不能用 DevEco 的 JBR，它没有 `jlink.exe`**）+ 预置的 Gradle 8.9 分发。
出包命令与门禁：

```powershell
cd android-shell
node --test tools/inject.test.js tools/protocol.test.js   # 94 项：线格式、分片重组、通道客户端、会话索引、注入层、页面 RPC 取证
python tools/check_kotlin_structure.py   # 括号配平 / 包名与目录一致 / 合并残留（约 1 秒）
python tools/check_resources.py          # 资源引用名是否存在（约 1 秒，补 aapt2 只在 CI 跑的缺口）
python tools/watch_ci.py                 # 读 CI 状态与失败原因（无需 gh / 无需 token）
python tools/doc_snapshot.py --probe     # 官方文档快照（docs/）的状态；详见脚本头部 docstring

# 本地出包直装真机（不消耗 CI）：改版本号必须加 --no-configuration-cache，否则会复用上次配置
$env:JAVA_HOME='E:\Autopsy-4.22.1\jre'; $env:ANDROID_HOME='E:\AndroidSdk'; $env:ANDROID_SDK_ROOT='E:\AndroidSdk'
$env:ZCODE_VERSION_CODE='128'; $env:ZCODE_VERSION_NAME='1.0.0-local.128'
.\gradlew.bat --stop; .\gradlew.bat --no-configuration-cache assembleRelease
```

推 `pre` 仍然可用（CI 会把 Gradle 关键错误行提升为 `::error::`），但不再是唯一手段。

- `watch_ci.py` **单次读取**即出结论：退出码 **0 = 全绿；1 = 失败 / 已取消 / 零 job（workflow 校验失败）/ `--watch` 到点仍有 job 未结束**；`--watch` 是长驻轮询，**会被宿主取消**，交互会话里别指望它。
- 网络事实：本机到 `github.com` 间歇性 TLS 失败（push / API / 下载都要重试循环）；`api.github.com` 匿名限流 60/h，优先用 `watch_ci.py`。

提交时**绝不用 `git add -A`**：仓库里有 `docs/`、`ColorOS_docs/`、`scratch/` 等不入库的大目录，一律 `git add <明确路径>`。

工具链实况：

| 东西 | 位置 / 情况 |
| --- | --- |
| JDK | 本机唯一可用的是 DevEco Studio 自带的 JBR（`E:\DevEco Studio\jbr`）；它的 `keytool` 可以生成安卓 keystore（当前签名材料就是这么来的） |
| Android SDK / Gradle | **没有**；Gradle 由 CI 跑，本地 `gradlew` 不具备构建条件 |
| `hdc` | `E:\DevEco Studio\sdk\default\openharmony\toolchains`（鸿蒙侧） |
| `gh` / `jq` | **没有**。所以读 CI 用 `tools/watch_ci.py`，发布流程全部在 CI 里用 runner 自带的 `gh` |

### 真机调试（adb）

安卓真机是**一加 PLC110（Android 16 / API 36 / WebView 153）**，USB serial `3B6F5RE8GCL3LYY7`。本机不装 Android SDK，但**有可用的 adb**，不必另装；USB 直连用上面的 adb 即可，其余步骤同无线：

| 位置 | 版本 | 说明 |
| --- | --- | --- |
| `C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe` | Platform-Tools **36.0.0** | 推荐；Android 16 需要较新的 adb |
| `E:\leidian\LDPlayer9\adb.exe` | 34.0.4 | 雷电模拟器自带，备用 |

手机走**无线调试**（开发者选项 → 无线调试）。局域网 IP 与端口见手机页面且**每次都变，用 mDNS 现取**（近期为 `192.168.0.185`）。**Android 11+ 首次必须配对**：只做 `adb connect` 会被拒——端口 TCP 通（`Test-NetConnection` 返回 True）而 `adb connect` 报 `failed to connect`；**配对端口与连接端口不是同一个**。

```bash
ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"

# 1) 手机：无线调试 → 「使用配对码配对设备」→ 得到 <配对 IP:端口> 与 6 位配对码
MSYS_NO_PATHCONV=1 "$ADB" pair <配对 IP:端口>        # 交互输入 6 位配对码
# 2) 手机：无线调试页面上的 <IP:端口>（与配对端口不是同一个）
MSYS_NO_PATHCONV=1 "$ADB" connect <IP:端口>
MSYS_NO_PATHCONV=1 "$ADB" devices -l                 # 确认出现设备
```

配对码是**一次性凭据**：不要入库，不要写进日志、文档或提交信息。

**两个本机专属的坑**：① Git Bash 会把设备绝对路径改写成 Windows 路径（`/sdcard/...` 变成 `C:/Program Files/Git/sdcard/...`），凡带设备路径的命令都要加 **`MSYS_NO_PATHCONV=1`**；② 本机有两套 adb（雷电 34 / UotanToolbox 36），出现 `cannot connect to daemon at tcp:5037` 时先 `kill-server`，再用推荐的那个 adb 重新 `start-server`。

**鸿蒙测试机不要弄混**（与本子项目无关）：`192.168.0.82:12345`（HBN-AL80 / API 24）、`192.168.0.79:41247`，另有若干串口；`dsh-hdc-bridge` MCP（`hdc_list_targets` / `hdc_shell` / `hdc_screenshot`）能直接操作它们，但**连不到安卓真机**。

#### 约定：真机验证与读日志的闭环（照这个顺序做）

本机没有 Android SDK，所以 **adb 是唯一能连安卓真机的通道**。每轮真机验证都走同一条闭环，别临时发明步骤：

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

补充命令（排查系统层问题时用；两套 adb 的 daemon 冲突、`MSYS_NO_PATHCONV` 与鸿蒙机区分见上节）：

```bash
"$ADB" shell dumpsys window windows | grep -i zcode    # 窗口类型（判断「悬浮显示」这类系统弹窗）
"$ADB" shell appops get com.zcode.remote               # 权限 / AppOps 实况
"$ADB" shell dumpsys notification --noredact | grep -i -A5 zcode   # 是否有 promoted（流体云）通知在展示
"$ADB" shell dumpsys activity activities | grep -i zcode           # 是否被系统置于浮窗/分屏
```

（本机专属坑与鸿蒙机的区分已在上节「真机调试（adb）」展开，此处不重复。）

## 目录

| 位置 | 放什么 |
| --- | --- |
| `app/src/main/java/com/zcode/remote/` | 源码：`MainActivity` / `SettingsActivity` / `ShellRuntime` / `KeepAliveService` / `WebAppBridge` / `ZcodeRemoteApp`，以及 `core/`（纯逻辑，可单测）、`notify/`（三渠道通知） |
| `app/src/main/assets/` | 注入层：`inject.js`（WS hook、心跳、任务定位；**可见性劫持已停用**）、`zcode-protocol.js`（线协议与页面 RPC 取证） |
| `app/src/test/` | Kotlin 单测（`core/` 下 8 个） |
| `tools/` | Node 测试（协议层、注入层、假桌面）+ 脚本：`check_kotlin_structure.py`、`check_resources.py`、`watch_ci.py`、`doc_snapshot.py` |
| `app/src/main/res/` | 资源；应用图标直接复用鸿蒙工程的图层 |

---
## 交接文本区（新会话从这里开始）

> **约定：本区每次会话收尾时**就地改写**，不是追加。**
> 改写时删掉已完成的条目、把现状改成新的、只保留仍然成立的信息——追加会让下一个会话先读到已经过期的结论
> （本项目此前就是因为这个才删掉了独立的交接文档，只留这一处）。
> **细节不要往这里堆**：架构与实现 → 「实现要点」；待验证项与判读口径 → 「项目现状 / 已知问题 A·B·C·D」；
> 环境、adb 与取日志的约定 → 「本机开发」；发版 → 「发布流程」。**本区只留：状态、未结项、拦路石、命令。**

### 先读这两份（本轮沉淀，都不在交接区里重复）

| 位置 | 什么时候读 |
| --- | --- |
| README 正文「已知问题 A–D」 | 要机制级细节时读：A（ColorOS 悬浮窗弹窗）、B（会话加载慢，含"A/B 证明不在壳侧"）、C（流体云出卡）、D1/D1-b/D2（标题回退与长后台卡住） |
| `docs/05-远程网页-remote-v4-20260911/` | 要**网页侧契约**时读：bundle 快照（`assets/index-nOVzQNKW.js`、`src-DHgFesxz.js`、`logger-BVohFQ23.js`）+ 注入点审计/进对话无内容分析。本轮所有"源码定案"都是从这里读出来的 |
| `docs/05-…/assets/logger-BVohFQ23.js` | 只想弄清"页面自带日志到底能拿到什么"时读它（1013 字节，PROD 下 console 全短路，唯 `window.zcode.log`） |
| `docs/15-后台原生承载-独立设计.md` | 要**后台原生承载**这条路时读：由子代理在干净上下文里只读 bundle 独立推导（**禁止读本项目实现**），含协议契约逐条 `文件:行:列` 证据、接管/交还时序、17 条真机实验。⚠️ 其 §2.6 平台结论未经本会话核验（附录 C 自述），读时先看该声明 |
| **`docs/16-后台60秒墙-根因取证与原生承载.md`** | 要**后台 60 秒墙**的真机取证时读：三次实测的逐窗数据、同刻三证（JS `fetch` 挂住 / 原生 TCP 67ms / 原生配对 1s）、被否定的 7 条假设表、v132/v133 的落地形态与诊断指令、复现步骤。**本轮所有"后台"结论都在这里** |

### 一句话状态

真机当前跑的是**本机出的 `1.0.0-local.137`**（本地 keystore 同签名，`adb install -r` 覆盖安装，
**零 CI 消耗**；v130–v137 都是 2026-09-16 凌晨为"后台 60 秒墙"做的实验件）。`pre` 分支与滚动预发布
`android-pre` 停在 **`764235e`**；只读壳那轮是本地快照提交 `c8c93e2`、收尾文档 `c5f9407`，
**均仅本地、未推送**；`docs/15-…md`、`docs/16-…md` 与根目录历史 APK 仍未跟踪。

**2026-09-16 凌晨（第十六轮）四条结论**：
1. **根因**：退后台约 60–70s 后 **Chromium 的网络栈整条停止工作**。同刻三证：墙内页面新建
   `fetch` 挂住 76s（既不成功也不失败）；**原生 Java** 裸 TCP `ok 67ms`、HTTPS `HTTP 200 276ms`；
   原生 WebSocket 1 秒内 `★配对成功（matched）`。墙的形状是**僵尸连接**（`socket=1(OPEN)`、
   `paired true`、探针照发、零 ack、无 close 事件；连页面 `close()` 都卡在 CLOSING）。
   ⇒ **页面侧自救整类方案被证伪**（合成 `online` 送到页面也没用），只有换承载层。
2. **承载形态已跑通**：`判死（入站帧静默 ≥35s）→ 原生接管 → 回前台交还`。真机：触发行
   `后台原生承载：入站帧静默 41s …` → `★接管配对成功`（0.5s）；后台上承期间原生心跳/ack
   持续（139s 那次 13/14）；`DEVICE_OFFLINE` 后 10s 自动重连并重新配对；回前台
   `Tier2: 关闭（回前台交还）`、无 KICKED。**回前台不需要重载**（页面自己 `recoverConnection` 重拨）；
   只有页面已进失败态才由 v133 的 `__zcodeShellAfterCarrierReturn` 兜底重载一次。
3. **但它现在默认关闭**：原生"裸终端"配对会在 **4.3 秒**后让桌面端拆掉自己的 window host
   （干净 A/B 见「实现要点」16 与 `docs/16-…md` §8）——**必须先补 window 控制面**才能转正。
   这是本轮最重要的新增约束。
4. **造流通道打通**：`adb input text` 进不了 WebView（复现），但新增的
   **`tools/cdp.mjs`（CDP over adb forward）**能真实输入：`Input.insertText` 被 Lexical 接受、
   点 `button[data-testid="v4-composer-send"]` 发送成功（时间线 660 → 662 行、`running×1`）。
   端到端验收因此随时可做——**只差 window 控制面那五步**。

**目标（用户拍板，两条，未变）**：
1. **前台**：对话流跟手——前提是"壳不碰页面链路"，判据见「下一步」第 4 条。
2. **后台/锁屏**：应用常驻接收并跟踪**各对话流**，推到流体云显示（ColorOS 侧已授权完全后台行为）。
   **承载层已换成原生并跑通**，但它与桌面端的 window 归属冲突（第 3 条）——补完 window 控制面即可验收。

**上一轮（第十四轮，2026-09-14/15）定案并修掉的四处机制**（历史；机制细节仍然有效，别再重做）：

| 缺陷 | 真机症状 | 定案依据 |
| --- | --- | --- |
| `rpc-frame-ack` 缺 `bridgeGeneration`/`recoveryId` | 新桥好 ~1 分钟 → 订阅/resync 集体超时 → 只能整桥回收续命（三天如此；pre.87「73 分钟零帧」同源） | bundle：桌面端对入站 payload 做身份三元组全等匹配，缺字段的 ack 被丢 → replay buffer 累积 → ~45 s 判 `ackGraceExceeded` 降级。已按网页端做法：客户端自生成 `recoveryId`，open/数据帧/ack 三处一致 |
| 运行态读错流 | 接管后卡片冻住：`工作区相位 …：41 个任务 · completedSuccess×41`，`runningTaskRefs()` 空 → 活进展被丢 | `sessions-index.phase` 是**持久态**（轮次边界才变）；"此刻在跑"在 **`controller/tasks-index` 的 `liveStatus`**。此前从未订过这条流 |
| resync 的 `base` 省略 | `expected object, received undefined`（zod 拒收），SI 缺口永远补不上 | schema 是 `.nullable()` **不是** `.optional()`；网页端始终传 `base`（无基线传 null） |
| 会话订阅带 `visibility:"background"` | 首屏快照后再无 delta 推送 | 网页端的会话订阅只发 `{topic, base}`，**从不带 visibility** |

配套：会话流补缺口规则（无基线/`fromSeq` 不连续即恢复；`notOwned` 只重订该会话）、
`sessions-index` 补 `toSeq<=seq` 旧帧丢弃（resync 风暴之源）、物理分片上限 512 KiB→1 MiB、
逻辑分片 64→1024、`bridge-error` 读 `reason+error`。

**pre.101 追加**（pre.100 真机的两条新证据逼出来的）：`subscribeControllerV4` **超时**
（那条流看来由桌面**窗口进程**提供，而接管正好把页面顶掉）→ 运行态改用**会话流自己的
`turnHeader.state`** 兜底（`ConversationTail.turnRunning()` → `TaskStore.applyConversationRunState`，
store 三级优先级：controller > 会话流 > SI 持久态）；controller 订阅**移到索引订阅之后**、
超时 30 s→12 s，不再挡住握手主路径。

### 下一步（新会话第一件事）

0. **补 window 控制面，然后才能打开后台承载（当前唯一的前置）**：原生"裸终端"配对 4.3 秒后
   会让桌面端拆掉 window host（「实现要点」16 / `docs/16-…md` §8）。步骤：
   ① 注入层把页面 URL 里的 window token 交给原生（**已定位：查询串里的 `t=`**，2026-09-16 01:08
   用 CDP 读出；同串还有 `sid/hash/mid/name/app_version`；**是凭证，只进内存不落日志**）；
   ② `POST {relayOrigin}/api/remote-control/windows/bootstrap/<token>`；
   ③ 开 `/ws/remote-control/window/<token>`，收 `window-control-ready` 拿 `windowControlSessionId`
   + `mobileConnectionId`（**一次性握手 socket，收到即关**）；④ 用
   `POST /api/remote-control/windows/<token>/workspace-bridge` 带 `X-ZCode-Mobile-Connection-Id`
   开桥（取代现在的 relay 侧开桥）；⑤ 再把 relay 的会话订阅接上。
   验收判据：做完 ①–④ 后 `carrier_on` 不再出现桌面端 `unregistered host`（用下面 1 的 A/B 法验）。
1. **后台承载的端到端验收**（本轮的收尾动作，前置=0）：让手机进一个**在跑任务**的会话
   （用 `tools/cdp.mjs` 造流，见「实现要点」16 与本条末尾），然后：
   ① 退后台（`input keyevent KEYCODE_HOME`）→ 第 4~7 窗出现
   `后台原生承载：入站帧静默 3Xs…` 与 `Tier2: ★接管配对成功（matched）`；
   ② 之后 `活进展 <key>：…` / `页面正文 <key>：…` 持续出现，流体云正文跟着变
   （判据仍是「同刻核对 `android.text`、`when` 与系统时间」）；
   ③ 回前台：`Tier2: 关闭（回前台交还）存活=Ns 心跳=n ack=m` + **页面自己**
   `socket.close() … ← i4t.recoverConnection` + `relay socket open (#N)`，**没有**壳发起的重载、
   **没有** KICKED；只有页面已进失败态才允许出现 `交还兜底：…重载一次`。
   **造流一条命令**（`tools/cdp.mjs`，需要先 `wvdebug_on` + `forward`）：进会话 → `type "<提示词>"`
   → `eval "...querySelector('button[data-testid=\"v4-composer-send\"]').click()"`。
2. **后台长测（过夜）**：补完 0 之后，亮屏挂后台 2 小时以上，看原生承载的 `存活`/`心跳`/`ack`
   是否线性增长、有没有被 ColorOS 掐掉；然后再做**熄屏**工况（本轮全部是亮屏 + 主页）。
3. **流体云正文最后一跳（页面那条路）**：`对话帧 N（正文 M 字）` 里"正文"至今**始终 0 字**
   （v129 的 23:09 窗口仍是 `正文 0 字`，而帧在流：`对话帧 30 → 64`）。要判的是
   "页面自己那条流有没有走进观测通路"（看有没有 `入站 topic 首次出现(N)：conversation/…`）。
4. **只读壳 + 两条对策的复验**（v129 已验过大半）：进对话连发 4 条以上消息，判据仍是
   ① `socket.close() 被调用 … 来自 …` 只有页面自己那两处（`i4t.dispose` 启动、`i4t.reconnectNow` 回前台）；
   ② 没有壳发起的自动重载；③ `页面开销` 每 10s 一条且**前台 `探针 0`**；
   ④ 启动有 `可见性劫持已停用：…`；⑤ 回前台页面自己 1~2s 内重拨并照旧收帧。
5. **`healDeadLinkOnResume` 仍未验证**：要验它，用诊断指令 `deadlink_test`（伪造静默 120s）或真去后台 7 分钟再回。
6. **Chromium 侧为什么会在后台死**：只知现象与边界（Java 侧同刻完全正常），没定位到哪一层。
   要定案得做进程/cgroup 级取证；本轮没手段。**不要再花时间在"注入层救后台"上**（已证伪）。

### 本轮用真机 + 源码换来的硬事实（别再重新发现）

1. **桌面端对入站 payload 做身份三元组匹配**（`bridgeSessionId` + `bridgeGeneration` + `recoveryId`）。
   缺字段的 ack 被静默丢弃 → replay buffer 持续累积 → ~45 s 后整座桥降级
   （`remote.rpcFrame.ackGraceExceeded`）：此后该桥所有 RPC 不再应答、推送停止。
   **回收之所以"有效"**，只是因为它重置了桌面端的 ack 状态——这就是"回收能续命"的真相。
2. **`sessions-index.phase` 是持久态，`controller/tasks-index.liveStatus` 才是运行态**；
   但 **controller 流在 Tier2 下不一定拿得到**（实测 30 s 超时），所以运行态必须有第二条腿：
   **会话流 `turnHeader.state`**（bundle 里 UI 判"在跑"用的就是它）。
3. **`resync*` 的 `base` 必填可空**：无基线要显式传 `null`，省略整个字段会被 zod 拒收。
4. **网页端不做周期性 resync/forceSnapshot**：订阅一次，靠推送 + 缺口驱动恢复。
   我们的 24 s 重锚是**自造**的保险（桌面端推送稀疏时的兜底），别把它当成网页行为。
5. **`window.zcode.log` 是页面在 PROD 下唯一的日志出口**（见「判据速查」末行）：
   console 被短路，普通 `info/warn/error` 连它都不发，只有 `lifecycle.*` 走这条路。
   注入层已接（`installPageLogSink`）→ 原生落 `Diagnostics`（`页面: …`）；
   真机证据：`09-14 10:30:19 INFO 页面日志汇已接通（window.zcode.log）` 后紧跟 12 条页面自述
   （`v4.conversation.subscribe.started/acknowledged/activated`、`store.connect.completed`、
   `dispose`…）。**但它是 Tier1-only**：接管期间 renderer 被冻结/顶掉，这条通道按构造就是死的
   ——这就是为什么后台窗口里一条页面自述都没有。
6. **`desktop-disconnected` 的 `reason` 字段现在读得到了**（本轮补的 `reason+error`）：
   桌面端拒桥时会把原因码与文案一起给出来，排障时先看这一行。

### 写给自己：本轮的两次误报（别重复）

1. 只凭 `活进展 … 正在执行 Bash` 一行（那是一小时前那份快照）就报"接管跑通"；
2. 把 `when` 前进当成内容刷新（其实任何 store 更新都会重发通知）。

**规矩**：涉及"流体云在跟手"的结论，必须在同一时刻同时核对 `android.text`、`when` 与系统时间，
再回日志找对应的那一拍；只凭单一信号一律不算验过。
**本轮新增第三条**：涉及"某机制生效了"的结论，先回答"这个机制在源码里的契约是什么"——
`base` 必填、ack 身份三元组、运行态在 controller 流，这三条都能从 bundle 读出来，
我却是在真机报错之后才回头读（用户当场点破）。

### 未结项（按优先级，按这个顺序做）

1. **后台承载的端到端验收（唯一没做完的一条；前置是桌面端 host 进程活着）**：
   机制已验：判死 → 原生接管 → 后台 139s 里心跳 13/ack 14 → 回前台交还、无 KICKED、无重载。
   **要验的是"正文真的推到流体云"**：需要能开桥（桌面端 host 活着）＋ 一个在跑的任务。
   步骤与判据见「下一步」0/1。
2. **后台 60 秒墙已定案（2026-09-16 凌晨，别再当未解问题）**：根因是**Chromium 的网络栈在后台死掉**
   （墙内页面 `fetch` 挂住 76s，同刻原生裸 TCP 67ms/HTTPS 200），形状是僵尸连接
   （`socket=1(OPEN)` + `paired true` + 探针照发 + 零 ack + 无 close 事件 + close 卡 CLOSING）。
   **已排除**：壳干预、可见性劫持、补帧不足、平台掐网络（netpolicy 允许、原生同刻通）、进程冻结。
   **落地形态**：`判死（入站帧静默 ≥35s）→ 原生接管（持久模式）→ 回前台交还`；
   反向不变式是"页面链路一活，原生立刻交还"。取证/复现/诊断指令见
   `docs/16-后台60秒墙-根因取证与原生承载.md`。**仍未定**的是 Chromium 内部哪一层死的（不再追）。
3. **流体云正文最后一跳**：`对话帧 N（正文 M 字）` 里的"正文"若始终 0 字，要判的是
   "页面自己那条流有没有走进观测通路"（看日志里有没有 `入站 topic 首次出现(N)：conversation/…`），
   与只读政策无关——只读门只管"不写"，不管"读什么"。原生承载上路后这一跳改由
   `Tier2Probe.progressSink → pushLivePreview` 承担。
4. **桌面端拒桥（`desktop-disconnected` / `DEVICE_OFFLINE`）现在回到主路径上了**：
   原生承载要靠它开桥，所以 host 进程一死，承载就只剩"连着但没内容"。
   判据行：`bridge open failed for <key>: desktop-disconnected: 未找到桌面窗口 host process，windowId=1`；
   桌面端同刻有 `[task-realtime] unregistered host` + `host process … exited with code 1`。
   处置：桌面端把「远程控制」关掉再打开（见「下一步」0）。
5. **`onAgentRuntimeRestarted` / `runtimeRestart` 未接**：网页端在 agent 重启时作废全部订阅并重订；
   我们只能等超时后回收，会出现一段无谓空窗。
6. **watchdog 未对齐网页端**：`FrameAssembler` 无 30 s 超时与淘汰、无 45 s ack 宽限、
   recovery 帧期限用 3 s 轮询而非 30 s 期限——当前靠回收兜住，属"能跑但不优雅"。
7. **「已完成」卡片尚未被一次真实完成触发**（机制与 C1 同一条 promoted 路径，单测钉住）。
8. **上传卡 0%**（13:20 桌面重启后复现过一次）仍缺一份含完整上传链路的日志。
9. **正式版 tag 命名空间**：安卓 `tags: ['v*']` 与鸿蒙版共用，建议改 `android-v*`——**待用户点头**。

### 本轮（2026-09-14）做完的（别再重做）

- **ack 身份三元组**：`rpc-frame-ack` 带 `bridgeSessionId`+`bridgeGeneration`+`recoveryId`；
  `workspace-bridge-open` 也带上客户端自生成的 `recoveryId`，与数据帧一致。
- **`ControllerTasksState`**（新文件）：`controller/tasks-index` 的快照/增量/缺口/旧帧丢弃，
  经 `BridgeManager→Tier2Probe.liveTaskSink→TaskStore.applyLiveTasks` 接入运行态覆盖层。
- **会话流运行态兜底**：`ConversationTail.turnRunning()`（读 `turnHeader.state`）→
  `Tier2Probe.turnStateSink` → `TaskStore.applyConversationRunState`；store 三级优先级
  （controller > 会话流 > SI 持久态）。单测钉住两级覆盖与完成卡片。
- **流体云正文只认 `assistantText`**（对齐网页 `lastAssistantPreview`）：toolCall/subagent
  不再上卡片——用户真机观察到"卡片显示对话输出之外的 tool call"，已修并单测钉住。
- **会话流缺口规则 + `notOwned` 只重订该会话**（不再整桥回收）、SI 旧帧丢弃、
  `bridge-error` 读 `reason+error`、分片上限对齐协议（1 MiB / 1024）。
- **`BridgeSession.reanchorConversations` 用每会话帧计数**判"resync 是否真的出新帧"
  （原来递增全局计数、比较每会话基线，永远判不出新帧）；重锚周期 48 s→24 s；
  回收失败 15 s 补一次重试；`openBridgeBlocking` 加串行锁。
- 真机验证过：`reanchor conversation … (force snapshot)` 连续成功、整桥回收在 pre.96 上
  真实跑通（回收后立刻拿到新快照并推进活进展）、流体云卡片正文确认为正文而非工具名。

### 已完成（更早各轮，别再重做）

- 主界面去掉应用栏与溢出菜单；顶部色带由注入层回推「状态名 + 主题名」+ 固定表查得
  （`core/PageBarColor.kt`），三态与网页顶面同色无缝（截图验证）。顶部 inset 作网页 padding、底部刻意不消费。
- MiuiX 设置页（取色/度量逐项照抄本机 `docs/miuix` 源码）+ 该页排除动态配色。**别再引入 miuix 库本身**，理由写在 `values/miuix_colors.xml` 头部。
- 两条静态快捷方式（重新扫码 / 打开设置），图标用 Google 官方 Material Icons 字形。
- 通知标题 `状态 · 任务名`、正文只留进展、标题强制单行；`已完成的` 卡片（promoted + 15s + 启动清扫）。
- 注入层页面状态观察器（含 document-start 竞态重试与无 MutationObserver 的降级）；
  `window.zcode.log` 日志汇（页面在 PROD 的唯一出口）。
- 工具：`tools/check_resources.py`、`tools/check_kotlin_structure.py`、`tools/device_check.sh`、`tools/watch_ci.py`。
- 真机抓到的回归已修：标题加前缀后 `EXTRA_TASK_TITLE` 塞的是装饰过的标题，导致「点通知跳任务」静默失效（新增 `locateTitle` 分开，单测钉住）。
- **C3「标题单行滚动」已定案**：流体云卡片是系统渲染的，官方硬性要求「不得设置任何 `customContentView`」，
  所以 marquee 与流体云互斥；用户 2026-09-12 明确「**流体云维持现状不动**」——**不要再改这一项**。

### 拦路石 / 待决策

- **桌面端拒桥（`desktop-disconnected` / `DEVICE_OFFLINE`）**：见「未结项」第 4 条——**已不在主路径上**
  （只读壳默认不开桥），只在启用「订阅所有工作区」时才需要处理。
  判据行：`bridge open failed for <key>: desktop-disconnected: 未找到桌面窗口 host process，windowId=1`。
- **后台约 60 秒墙**：见「未结项」第 2 条——**唯一还没定案的机制问题，但卡点已不在壳侧**。
  两轮零干预实测：退后台 ~60–70 s 后收/发/ack 双向同时归零，补帧照发无人应答。
  已排除壳干预、可见性劫持、补帧不足；**唯一的决策点是 A/B/C 三条路**（维持现状／后台原生承载／
  让窗口后台仍可见），等你拍板。
- **手机无线调试会随息屏断**：本轮 16:25 起端口从 `33633`→`35669`→`39489` 变了三次，
  mDNS 广播还在但端口拒绝连接；用户重开后即可用。**别照抄旧端口**，用 mDNS 现取：
  ```bash
  ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"
  addr=$(MSYS_NO_PATHCONV=1 "$ADB" mdns services | awk '/_adb-tls-connect/{print $3; exit}')
  MSYS_NO_PATHCONV=1 "$ADB" connect "$addr"
  ```
- **过夜真机测试前先做两件事**：`adb shell settings put system screen_off_timeout 86400000`（一天）
  **并插上电**；否则屏幕一黑就断 Wi-Fi，整条 adb 链路一起消失（只能人工恢复）。
- **坐标不要凭截图估**：截图是 1272×2800，看到的渲染图更小，按渲染图估会偏约 1.4 倍。
  用 `uiautomator dump` 拿真实 `bounds`；WebView 元素 dump 不到时先截图再按比例换算。
- **ColorOS 电池策略是前置条件，只有用户能改**：设置 → 电池 → 应用耗电管理 →「ZCode 远程」→
  允许完全后台行为 + 自启动。**Doze 白名单不够**。**每次重装 APK 都可能重置这个策略**。
- **手机控制端单占**：另一台控制端接入时本端被踢进终态（`KICKED`）且不自动重连；
  桌面端仍持有会话时点页面上的「重新连接」就能回来。
- **进程会在后台被外部事件杀掉**：例如 Google WebView 被 Play 商店更新（`reason=16 PACKAGE UPDATED`）。
  判读后台日志前先看 `dumpsys activity exit-info com.zcode.remote`。
- **本机网络**：`github.com` 间歇不可达（`git push` 要重试，本轮有连续 9 次失败、第 10 次成功）；
  `api.github.com` 匿名额度 60 次/小时。取包优先用直链 `…/releases/download/android-pre/zcode-remote.apk`
  （**先对 `.md5`**，滚动资产会被覆盖）。
- **抓日志优先抓文件而不是 logcat**：ColorOS 会吞掉应用 tag 的 logcat 行，但
  `/sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log` 一直写（`.log.1` 是上一份）：
  ```bash
  MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell "cat /sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log" > /tmp/shell.log
  ```

### 本轮最常用的几条命令

```bash
cd android-shell
python tools/check_kotlin_structure.py     # 一秒：括号配平 / 包名与目录一致 / 合并残留
python tools/check_resources.py            # 一秒：每个 @type/name 是否都有定义
node --test tools/inject.test.js tools/protocol.test.js   # 94 项（protocol + inject）
python tools/watch_ci.py                   # 匿名读 CI 状态与编译错误注解（单次读取；失败/取消时退出码非 0）
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
| 卡死看门狗干活没 | 布防 `卡死看门狗布防（…）` → 轻推 `卡死轻推：关闭 relay socket…`（桌面端仍在下发时是 `跳过轻推`）→ 刷新 `第 N/2 次刷新页面` → 到顶 `连续刷新 2 次未恢复，停止自动干预`；恢复 `卡死看门狗撤防: …` |
| 页面自己的日志接通没 | `页面日志汇已接通（window.zcode.log）` 后，`页面: …` 行就是页面自述（订阅/重连/失败全在这）；接通前这些信息不存在 |
| 有没有"发了没回音"的调用 | `页面调用无回包 Ns：method（桌面端从未回答）`——沉默的唯一日志形状（失败/完成都有行，只有沉默没有） |
| 注入层失效了没（滚动条/取色回退） | `注入未在 document-start 生效，由加载期补注` 行 = 抓到失效现场（补注已兜住） |

**更新记录**：2026-09-11 建立本区；同日第二至七轮见 git 历史。
第八轮（pre.34–pre.39）：后台存活测满 29 分 26 秒判定保活成立、修掉会骗人的 close 归因、后台三把尺子。
第九轮（pre.42–pre.50，27 个提交）：11 条要求全部交付并真机验收（去应用栏 + 状态栏动态取色、
顶部避让/底部沉浸、MiuiX 设置页、长按快捷方式、通知标题与完成卡片、fault 收敛）。
2026-09-12 下午：`538f67d` 标题回退自动恢复（**当时** 2s 判定 + 25s 刷新窗；现已换代）。
第十五轮（2026-09-15）：只读壳止血 + 可见性劫持停用 + 后台 60 秒墙两轮实测。
第十六轮（2026-09-16 凌晨，v130–v137 全部本机出包直装真机）：**60 秒墙定案为"Chromium 网络栈在后台死掉"**，
落地"判死 → 原生接管 → 回前台交还"（触发/交还/兜底三处），新增后台可调用的 `DiagReceiver` 诊断通道、
原生网络判据 `net_probe`、**CDP 工具 `tools/cdp.mjs`**（真机造流唯一可用通道），
取证归档进 `docs/16-…md`；**并发现原生裸终端配平会让桌面端 4.3 秒后拆掉 window host ⇒
window 控制面是硬前置，`carrierEnabled` 因此默认 false**。**未验收：原生订阅 → 正文 → 流体云的端到端一跳。**
**第十轮即本轮（2026-09-12 晚）**：网页代码全面审计（`docs/05/分析-壳端注入点审计-*.md`）后落地四件事——
① **页面日志汇**：生产页面的全部生命周期自述只走 `window.zcode?.log`（此前无人接收、静默丢弃），
inject.js 现供给 sink，原生日志出 `页面: …` 行；
② **卡死看门狗**：旧"3s 直刷"升级三级——布防（进对话信标/页面日志失败事件/心跳巡检三源）→ 3s 轻推
（关共享 socket 借页面自己的重连-重订阅梯子；桌面端仍在下发则跳过）→ 再 3s 仍卡才刷新，
**连续 2 次到顶放弃**、恢复信号自动复位（用户拍板）；
③ **沉默检测**：`页面调用无回包 Ns` 新证据行（失败/完成都有行、只有沉默没有的盲区补上了）；
镜像行在日志汇接通后自动关闭（条件式去重，socket 级与沉默线保留）；
④ **gutter 保险**：`[data-v4-timeline-scroll]{scrollbar-gutter:auto}`（当前引擎 no-op，防改版回归）。
**测试点（adb 驱动）**：`am start -a com.zcode.remote.action.DIAG --es diag_cmd kick_test|l1_test|vitals`——
kick_test = Tier2 可行性实验（同凭证开第二条 WebSocket，观察 relay 的 KICK/takeover 语义，旧连接是否被踢），
l1_test = 手动轻推，vitals = DOM 体征快照。当时 JS 68 项测试全绿（快刷 6 项按看门狗语义重写；现共 90 项）。
**第十一轮（2026-09-13 白天）**：Tier2 原生直连（M1 探针 / M2 生命周期 / M3a 线协议 / M3b 桥引擎 /
M3c 事件解码 / 静默看门狗）落地并 CI 全绿，真机端到端在 16:05 自然跑通一次
（`Tier2: ★接管配对成功（matched）` → 桥覆盖 2 个工作区）。

**第十二轮即本轮（2026-09-13 傍晚，本机时间线）**：用户三条硬要求 + 一次现场事故，全部按机制重做。
事故与根因见「本轮目标」；结论一句话：**桌面端对远端的推送是稀疏的**（页面拿到快照后整段只有心跳帧、
入站字符数为 0；对话订阅在首屏快照之后 73 分钟零帧），所以"跟手"只能靠**主动重挂**，
而"判死"只能认**渲染器不答话**（认链路静默会把活页面踢死，16:05 就是这么断的）。

**第十三轮即本轮（2026-09-14）**：用户点破"网页源码都是单向透明的，为什么还要靠真机试错"，
于是换方法——**先把契约从 bundle 里读穷**（两个并行子代理分别审 RPC 契约与会话生命周期），
再动代码，然后用真机日志验证。结论见「交接文本区 → 一句话状态」的四处缺陷表与硬事实 1–6。

**第十四轮（2026-09-15，未推 pre，全部本地出包直装真机）**：换工作方式——**本机终于能编译了**，
于是这一轮不再靠 CI 试错。三条成果 + 一条根因：

1. **本机回路打通（CI 轮次归零的关键）**：`E:\AndroidSdk`（cmdline-tools + platform-35 + build-tools）+
   **`E:\Autopsy-4.22.1\jre`**（JDK 17，**DevEco 的 JBR 没有 `jlink.exe`，AGP 的 JdkImageTransform 会直接失败**）+
   node 预置的 Gradle 8.9 分发（`services.gradle.org` 会重定向到 github，本机不可达，用腾讯/华为镜像）。
   之后 `:app:testReleaseUnitTest` 与 `:app:assembleRelease`（**本机 keystore 同签名，可直接覆盖安装**）全在本地。
   `versionName`/`versionCode` 走环境变量 `ZCODE_VERSION_NAME/CODE`；⚠️ **configuration cache 会复用上次配置**，
   改版本号要么加 `--no-configuration-cache`，要么改动源码。`mergeDexRelease` 报"文件正被使用"= 守护进程残留，
   `gradlew --stop` 即可。

2. **【主线】网页原生日志已接入并真机验证**：PROD 下页面唯一日志出口是 `window.zcode.log(level, args[])`
   （`logger-BVohFQ23.js` 全文已解；`console` 被硬短路，见 `t()`/`i()`/`a()`/`o()` 四个函数），
   注入层 `installPageLogSink` 供给该汇 → 原生 `ShellRuntime.kt` 的 `"pagelog"` 分支落 `页面: …`（截 600 字）。
   真机实测：`页面日志汇已接通（window.zcode.log）` + **47 条真实自述**（`v4.conversation.subscribe.activated`、
   `store.connect.completed {"durationMs":1229}`、`frames.upstream_attached`…）。**Phase 0 让它更强**：
   以前后台一接管、渲染器被顶掉这条通道必死；现在页面留在原地，后台也能继续自述。

3. **滚动条定案（真相反转）**：那条"丑陋滚动条"**是网页自己写的**——`index-BMndL2ru.css` @368578 有全局
   `*{scrollbar-width:auto}` + `::-webkit-scrollbar{width:14px}` + thumb(`var(--color-border)`、`border:3px solid #0000`、
   `radius:9999px`、`min 32px`)。Chromium **只要自定义 `::-webkit-scrollbar` 就强制经典占位条**，14×dpr3.5 = 49 物理像素
   ——这正是 README 里"内容盒 1222 / 屏幕 1272"的差。而且**根文档不丢宽度**（真机 `innerWidth==clientWidth==363`），
   丢的是内部 `[data-v4-timeline-scroll]` 容器。作者自己的 WebView 内嵌方案（bundle 函数 `J0e()` @1422852）就是
   `html,body,*{scrollbar-width:none!important}` + `::-webkit-scrollbar{display:none!important;width/height:0}`，我们照抄。
   ⚠️ **滑块宽度必须用常量算**（`rail - 2*inset`）：`border:3px solid transparent` + `background-clip:padding-box`
   那套（与网页写法逐字一致）在真机上没生效，滑块撑满 14px 轨道、视觉粗一倍（用户当场指出）。
   **硬判据**（`滚动条几何` 行）：`容器占宽=0 容器宽=363 轨道=14px 内缩=3px 滑块可见宽=8px(28物理) 实测滑块宽=8px`。

4. **根因：壳的心跳探针从来没被 ack 过**——`injectPayload` 把内容包成 `{type:'data', payload:…}`（数据面形状），
   而 relay **控制帧必须是顶层 `type`**（页面自己的客户端就是 `this.send({type:'pair_status_query', device_sid, client_ts})`）。
   指纹：后台 `探针 1` 而 `链路 ack 0`。前台看不出来（页面自己的定时器在跑）；**一退后台**页面的自链式 `setTimeout`
   被 Chromium 节流 → 没有页面心跳 → 壳的探针又没 ack → 页面 30s ack 看门狗 → `i4t.reconnectAfterStaleWaiting`
   关掉 socket —— 这就是"回后台立刻断线"。加 `sendControlFrame()`（顶层控制帧）后真机后台 70s+ 仍 `链路 ack 1 · paired true`，
   `KICKED 0`。**`tools/inject.test.js` 里那两条断言原本按 `frame.payload.type` 计数，等于把这个 bug 钉死在测试里**（同文件
   伪造页面心跳用的却是顶层形状），已按正确契约修正。

**未完成（下一位接手的第一件事）**：流体云卡片在**流式期间**跟手还差最后一步。JS 侧已按正确顺序
（`subscribeConversationV4` **必须先于** `subscribeSessionsIndexV4`）接上对话流，并已实现 `_trackConversationText`
（快照 `payload.rows.window` / 增量 `payload.ops` 的 `row.appended|upserted|delta`，只认 `kind==='assistantText'`）
→ `页面开销` 行尾的 `对话帧 N（正文 M 字）` → `post('convtext')` → 原生 `onConversationText` →
`store.applyLivePreview` → 卡片。

候选清单的时序已按三层修过，全部真机实测过诊断行：
① JS 侧缓存改成**模块级**（跨 `resetClient()` 存活，`pageCoverage` 的同一条教训）；
② 补"状态首次就绪时再订一次"（`bridge._convSubscribeTried`）——这条已确认会触发
（`对话流候选 <key>：N 条` 就是它打的）；
③ 原生经 `WebAppBridge.config()` 交出**所有已知工作区**的在跑会话，且注入层在**开桥那一刻现问**
（`nativeRunningSessionsProvider`）——因为 TaskStore 是随索引帧长起来的，**client 创建时的快照在刚重装/
重启后必然是空的**（真机 18:13 实测：只读创建时快照，一直报"原生运行集种子：0 个工作区"；空数组也
要给的道理就在这，否则"接线断了"和"没有在跑的任务"在日志里长得一模一样）。

**卡在哪**：以上三层都到位后，`对话流候选` 仍是 0 条——**因为那一刻确实没有任何任务在跑**
（`工作区相位：22 个任务 · completedInterrupted×1 / completedSuccess×21`，无 `running×N`）。
**要收尾只差一次"有任务在跑时"的真机采样**：起一个任务，看是否出现
`对话流候选 …：1 条（sess_…）` → `subscribed conversation for sess_…` → `对话帧 N（正文 M 字）` →
`页面正文 <key>：…`。⚠️ **adb 灌不进 WebView 的输入框**（`input tap` + `input text` 实测无效、
`uiautomator dump` 也拿不到 WebView 内的节点），所以这一步必须由人在手机上发一条消息。
**已能本地验证的那一半**：`tools/protocol.test.js` 新增 6 项单测钉住这套帧契约与候选优先级
（snapshot 取最后一段 `assistantText`、`row.delta` 只拼同一行、工具/子代理/reasoning 不覆盖正文、
活取的原生种子优先于创建时快照、provider 抛错要退回快照、上限 `CONVERSATION_MAX`），
JS 测试 73 → **79 项**、`node --test` 全绿——所以这一环除"桌面端到底发不发帧"之外都有测试兜着。

**第十四轮追加（同日晚，已把范围缩到最后一跳）**：订阅本身**是通的**——真机日志有
`subscribed conversation for sess_…` **8 次、`subscribe conversation failed` 0 次**，候选也真的非零过
（`对话流候选 E:\Mimo：2 条（sess_9e28…, sess_aceb…）`）。也就是说那条"索引先上桥之后对话订阅
不回包"的顺序约束**在 JS 路径上不成立**（至少桌面端会正常 ack）。

真正卡住的是**帧没走到我的观测通路**。为此加了一行入站 topic 直方图（每个新 topic 打一行，
最多 8 种），真机结果是**只有三种**：

```
入站 topic 首次出现(1)：controller/workspaces   payload.kind=snapshot
入站 topic 首次出现(2)：controller/tasks-index  payload.kind=snapshot
入站 topic 首次出现(3)：sessions-index/E:\Mimo  payload.kind=-
```

**从来没有 `conversation/*`**——而**页面自己**那条订阅同期是成功的
（`页面: v4 conversation store connect completed {"mode":"snapshot"}`），所以帧确实在发。
排除了"分片上限太小"：把 JS 侧抬到与 Kotlin 一致（`512 KiB/64` → `1 MiB/1024`）后**结果不变**。

**下一步（就一件事）**：在这条通路上按 `bridgeSessionId` / `messageSeq` / `fragmentCount` 统计
**原始入站帧**，并把 `_tryAssemble` 的失败连尺寸一起记一行——要回答的是"这些帧到底有没有到
我们的 WebSocket 钩子手里"。若到了却拼不起来，就是分片重组的问题；若压根没到，说明页面的对话流
走的是另一条（可能二进制的）通路，那才是要适配的地方。
**附带收获**：`controller/tasks-index` 的帧**我们本来就收得到**——那正是"此刻在跑"的权威
（`liveStatus`）所在，选对话候选时用它比用 `sessions-index.phase`（持久态）准得多。

**又追加：错误的那条 socket**。给建线处加了一行诊断后（`页面 socket 类型首次出现：<pathname>`，
只打 pathname、不带凭证），停在**工作区列表页**时页面**只开了一种 socket：`/ws`**。
而 bundle 里页面还有一条 `/ws/remote-control/window/<token>`（函数 `H4t`，`relayOrigin` 拼成
`<origin>/ws/remote-control/window/<token>`）——**它只在进入会话时才开**。
所以最自洽的解释是：**relay 那条走控制面 + `controller/*` + `sessions-index/*`（正是我们观测到的
三种 topic），而对话行数据走第二条 socket**，页面两条都读（所以它拿得到 snapshot），
我们只读了 relay 那条（所以 `conversation/*` 永远不出现）。

**确认它只需一步**：进一个会话，再看一行 `页面 socket 类型首次出现：/ws/remote-control/window/…`
是否出现。**已结案（2026-09-15 晚）：设备侧 12 次 socket 记录里从来只有 `/ws`**，而且对话帧
（`对话帧形状 conversation/sess_…`）确实是在 relay 那条上观测到的——所以上面这段"对话行数据走第二条
socket"的读法是**错的**，`/ws/remote-control/window/<token>` 只做窗口控制、不承载 AI 正文
（子代理从 bundle 独立推导出同一结论，见 `docs/15-后台原生承载-独立设计.md` §1）。**原生只需实现 `/ws` 一条。**

**第十五轮（2026-09-15 晚，本机出包 v127/v128/v129 直装真机）：用户报的"发消息转圈 / 要重连好几次才出来 /
返回页面是它自己在重连"——定案为壳自伤，已按「只读壳」政策止血；随后又定位并修掉了第二个真凶
（可见性劫持），但"后台 60 秒墙"依旧存在。**

1. **定罪证据是 `socket.close() 被调用 … 来自 …` 那一行**（它点名调用者，本轮终于把三种来源抓齐）：
   `at nudgeReconnect ← at fallbackCheck`（20:27:28–20:28:06，**40 秒里 10 次**）、
   `at forceReconnect ← at G.__zcodeShellSetAppForeground`（20:53:04，**回一次前台拆一次**）、
   `at forceReconnect ← at heartbeatTick ← at G.__zcodeShellHeartbeat`（18:24、18:35、19:40、20:56）。
   拆掉的每一次都是**页面正用着的那条** relay 连接，页面只能按自己的梯子重建——用户看到的三个症状全是它的下游。

2. **对照实验（`diag_cmd passive_off`，注入层只剩滚动条 + 状态页面上报 + 页面日志汇）**：
   21:44:48 进对话 → 21:44:53 页面自己的订阅 ack（`mode=snapshot`）→ **到 21:49:48 整整 5 分钟
   零生命周期事件**（无 store close/connect、无 unsub/resub、无 KICKED、无看门狗、无轻推）。
   页面自己的收发能力完好，"网页哪怕前台都做不到"是壳造成的。

3. **同时排掉"逐帧解析拖死页面"这个假设**（真机 20:51–20:56 的 `页面开销` 行）：每 10 秒收 30–46 帧、
   解码合计 **90–310 ms**、单帧峰值 11–55 ms、长任务 0–6 个——是主线程上的真实开销，但只占 1–3%。
   所以**不动 `_trackConversationText` 这条取正文的路**（流体云靠它，改写它没有证据支持）。

4. **`SHELL_READ_ONLY = true`（inject.js §4）**：`forceReconnect` / `nudgeReconnect` 变空操作
   （只留一行"本会触发"日志——那句话本身就是链路健康度的诊断量）；`stallReloadIfAllowed` /
   `reloadForMissingConversation` **永不重载**；前台**一帧都不写**页面 socket（心跳探针只在退后台后发）；
   看门狗撤防后 60 s 内不再布防（此前**布防 267 次 / 撤防 265 次**，全是同一对理由打转，
   537 行日志把真事件淹掉了）。**壳对页面的写入面只剩五处**：① document-start 的滚动条 CSS；
   ② 退后台后每 10 s 一帧 `pair_status_query`；③ KICKED 终态时回前台的自愈重载（终态页面自己回不来）；
   ④ 通知点击后的定位点击；⑤ **回前台死链兜底**（第 7 条，三条判据齐了才重载，5 分钟限流）。
   其余全是"读"。

5. **三个功能逐条对过，都不受影响**：滚动条＝纯 CSS 注入；状态栏取色＝只读 DOM/CSSOM
   （`passive_off` 期间照样打 `状态栏底色: main-header/dark → #202020`）；流体云正文＝只读观测
   页面自己那条流（`_trackConversationText` → `convtext` → `applyLivePreview`）。
   **JS 测试 79 → 88 → 90 项**（只读契约 4 条：前台零写入、回前台零写入、进对话卡住不重载、
   卡住不连刷），`node --test` 全绿。

6. **后台静默：形状量了两轮，第一轮的归因已被第二轮修正（仍未解）**。

   **第一轮（22:08–22:15，后台 7 分 21 秒，壳零干预，当时可见性劫持还开着）**：注入层补帧 **44 次**
   全部 `send` 成功；页面自己的 10 s 心跳在 44 个观察窗里只跳 **6 次**；入站帧前 ~50 s
   233/31/59/36/20/39（流体云追到的第 97→100 次回复）之后**全程 0**；配对确认 10 次后归零。

   **第二轮（23:08–23:11，v129，可见性劫持已停用）**：第二段后台 23:09:05 起，逐窗
   收/发帧 35/34 → 43/41 → 36/33 → **41/39（23:10:05）→ 0/0（23:10:15 起直到 23:11:35）**，
   `对话帧` 冻在 64，`页面心跳` 0~2，`探针 1` 照发但 `ack` 从 2 变 **0**；
   23:11:05 打 `后台链路静默 60s`、23:11:35 打 `relay 心跳陈旧 92s`。
   **即：退后台约 60 s 后双向同时归零，比第一轮的 ~70 s 还早，而这一轮壳更"干净"。**

   **修正后的归因**：第一轮那句"Chromium 把定时器节流到约 1 次/分钟 ⇒ 这就是墙"**只对了一半，
   而且不是主因**。第二轮源码级证据（我自己核过偏移）：
   * 页面有一处统一的生命周期 observer（`off=4712239`）：`hidden/pagehide/freeze → onSuspend`、
     `visible/pageshow/online/resume → onRecover`；
   * `suspend()`（`off=4700510`）里 `hiddenStartedAt=Date.now() … stopHeartbeat() … setState('suspended')`
     ——**页面在后台是自己主动停心跳的**；
   * `heartbeatAckTimeoutMs ?? 3e4`（`off=4704835`）＝30 s ack 看门狗。
   所以第一轮看到的"6 次心跳"是**次生现象**：可见性劫持把 `hidden` 吞了，页面以为自己在可见状态、
   继续按 10 s 计划心跳，才被 Chromium 节流到 ~1 次/分钟。**主因是"页面被我们蒙住、没能按自己的设计挂起"，
   而墙本身另有出处**——第二轮劫持已停用、页面如实挂起，墙照旧（60 s）。

   **两轮合起来的硬结论**：这堵墙**不是**壳的干预造成的（两轮证据），**也不是**可见性劫持造成的
   （第二轮已排除），**也不是**补帧不够（补帧全程在发，前期还被 ack）。**Chromium WebView 里这条
   实时流就是撑不过后台约 60 秒**；要后台持续跟手，只能换"谁来承载连接"（见第 8 条与「未结项」2）。
   仍未定的是"远端为什么在 60 s 决定停"（桌面端的判据？页面挂起时是否改了订阅的 visibility？），
   这正是子代理方案里 E1/E14 要分离的问题——**不要在没有实验前就写下原因**。

7. **回前台"加载不出来、重启就秒开"——现场已定案，对策之一已被真机证实有效**。
   现场（22:15:26）：`relay socket closed (code=1006 clean=false)` → 1 秒内 `open (#2)` →
   `v4 conversation subscription started` → **此后永远没有 `acknowledged`**，体征
   `{"chat":true,"timeline":true,"rows":0}` 一直空 → 22:15:48 重启应用 → 22:16:04 `acknowledged`
   （250 ms）· `mode=snapshot` · 时间线 234 行。

   * **停用可见性劫持**（`SHELL_VISIBILITY_HIJACK = false`）：**v129 真机已证实恢复**——回前台那一刻
     页面**自己**执行了它的恢复路径：`socket.close() 被调用 … 来自 at i4t.reconnectNow (:897:321621)
     ← at i4t.recoverConnection (:897:316158)`（23:08:53.109）→ `relay socket closed (code=1005 clean=true)`
     → `relay socket open (#2)`（**1.2 s 重拨完**）→ 之后 23:09:15/25 仍在收帧（122/39），**重拨没有打断对话**。
     对照 v128：同样的回前台只有被动的 `1006 clean=false` 收尸，从来看不到 `recoverConnection`。
     这正是 `c4t` observer 被劫持吞掉后缺失的那一环，所以**这一条可以按"已修"记**。
   * **回前台死链兜底**（`healDeadLinkOnResume`，**仍未验证**）：静默 >60 s ＋ 5 s 观察窗零入站帧
     ＋ 对话 0 行，三条齐了才重载一次（5 分钟限流）。v129 那次回前台静默 0 s，所以**一次都没触发**
     （符合预期：链路是活的）。真机测试点：诊断指令 `deadlink_test`。
   * **JS 测试 88 → 90 项**（有帧不重载 / 零帧+0 行才重载）。

8. **后台要持续跟手，只有"谁来承载连接"这一层能改**（三条路的取舍见「未结项」第 2 条）。
   子代理已在**干净上下文里只读网页 bundle**（禁止读本项目任何实现）独立推导了一份方案：
   `docs/15-后台原生承载-独立设计.md`（约 6.6 万字符 / 1237 行 / 6 节 + 3 附录）。
   它的要点与本轮核验结果：**只有两条 WebSocket**（`/ws` 承载全部数据面；`/ws/remote-control/window/<token>`
   只做窗口控制）——设备侧 12 次 socket 记录全部是 `/ws`，与我们的观测一致；
   核心不变式是"**任意时刻只有一条 `/ws`**"，接管与交还都做成**确认旧连接已 CLOSED 之后**才 dial 的串行转移；
   最大风险是 `KICKED` 不可自愈终态。
   ⚠️ **它的 §2.6（平台侧"Chromium 节流不影响 WebSocket、`onmessage` 后台仍派发"）未经本会话二次核验**
   （子代理自述：其检索通道与本会话工具不同，附录 C 已声明限制）；**"网页后台停心跳是主因"这一条我已用
   本地 bundle 自行核过偏移，成立**。方案要不要采纳、要不要先做它列的 E1/E14/E2 实验，**等用户拍板**。

### ⭐ 已从 bundle 定案：网页其实有**两层**客户端，我们只做了一层

**不需要真机确认了**——`index-nOVzQNKW.js` 里写得很清楚，`Q4t(e)` 是启动编排：

```
1) bootstrap:  fetch  POST-less  /api/remote-control/windows/bootstrap/<token>      (F4t @4727723)
2) window socket: new WebSocket(`${relayOrigin}/ws/remote-control/window/${token}`) (H4t @4735747)
   等它回 {type:'window-control-ready', windowControlSessionId, mobileConnectionId} (V4t @4735700)
3) workspace bridge: POST /api/remote-control/windows/<token>/workspace-bridge      (I4t @4728278)
   请求头必带 `X-ZCode-Mobile-Connection-Id: <mobileConnectionId>`
```

也就是说：

* **`/ws`（relay）＝配对/控制面**：`auth_init`→`challenge`→`response`、`pair_status_query/ack`，
  以及我们观测到的那三种 topic（`controller/workspaces`、`controller/tasks-index`、`sessions-index/…`）。
* **`/ws/remote-control/window/<token>` ＋ `<token>/workspace-bridge`（HTTP）＝窗口控制面**，
  它才是"这个手机连接"的身份来源（**`mobileConnectionId`**），工作区桥也由它开。
* 网页**两层都做**，所以我们只做第一层时会出现：对话订阅 ack 成功却永不投递、
  原生接管被回 `desktop-disconnected` / `未找到桌面窗口 host process`（`windowId=1`）。

**因此 `conversation/*` 走的是窗口控制面，不是 relay 数据面。** 这也修正了「下一件事」的方向：
不是"给 relay 加订阅"，而是**壳必须成为 window control 客户端**（先 bootstrap 拿 token，
再开 window socket 拿 `mobileConnectionId`，之后才谈得上对话流；原生侧更该这么走，因为
`X-ZCode-Mobile-Connection-Id` 是个 HTTP 头，Kotlin 比注入层更自然）。
这条是 2026-09-15 整场排查的终点，也是下一位接手的**真正起点**。

提交序列（本轮，均已推送 `pre`）：`e155ebd` 两级重锚判据修正 + 流体云只认正文 →
`67dac37` resync base 必填可空 →
`e7ac216` 订阅幂等化（重锚 resync 永远 notOwned 的根因）→
`1523024` **ack 身份三元组 + controller 运行态**（源码审计定案，本轮主修）→
`71ed606` 编译修复 + 假桌面补 controller 契约 →
`6aaccb2` 会话流 turnHeader 作运行态兜底；此后 `764235e` 覆盖按"在跑任务"排前＝**当前 HEAD / origin/pre**。

### 目标两条的**现行**做法（第十五轮定稿；右列是被废掉的旧设计，别再退回）

| # | 用户要求 | 现行做法 | 被废掉的旧做法 |
| --- | --- | --- | --- |
| 1 | **前台**：进对话要跟手 | 壳**只读**——不关 socket、不自动重载、前台一帧都不写（「实现要点」第 14 条）。链路健康由页面自己维护，壳只记账 | 5 s 铁判准 → **nudge 关掉页面 socket** → 仍不出内容就 reload（`a73656c` `4f6bea3` `4168acd`）。真机定罪为**自伤**：40 秒里拆了 10 次，用户看到的就是"转圈/要重连好几次" |
| 2 | **后台/锁屏**：常驻接收并跟踪各对话流到流体云 | 页面**留在原地**；退后台后由前台服务每 10 s 驱动注入层心跳、补一帧顶层 `pair_status_query`（前台不补）；正文从**页面自己那条订阅流**只读取出（`_trackConversationText` → `convtext` → `applyLivePreview`） | 退后台 5 s 后**原生接管**（`aa519b2` `ca152d7` `6671de5` `aa41734` `0ede853` `48031eb`）：接管必 KICK 页面，而页面进 KICKED 是终态 → 现在 `AUTO_TAKEOVER_ENABLED = false`，只保留后台泵与页面连接 |
| 3 | 接管后页面停在 **KICKED 终态** | KICKED 是**终态**（页面自己回不来，只有手动"重新连接"），故**回前台 1.5 s 后自愈重载**——这是只读壳里唯一保留的页面级干预 | — |

**接管触发判据（一次真机事故换来的，接管停用了但判据仍然有效）**：只认「原生多久没收到注入层 liveness」。liveness 由原生泵每 10 s
用 `evaluateJavascript` 驱动，渲染器冻结/被杀即整段停摆；**链路静默不算判死**——桌面端安静不等于我们瞎了，
接管同样拿不到帧，代价却是把页面顶掉（16:05 那次误判让流体云断供一小时）。

**M4 取数通道的真机结论（照抄别再试错）**：
* `conversationRowsRangeV4`（拉取）**原生侧永远超时**（每次 20 s，带上 workspace scope、订阅建立之后也一样）
  → 已从轮询里删掉，常量留在 `RelayWire` 只作记录；
* `subscribeConversationV4`（订阅）**可用，但有顺序要求**：`{...workspaceScope, sessionId}` + 先注册
  `onDynamicConversationFrame` 监听再订阅 → ACK 带 `subscriptionId`，随即推一份 `snapshot`
  （行窗口 → `RelayWire.ConversationTail` 取"最新一行"：`assistantText.text`（超长留尾巴）/ 正在跑的 `toolCall` / 跑着的 `subagent`）；
  **但它必须发在 `subscribeSessionsIndexV4` 之前**——索引订阅先上桥之后，同一座桥上的对话订阅
  永远不回包（20:0x 实测：每 36 s 重试、连续 12 分钟全超时；18:35 成功那次纯属轮询线程抢到了正确顺序）。
  本轮最后一次提交把顺序写死：握手在 `hello/initialize` 之后同步先订对话（`runningSessionsProvider` 注入在跑会话），再订索引；
* 此后桌面端**不再推帧**（73 分钟零帧实测）→ 每 4 拍（≈48 s）**重挂订阅**（退订+重订阅）逼一份新快照，
  这是"跟手"的唯一来源。诊断行：`活进展 <key>：<文本>`、`subscribed conversation for … (帧已收 N 个)`、
  `reanchor conversation for … (距上次共收 N 个帧)`。

### 未竟（按此顺序）

1. **顺序修复（本轮最后一次提交）的真机复验**——这是本轮唯一没验完的一条，判据与步骤见上面
   「下一步（新会话第一件事）」。**关键**：别再只看 `when`；要 `android.text` 变化 + `when` 与
   系统时间对齐 + 与本刻实际动作对得上，三条同时成立才算跟手。
2. 后台长测（一直没做）：熄屏过夜，看重挂周期在系统挂起后是否仍成立（原生泵在后台是活的，
   但**熄屏后 ColorOS 是否连原生也挂起**还没测——若被挂起，Tier2 的重连会断，需要在设置里
   放开电池白名单后复测）。
3. 接管生产化收尾：接管期间通知/流体云是否标注"原生接管中"；`userAway` 是否需要额外反例（投屏/车机）。
4. 并发会话留的 `lastPairStatus` 半成品（注入层赋值但变量未声明）：补丁在 `/tmp/concurrent-pair-status.patch`。
5. 旧的未结项仍在：完成卡片未被真实完成触发过、键盘上抬未重测、08:11 瞬时现象、
   正式版 tag 命名空间 `android-v*`（待用户点头）。

### 当前状态（2026-09-13 21:00 前后，本轮收尾）

- `pre` HEAD = 本轮最后一次提交（共 11 个）；**代码那版已推送**，其余纯文档改动可能只在本地（见上）。
- 真机装 `1.0.0-pre.90`（＝`48031eb`），**不含那次订阅顺序修复**——
  复验前先等 CI 绿、下载覆盖安装（对 `.md5`）。
- 已装机并真机验过的：R1 铁判准刷新的正向路径、R3 KICKED 自愈、R2a 退后台 5 s 接管与桥覆盖。
- **未验**：修复后的对话订阅能否稳定回包、流体云是否真的跟手（判据见「下一步」）。
- **诊断指令全集**。**两条通道**：
  * **后台可调用**（`DiagReceiver`，`android.permission.DUMP` 门禁，**不碰可见性**——测后台现场只能用这条）：
    `"$ADB" -s $S shell am broadcast -n com.zcode.remote/.DiagReceiver -a com.zcode.remote.action.DIAG --es diag_cmd <cmd>`
  * 前台/老通道（会把应用拉到前台）：`am start -f 0x20000000 -n com.zcode.remote/.MainActivity -a com.zcode.remote.action.DIAG --es diag_cmd <cmd>`
  * 指令表：`vitals`｜`l1_test`（轻推 socket，只读壳下只记一行）｜`kick_test`（第二条 WS 观察 KICK）｜
    `degrade_test`｜`deadlink_test`（伪造"观测静默 120s"后走一次回前台死链判定）｜
    `passive_off` / `passive_on`（注入层观测层总开关，两条都会重载页面，**只能走前台通道**）｜
    `tier2_test`（原生配对+覆盖 60s，不动生产状态）｜`tier2_stop`｜`tier2_takeover`（90s 自动交还）｜
    `tier1_silence_test`｜
    **第十六轮新增（后台承载）**：`stall_state`（开关/是否已接管/Tier2 在跑/入站帧多久没来）｜
    `carrier_on` / `carrier_off` / `carrier_now`（开关与手动接管）｜
    `net_probe`（**原生**裸 TCP+HTTPS，不经 Chromium——墙的定性判据）｜
    `bg_state`（链路现场：`socket` readyState / `sockets[]` / `paired` / `inboundAgo` / `ackAgo` / `vis` / `fg`）｜
    `bg_http`、`bg_http|<url>`（**Chromium 侧**新建连接判据，墙内会挂住）｜
    `bg_redial`、`bg_redial:close`（合成 `online` / 关页面 socket，**仅取证**，生产逻辑不用）｜
    **造流三件（仅测试）**：`wvdebug_on` / `wvdebug_off`（开/关 WebView 远程调试 → CDP）、
    `probe_composer`（打印输入框与发送键候选）、`compose|<文本>`（**注入层自己塞字**——
    对 Lexical 受控编辑器无效，真机 00:41 现场 `填进去又被清空`，**能用的是 `tools/cdp.mjs`**）。
- 真机取数命令（本机专属）：`MSYS_NO_PATHCONV=1 "$ADB" -s 3B6F5RE8GCL3LYY7 exec-out cat …/zcode-shell.log > x.log`
  （设备端 grep 中文会碎）；流体云现状用 `python tools/notif_dump.py`（打印 title/text/`when`）。

### 工作环境（新会话必读）

- **本机现在能编译（2026-09-15 起）**：`E:\AndroidSdk` + `E:\Autopsy-4.22.1\jre` + 预置 Gradle 8.9，
  出包命令见「本机开发」；`adb install -r` 直接覆盖安装，**不消耗 CI**。CI 仍然是可选通路
  （`git push origin pre` → js 检查 + `:app:testReleaseUnitTest` + APK → 滚动预发布 `android-pre`）。
  本地出包前务必先跑门禁（下面两条），因为本地编译**同样**只报类型错误、不保证行为正确。
- **本地能跑的检查**：`node --test tools/inject.test.js tools/protocol.test.js`（94 项）、
  `python tools/check_kotlin_structure.py`
  （括号配平 / 包名 / 同文件重名——**不同嵌套类里的同名 fun 也会被点名**，改名即可）、
  `python tools/check_resources.py`；读 CI 用 `python tools/watch_ci.py`（含 `e:` 注解行；
  单次读取，退出码 0=全绿、1=失败/取消/零 job/`--watch` 超时，**`--watch` 长驻会被宿主取消**）。
- **网络**：本机到 github.com 间歇性 TLS 失败（`git push`、API、下载都要重试循环）；
  `api.github.com` 匿名限流 60/h ——优先用 `watch_ci.py`。
- **设备侧**：`MSYS_NO_PATHCONV=1` + 显式 `-s <serial>`（USB `3B6F5RE8GCL3LYY7`）；日志在
  `/sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log`；进程死因看
  `dumpsys activity exit-info com.zcode.remote`；流体云看 `dumpsys notification --noredact`。
- **端口/坐标**：无线调试端口每次都变（mDNS 现取）；点 WebView 内容前先
  `adb_ui_find`/`uiautomator dump` 拿真实 bounds，别按渲染图估（会偏约 1.4 倍）。
- **`conversationRowsRangeV4` 是死路**、**`KICKED` 是 relay 的 `error` 帧**、
  **对话行结构在 `docs/05` 快照的 `src-DHgFesxz.js`**——这三条别再重新发现一遍。
- **工作区纪律**：只 stage `android-shell` 自己的改动（`git add android-shell/app ...`）；
  并发会话会动 `entry/src/main/ets/pages/Index.ets` 与 `docs/`（提交前先看 `git diff`）。
