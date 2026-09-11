# 交接文档（给下一个对话）

> **这份文档给谁看**：在新的对话里接手 `android-shell`（安卓薄壳）并做 bug 排查的人/agent。
> **怎么读**：先读 [`README.md`](README.md)（架构、构建、签名、发版、实现要点、决策落点），
> 再读本文（当前进度、迁移清单、待排查项）。
> **本文件随项目走**（已入库），因为它是子项目自带的状态说明；父仓库的
> `安卓薄壳迁移文档.md` §12 是历史决策记录，**仅本地保留、未入库**。
>
> 撰写时间：2026-09-11（一夜连续开发结束时）

---

## 一、一句话状态

薄壳功能已全部落地并通过 CI（含 72 项自动化测试），**交付包是 `v1.0.0-pre.13`**（已发布在 Releases，可直接覆盖安装）。
**尚未有任何人在这台应用上跑过真机验证** —— 下面的「未验证项」就是你要 debug 的清单。

| 项 | 状态 |
| --- | --- |
| 分支 / HEAD | `pre` @ `eeceac1`（工作区干净，无未提交改动） |
| CI | `js`(Static checks) / `build` / `prerelease` 全绿；`release` 仅 `v*` tag 触发 |
| 交付包 | `v1.0.0-pre.13`（`zcode-remote.apk` 5.81 MB，sha256 `09a0b7ce…`） |
| 自动化测试 | JS 35 项（`node --test`）+ Kotlin 37 项（CI 的 release 单测） |
| 真机验证 | **零**。§8 第 4 步（后台 30 分钟存活取证）与全部 UI/通知效果都还没人看过 |

---

## 二、必须随项目迁移的东西（迁移清单）

这个子项目**不是自包含的**：CI 配置在父仓库根，参考文档与签名材料不在 git 里。搬走或交接时必须逐项处理。

### 1) 在 git 里（`android-shell/**`，正常 clone 即随行）

| 内容 | 说明 |
| --- | --- |
| `app/src/main/**` | 全部 Kotlin 源码、layout/drawable/values 资源、`assets/inject.js` 与 `assets/zcode-protocol.js` |
| `app/src/test/**` | 3 个 Kotlin 单测（通知判定 22 / 提升策略 7 / MIME 归一 8） |
| `tools/` | `protocol.test.js`、`inject.test.js`（共 35 项）、`fake-desktop.js`、`check_kotlin_structure.py`、`watch_ci.py` |
| `gradle/wrapper/**`、`gradlew`、`gradlew.bat` | **必须保留**（CI 靠它构建；`gradlew` 必须是 LF，`.gitattributes` 已保证） |
| `build.gradle.kts`/`settings.gradle.kts`/`gradle.properties` | 版本基准 `zcodeBaseVersion` 在这里 |
| `.gitattributes` / `.gitignore` / `key.properties.example` / `CHANGELOG.md` / `README.md` | |

### 2) ⚠️ 在父仓库根，**不在子项目目录内**（迁移最容易漏）

| 路径 | 为什么必须跟着走 |
| --- | --- |
| `.github/workflows/android-shell.yml` | 整个构建/签名/预发布流程。所有 step 都用 `working-directory: android-shell`，**路径是写死的** —— 子项目改名或换目录必须同步改 |
| `.github/scripts/android-shell-release-notes.ps1` | 正式发版时从 `android-shell/CHANGELOG.md` 取 Release 说明 |

> 若把子项目抽成独立仓库：把 `.github/` 一起搬过去，并删掉 workflow 里的 `working-directory`（或改成 `.`）、把 `paths` 过滤简化为 `**`。

### 3) ⚠️ 不在 git 里，**仅本机保留**（必须手动拷贝/备份）

| 路径 | 大小 | 内容 | 处置 |
| --- | --- | --- | --- |
| `android-shell/scratch/` | 9 KB | 签名密钥：`zcode-remote-release.p12`、`keystore-password.txt`、`keystore-base64.txt` | **务必备份**（丢弃后无法覆盖安装旧版本）。CI 从 4 个 Secret 读，本地不需要，但换机要带走 |
| `android-shell/docs/` | 32 MB | Android 官方文档快照（文件/图片选择 15 篇 + 界面规范 Material3 12 篇），本轮的规范化依据 | 手动拷贝；版权归原厂商故不入库 |
| `android-shell/ColorOS_docs/` | 87 MB | ColorOS/OPPO 文档 149 篇 + Android 官方 6 篇（流体云、泛在服务、Live Updates），**流体云路线的判断依据** | 手动拷贝；同上 |

> `docs/` 与 `ColorOS_docs/` **不在 git 里**是刻意决定（版权），但如果只带走 git 内容，下一个对话会失去：
> ① 流体云为什么选 Android 16 Live Updates 而不是 OPPO 卡片；② 界面规范的具体条文出处；③ 上传/文件选择的官方 API 依据。
> 这两个目录里各有一份 `README.md`（抓取清单 + 结论摘要），最值得先读。

### 4) GitHub 仓库侧（不在文件系统里）

- **4 个 Secrets**（必须存在于仓库设置里，否则 CI 回退 debug 签名）：
  `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_PASSWORD`（与前者相同）/ `ANDROID_KEY_ALIAS`（`zcode-remote`）
- 换仓库/换密钥时，这 4 个值必须同步更新，否则新旧包签名不一致 → 只能卸载重装。

---

## 三、CI 流程（现状即最终形态）

```
        ┌─ js     : Static checks（Node 35 项 + Kotlin 结构检查）   快，约 40 s
推 pre ─┤
        └─ build  : release 单测 37 项 + assembleRelease + 签名校验   约 2m 45s
                     └─► prerelease：覆写滚动 Release `android-pre`（固定资产名 + 移动 tag + 刷新标题/说明/时间）
推 v* tag（正式）───► build ──► release：用 CHANGELOG 段落发正式版
```

要点与坑：

- `js` 与 `build` **并行**；`build` 用**一次 Gradle 调用**同时跑单测与打包（共享 `compileReleaseKotlin`）；`actions/setup-java` 的 `cache: gradle` 恢复 `~/.gradle`；`fetch-depth: 1`。
- 版本号：`versionCode = 工作流运行号`（保证任何 CI 产物都能覆盖安装上一个），`versionName` 在 `pre` 上是 `<base>-pre.<运行号>`。基准在 `gradle.properties` 的 `zcodeBaseVersion`。
- **预发布是「滚动」的（预发布 tag 不再每次新建）**：固定在 tag `android-pre` 上，`gh release upload --clobber` 覆写 `zcode-remote.apk`/`.md5`，然后 `git push --force` 把 tag 移到本次提交，再 `gh release edit` 刷新标题与说明（说明 = 与上一次构建之间的提交列表 + 推送/编译时间）。下载链接恒定：`…/releases/download/android-pre/zcode-remote.apk`。tag 不带 `v` 前缀是刻意的（带 `v` 会命中 `tags: ['v*']` 的正式发版触发条件）；改这段时注意别丢掉这个性质。形状抄自 `E:\sevnX\.github\workflows\build.yml`。
- **`paths` 与 `paths-ignore` 不能同时用于同一事件**：GitHub 会创建一个**没有任何 job** 的 run（PyYAML 能解析，本地校验拦不住）。现在只用 `paths` 显式列出会影响 APK 的路径，纯文档改动自然不触发。
- **失败信息怎么读（重要）**：Actions 日志需鉴权（匿名 404），所以 workflow 在 Gradle 失败时会把关键错误行 grep 成 `::error::` **annotation**，而 annotation 渲染在 job 页面 HTML 里、可匿名读取。用仓库里的 `tools/watch_ci.py` 无需 gh、无需 token 即可读状态与失败原因：
  ```bash
  cd android-shell
  python tools/watch_ci.py           # 当前状态快照
  python tools/watch_ci.py --watch   # 轮询到所有 job 结束
  ```
- 预发布不会递归触发（CI 用 `GITHUB_TOKEN` 做的 tag/Release 操作不触发工作流）；`concurrency` 的 key 含 ref，推 `pre` 不会取消 `main` 的构建。

---

## 四、进度：已完成

### 功能

1. **薄壳主体**：WebView 加载 `zcode.z.ai/remote/v4`；扫码（ZXing，无 GMS）/剪贴板/手输三种接入；链接按 `https` + `*.z.ai` + `/remote` 校验后持久化。
2. **协议层**：`assets/zcode-protocol.js` 实现 relay 帧、`rpc-frame`（base64 + crc32 + 分片重组）、ChannelClient 值编解码、`RemoteClient`（被动嗅探 + 为其它工作区主动开 bridge 订阅；D7）。
3. **注入层**：`assets/inject.js` 在 document-start hook WebSocket、加可见性劫持、自带心跳与陈旧重连、上报存活计数、提供通知点击的任务定位脚本。
4. **通知**：三渠道（运行中/待确认/完成）+ 群组摘要 + 点击定位；完成判定用「上一拍运行态 → 这一拍终态」跳变，待确认按 `interactionId` 去重。
5. **保活**：`specialUse` 前台服务 + `setRendererPriorityPolicy` + 返回键不销毁进程。
6. **流体云（ColorOS 16 / Android 16 Live Updates）**：声明 `POST_PROMOTED_NOTIFICATIONS`，常驻任务通知请求提升并带状态栏芯片；只提升 2 张卡（先「等待确认」后最近活动的运行中任务）。
7. **网页上传**：补上此前完全缺失的 `WebChromeClient.onShowFileChooser` —— 修之前网页里点上传是死的；接入系统相册/文件选择器，「选择上传方式」弹窗对齐鸿蒙版。
8. **界面规范化**：颜色全部改用 M3 颜色角色 + 动态配色（Android 12+）；M3 字阶；设置页 MaterialCardView；空态/错误态补图标字阶；edge-to-edge inset 处理（含暗色下系统栏图标正确）。
9. **诊断与独立日志**：`ShellLog` 轮转文件 + 崩溃堆栈 + FileProvider 分享（不需要 adb）；设置页有存活读数与流体云资格自检。

### 自动化测试（72 项，全部在 CI 跑）

| 套件 | 数量 | 覆盖 |
| --- | --- | --- |
| `tools/protocol.test.js` | 22 | 线格式（含手算黄金字节）、分片重组、crc32、ChannelClient、会话索引快照/增量/缺口、主动订阅与被动嗅探 |
| `tools/inject.test.js` | 13 | 可见性劫持、hook 透明性（statics/instanceof/无自激）、端到端订阅、定位脚本、存活计数、凭据不入日志 |
| `NotifyStateTest.kt` | 22 | 通知判定算法（运行/终态跳变/去重/多工作区/展示文案/id 派生） |
| `PromotionPolicyTest.kt` | 7 | 提升名额分配优先级与上限 |
| `UploadMimeTest.kt` | 8 | accept 三种形态归一、去重、未知后缀不放大过滤 |

> 这些是**唯一**在无设备条件下能验证的东西。真机行为一律未验证。

---

## 五、未验证项（你要 debug 的清单，按优先级）

### P0：整条路线的存活前提（迁移文档 §8 第 4 步）

装 `pre.13` → 扫码接入 → 按返回键退后台 **30 分钟** → 回前台进 **设置 → 诊断**，读第一行：

```
后台存活检查：时长 30 分 4 秒，期间收到 214 帧、配对确认 178 次 → 保活成立（链路有应答）
```

判读口径与取证方式见 README「首次真机验证」一节。**若这行结论不好，后面所有通知代码都建立在死连接上，应先修它。**

### P1：流体云是否真的出现

- **前置条件：手机需 Android 16 / API 36 及以上**。低于 36 时提升代码是惰性的，诊断页会写 `流体云: 本机为 API xx，需 Android 16(API 36) 及以上`。
- 排查线索：设置 → 诊断里的 `流体云: 可用/系统已关闭本应用的推广通知`（`LiveUpdate.describeEligibility` 输出，含 10 项条件自检）。若显示不可用，先看系统设置里该应用的「推广通知」开关。
- 预期现象：任务运行/等待确认时，状态栏出现带 `运行中` / `等待确认` 芯片的实时活动卡片（最多 2 张）。
- **已知限制**：提升出的卡片**无法手动划掉**（ongoing），要等任务结束。若体验不佳，可加一个「隐藏」操作（`Notifier` 里加 action + 一个 `BroadcastReceiver` + 一个 hidden id 集合，约 30 行）。

### P2：界面观感与规范落地

- 动态配色 + 暗色模式：切系统深色看两个 Activity、弹窗、设置页是否都正确（重点看设置页卡片与分隔线对比度、系统栏图标是否可见）。
- 上传弹窗的尺寸指标（16dp 圆角/24dp 内边距/磁贴 14dp 与 12dp 间距，对齐鸿蒙）在真机上的观感；要纯 M3 就把圆角改 28dp。
- edge-to-edge：顶部工具栏与底部内容是否都在安全区内（本轮修过一次：状态栏遮挡 + 底部偏移）。

### P3：上传链路

- 相册（Photo Picker，上限 5）与文件（SAF）两条路径在具体 ROM 上是否都能打开并正确回传；取消时网页的 input 是否正常结束（不卡住）。
- `accept` 为空的页面应能选任意文件（与桌面浏览器一致）。
- **未实现**：`MODE_SAVE`（网页请求保存文件）返回 `false`，日志有 warn。

### P4：通知细节

- 常驻通知的标题/正文/分组是否符合 D8/D9；点击是否定位到该任务（按标题匹配，页面改版即失效，会安静降级为「仅打开应用」）。
- 完成通知有无声音/震动（D10）；待确认是否既更新常驻又补发有声提醒。

---

## 六、待你决策的一件事

**正式发版的 tag 命名空间撞车**：`v1.0.0` 这个 tag 已被**鸿蒙版**的 Release 占用（资产是 `entry-default-unsigned.hap`）。同一仓库两个应用共享 tag 空间，而安卓 workflow 的正式发版触发条件是 `v*` ——
- 若你给鸿蒙版打 `v1.0.1`，会**误触发安卓构建**并在那个 tag 上发布安卓 APK；
- 安卓正式发版也应避开 `v1.0.0` 这类已被占用的号。

> 日常预发布侧已经没有这个问题：滚动 Release 用 `android-pre`（不带 `v`），与 `v*` 互不触发。

建议把安卓侧的**正式**发版也改成独立命名空间（workflow 里 `tags: ['v*']` → `['android-v*']`，并在 README/CHANGELOG 写明 `git tag android-v1.0.0`）。我**没有擅自改**，因为这会改变你的发版习惯，需要你点头。

---

## 七、两个已踩过的坑（别再踩）

1. **`UploadMime.kt` 的通配符常量不要简化成单个字面量。** 写成单个字面量时 `compileReleaseKotlin` 报 `Syntax error: Expecting a top level declaration`，位置精确落在那两字符列（28:26-29），连续三轮 CI 报错逐字节相同；而文件字节是干净的纯 ASCII、括号配平、内容合法。Kotlin 块注释可嵌套，嫌疑是词法器在注释深度上失手。现用 `"*" + "/" + "*"` 拼接（仍是编译期常量）绕过，单测同款字面量也已改为拼接。**无法本地复现，改动前请先跑一次 CI。**
2. **`paths` 与 `paths-ignore` 不能同时用于同一事件**（见上文 CI 要点）。症状是 run 被创建但**没有任何 job**。

---

## 八、本地命令速查（本机不装 JDK/Android SDK，D13）

```bash
cd android-shell

# 协议层 + 注入层测试（35 项，约 30 秒）
node --test

# Kotlin 结构检查（括号配平/包名与目录一致/合并残留，约 1 秒）
python tools/check_kotlin_structure.py

# CI 状态与失败原因（无需 gh / token）
python tools/watch_ci.py [--watch]

# 提交（注意：绝不用 git add -A —— 仓库里有 docs/ 与 ColorOS_docs/ 等不入库的大目录）
git add <明确路径> && git commit && git push origin pre
```

Kotlin 编译**只能靠 CI**：推送后在 Actions 页面或 `watch_ci.py` 看 annotation。

---

## 九、提交地图（便于定位与回滚）

`pre` 分支（新→旧）：

| 提交 | 内容 |
| --- | --- |
| `eeceac1` | 修 workflow 非法过滤组合（paths + paths-ignore） |
| `6fe82ee` | README 补三节（流体云/界面规范/上传）+ CI 路径过滤 |
| `a6bae3f` | **修 UploadMime 编译失败**（消除触发序列） |
| `091b6ca` | **界面按 M3 规范梳理**（颜色角色/动态配色/暗色） |
| `2820360` | **接入流体云**（LiveUpdate + PromotionPolicy + 权限） |
| `08f7aed` | **网页文件上传**接入系统选择器 |
| `eed3263` | docs/ 移出仓库跟踪 |
| `2f60926` | 修 `enableEdgeToEdge` 的 receiver 类型 |
| `dcfdd5c` | 修 targetSdk 35 edge-to-edge 的遮挡与视口偏移 |
| `07a3115` | 发布流程改为「推 pre 自动出预发布 Release」 |
| `ccede01` | 修首轮两处编译错误（BuildConfig / setRendererPriorityPolicy） |
| `076e068` | **CI 失败时把 Gradle 关键错误提升为 annotation**（这是"无需 gh 就能读失败原因"的来源） |
| `e0efe6d` | 子项目初版（骨架 + 协议层 + 通知 + 保活 + CI） |

五个功能块各自独立成提交，可单独 revert 而不影响其余（回滚后推 `pre` 会再发一个预发布包）。

---

## 十、背景文档索引

| 文档 | 位置 | 是否入库 |
| --- | --- | --- |
| 本子项目 README（架构/构建/签名/发版/实现要点/决策） | `android-shell/README.md` | ✅ |
| 变更记录（用户可见变更） | `android-shell/CHANGELOG.md` | ✅ |
| 鸿蒙→安卓的迁移决策 + 历史回填（§12 含本轮各项） | `../安卓薄壳迁移文档.md` | ❌ 本地保留 |
| 鸿蒙侧需求与决策历史 | `../项目文档.md` | ❌ 本地保留 |
| ColorOS/OPPO + Android Live Updates 官方依据 | `android-shell/ColorOS_docs/`（先读其 `README.md`） | ❌ 本地保留，87 MB |
| Android 文件/图片选择 + Material3 界面规范 | `android-shell/docs/`（先读其 `README.md`） | ❌ 本地保留，32 MB |
| 签名密钥与 Secrets 步骤 | `../zemote/独立项目文档.md` §1-2（另一把密钥的流程可参考） | ❌ 本地保留 |

---

## 十一、真机第一轮反馈（一加 PLC110 / API 36 / WebView 153）

有人在 `pre.13` 上真跑了一轮，报了三件事。处理状态与**下一轮怎么接着做**：

| # | 现象 | 状态 |
| --- | --- | --- |
| 1 | 键盘弹出，页面输入框不随之上抬 | **已修**（`core/WindowInsets.kt` → `padForSystemBarsAndIme()`） |
| 2 | ColorOS 弹「“ZCode 远程”正在当前页面悬浮显示，可能造成部分操作无响应，是否关闭该应用？」 | **未定位**，见下文 2 |
| 3 | 任务对话加载很慢，连标题都要半天 | **已加取证**，未定论，见下文 3 |

### 1) 输入法（已修）
`decorFitsSystemWindows=false` 之后 `adjustResize` 不再改变窗口大小，键盘只以 `Type.ime()` inset 送达，而根布局此前只消费 systemBars/displayCutout。现在底部内边距取 `max(系统栏, 键盘)`，WebView 变矮 → `innerHeight` 下降 → 贴底输入框上抬。真机验证：点开会话底部输入框，输入框应贴在键盘上方；注入层的 `视口 … innerHeight=…` 行（resize 后 300ms 上报）可用来核对数值是否真的变了。

### 2) ColorOS「悬浮显示」弹窗（待取证，优先做）
已知事实：
- 这是 ColorOS 的**悬浮窗/叠加层保护**，不是我们的崩溃或 ANR。社区反馈里它常在**退出应用时**出现（例：`bbs.tatans.cn/topic/122248` 豆包/QQ 同类现象），按「关掉该应用的悬浮窗权限」即不再弹。
- 我们的源码与清单**没有** `SYSTEM_ALERT_WINDOW`，也没有 `TYPE_APPLICATION_OVERLAY` / `TYPE_TOAST` 窗口；`KeepAliveService` 只发通知不开窗。所以它要么来自依赖库往清单里合并了权限，要么是系统把流体云提升出来的胶囊/卡片算在应用头上，要么与我们无关（用户把它拖成了自由浮窗）。
- 下一步（有 adb 后 5 分钟能定性）：
  ```bash
  adb shell dumpsys window windows | grep -i -A3 zcode   # 有没有 TYPE_APPLICATION_OVERLAY 之类的窗口
  adb shell appops get com.zcode.remote                    # SYSTEM_ALERT_WINDOW 是否被开启
  adb shell dumpsys notification --noredact | grep -i -A5 zcode   # 是否有提升(promoted)的通知在显示
  adb shell dumpsys activity activities | grep -i zcode    # 是否被系统置于浮窗/分屏
  ```
  若确认是流体云提升触发，取舍是：只在「等待确认」时提升（运行中不提升），或去掉提升；`LiveUpdate.requestPromotion` 是唯一开关点。

### 3) 会话加载慢（已加取证，待判读）
本轮新增的判读顺序（日志里都已出现，无需再改代码）：
1. `网页开始加载` / `网页加载完成，用时 N ms`（原生）与 `页面加载计时：ttfb=… load=… 资源 N 个 / KB`（注入层导航计时）→ 网络/文档层面的耗时；
2. `active subscribe start（页面已空闲 Xms）` → 我们的 bridge 何时开始握手（本轮已改为等页面静默 ≥800ms，上限 12s，见下）；
3. `页面开销 10s：收帧 N 个（解码合计 Yms，单帧最长 Zms）· 长任务 …` → **我们自己的主线程开销**；
4. `[web:行号] …` → 页面自己的 console（新增，错误/警告分级）。

若 1 小、3 大 → 是我们的解码/订阅挤占主线程，继续降载（例如限制并发 bridge 数、跳过大快照）；若 1 大 → 网络或 relay/桌面侧，往协议层加 RPC 往返计时。

本轮已改：主动订阅不再在配对后 1.5s 无条件启动（原来 7 个工作区约 10s 连续握手，正好压在页面首屏加载窗口里），改为「最后一次收帧静默 ≥800ms 才启动，最迟推迟 12s」。若下一轮日志显示这仍不够，再考虑限制并发/分批。

### 本机 adb（**已沉淀到 README**：见「本机开发 → 真机调试（adb）」）
要点速记，细则（命令、配对步骤、dumpsys/appops 用法）不再在本文档重复：
- 本机**有可用 adb**：`C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe`（Platform-Tools **36.0.0**，推荐）、`E:\leidian\LDPlayer9\adb.exe`（34.0.4，备用）。
- 一加手机走**无线调试**（近期 IP `192.168.0.185`，会变）；Android 11+ **首次必须配对** —— 端口 TCP 通但 `adb connect` 报 `failed to connect` 就是这个特征，需先用手机上的配对码 `adb pair` 一次。
- 配对码是**一次性凭据**：不要写进任何文件、日志或提交。
- 配对成功后闭环成立：CI 出包 → `adb install -r` → `adb shell cat .../files/logs/zcode-shell.log` 直接取诊断日志。

> 注意：本机还有一台**鸿蒙**测试机（hdc 可达 `192.168.0.82:12345`，HBN-AL80/API 24），与本子项目无关，别弄混。
