# android-shell — ZCode 远程（安卓薄壳）

> **接手排查请先读 [`HANDOVER.md`](HANDOVER.md)**：当前进度、必须随项目迁移的文件清单（CI 配置在父仓库根、
> 参考文档与签名材料不在 git 里）、未验证项清单、待决策项、提交地图。

把上层鸿蒙工程「ZCode 远程」的薄壳思路搬到安卓：**WebView 加载 `zcode.z.ai/remote/v4` + 原生对接系统能力**，并补上鸿蒙版没有的**任务通知**与**后台保活**。

- 包名 / 应用名：`com.zcode.remote` / 「ZCode 远程」
- 技术栈：原生 Kotlin + AndroidX（不用 Flutter），`minSdk 26` / `targetSdk 35`
- 编译：**完全依赖 GitHub Actions**，本机不装 JDK / Android SDK

设计与决策记录见上层文档 [`../安卓薄壳迁移文档.md`](../安卓薄壳迁移文档.md)。

---

## 一次构建要多快

工作流按墙钟时间排布，`js` 与 `build` 是两个并行 job：

| job | 内容 | 首次 | 有缓存 |
| --- | --- | --- | --- |
| `js` | Node 协议层 + 注入层测试（35 项），不需要 JDK/SDK | ~1 min | ~40 s |
| `build` | 单次 Gradle 调用：release 单元测试（20 项）+ `assembleRelease` + 签名校验 | ~4 min | ~2m 45s |
| `prerelease` | 仅 `pre` 分支：自动打 tag 并发**预发布 Release**（可直接下载安装） | ~20 s | ~20 s |
| `release` | 仅 `v*` tag：用 CHANGELOG 段落发正式 Release | ~20 s | ~20 s |

省时间的几个点：`js` 不与 Android 构建串行；`testReleaseUnitTest` 与 `assembleRelease` 放在**同一次 Gradle 调用**里（共享 `compileReleaseKotlin`，源码只编译一次、Gradle 只启动一次）；`fetch-depth: 1`；`actions/setup-java` 的 `cache: gradle` 会恢复 `~/.gradle`（依赖缓存 + 本地 build cache）；`org.gradle.configuration-cache=true` 且 `problems=warn`，所以配置缓存只可能加速、不会让构建失败。

---

## 发布流程：日常走 `pre`，正式版从 `main` 打 tag

```
main ──────────────────────────────────● v1.0.0  正式 Release
        ╲                              ╱  （你决定何时把 pre 合入）
   pre ──●──●──●──●
           │  │  └─ v1.0.0-pre.<run>  预发布（自动，可直接安装）
           │  └──── v1.0.0-pre.<run>
           └─────── v1.0.0-pre.<run>
```

- **日常开发只推 `pre`**。每次推送到 `pre`，CI 通过后自动创建 tag `v<base>-pre.<运行号>` 并发布 **pre-release**，APK 挂在 Releases 页面上——装测试包不用再去翻 Actions 的 Artifacts。
- **正式发布**：更新 `CHANGELOG.md` 顶部段落（必须是 `## [X.Y.Z]`，且与 `gradle.properties` 的 `zcodeBaseVersion` 一致）→ 把 `pre` 合入 `main` → 在 `main` 上 `git tag vX.Y.Z` → 推送。CI 会用 changelog 段落作为 Release 说明。
- CI 用 GITHUB_TOKEN 创建的 tag **不会**再触发一次工作流，所以预发布不会递归。
- `concurrency` 的 key 含 ref，所以推 `pre` 不会取消 `main` 上正在跑的构建。

### 版本号的唯一来源与覆盖安装

| 位置 | 内容 | 谁读它 |
| --- | --- | --- |
| `gradle.properties` 的 `zcodeBaseVersion` | 版本基准，如 `1.0.0` | Gradle 与 CI 共同读取 |
| `CHANGELOG.md` 顶部 `## [X.Y.Z]` | 正式版的 Release 说明 | 发 tag 时的 notes 脚本 |

`versionName` 由 CI 注入（`pre` 上是 `1.0.0-pre.<run>`，其它分支是基准值）；**`versionCode` 在所有 CI 构建里都取工作流运行号**。这一条是刻意的：Android 拒绝安装 versionCode 低于已装版本的 APK，所以固定 versionCode 会导致「正式版装上之后，再也装不上 pre 包」。用运行号保证单调递增，任何一次 CI 产物都能直接覆盖安装上一个。

---

## 取 APK

**推荐（pre 分支）**：推送后打开仓库 **Releases** 页面，下载最新的 `pre-release` 里的 `zcode-remote.apk`。

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

## 首次真机验证：迁移文档 §8 第 4 步（关键里程碑）

这一步决定整条薄壳路线是否成立，因此把它做成了一次可读出结论的测量，而不是靠感觉：

1. 安装 APK，扫码接入（桌面端 ZCode → 远程控制 → 显示二维码）。
2. 停留到页面加载完成，确认通知权限弹窗出现并允许。
3. 打开 **设置 → 诊断（真机调试）**，确认「后台存活检查」显示 `已配对`，且「累计帧数」在增长。
4. 按返回键退到后台（**不要**在最近任务里划掉），等 **30 分钟**——期间最好让桌面端至少跑一个任务，但即使没有任务也在生效：注入层每 10 秒一次心跳，桌面端的配对应答就是链路存活的证据。
5. 回到应用，打开 **设置 → 诊断**，读第一行：

```
后台存活检查：时长 30 分 4 秒，期间收到 214 帧、配对确认 178 次 → 保活成立（链路有应答）
```

判读方式：

| 结论 | 含义 |
| --- | --- |
| `保活成立（链路有应答）` | 整条路线成立，通知有真实数据来源 |
| `有数据但无配对确认，心跳可能被节流` | 连接还在，但渲染进程被限流；仍可用，心跳可能变稀疏 |
| `后台期间未收到任何帧，连接很可能已断` | §5.5 的残留风险成真，需要考虑 1px overlay 之类的兜底 |

同一行的下方还有「保留服务 / 注入脚本 / 工作区数量 / 日志文件路径」。要完整日志就点 **分享 / 导出日志文件**——日志写在应用外部存储目录并带崩溃堆栈，进程被杀也留得下，不需要电脑。

---

## 实现要点（改代码前先读）

1. **document-start 注入是硬性前提。** `WebViewCompat.addDocumentStartJavaScript` 必须在 `loadUrl` 之前装好，晚了就漏掉页面的首个 WebSocket 连接。系统 WebView 不支持时会回退到 `onPageStarted` 并在日志里警告「可能漏首帧」。
2. **协议层比预期深一层。** 通知要的 `sessions-index` 不在明文的 relay 载荷里，而是包在 `rpc-frame`（base64 + crc32 + 分片）里的 ChannelClient 值流；`assets/zcode-protocol.js` 实现了这一层，并有 35 项 Node 测试钉住线格式（含手算的黄金字节）。
3. **不要调用 `webView.onPause()`。** 它会挂起 WebView 的定时器，正好掐掉页面的 relay 心跳。
4. **返回键不销毁进程**，`moveTaskToBack(true)` 退到后台，保住连接。
5. **`_bridges` 与 `_bridgesById` 是两个索引**：前者按工作区键（生命周期/状态），后者按 `bridgeSessionId`（入站帧路由）。混用会让所有响应被静默丢弃——这个 bug 已被 Node 测试抓到过一次。
6. **可见性劫持不等于免于节流。** 它只让页面「以为自己可见」，从而不主动暂停；真正的保活靠前台服务 + renderer priority。心跳与陈旧重连是这一点的补充。
7. **主动订阅（D7）会带来一处副作用**：我们为其它工作区开的 bridge，其 rpc-frame 会被页面当成未知 bridge 缓存（参考实现的 `_pendingBridgePayloads`），长时间会有内存增长。设置里的「订阅所有工作区」开关可随时退回纯被动模式；每条连接的帧量很小，实测可接受。
8. **凭据不外泄。** 远程链接的 `sid/hash/mid` 严禁进日志/文档/输出；`Diagnostics.redact` 会剥掉 `/remote` 之后的查询串，`device_sid` 只学不用、绝不记录。
9. **颜色一律用 M3 颜色角色**（`?attr/colorSurface` 等），不要新增固定色值。固定浅色会让暗色模式从构造上就是坏的——这正是本轮修掉的问题（见"界面规范"一节）。新增界面元素时也请用 M3 字阶（`?attr/textAppearance*`）而不是手写 sp。
10. **`UploadMime.kt` 里的 `WILDCARD` 是拼接出来的，不要"顺手简化"成单个字面量。** 写成单个字面量时 `compileReleaseKotlin` 会在该列报 `Syntax error: Expecting a top level declaration`，连续三轮 CI 复现、报错逐字节相同，而文件字节是干净的纯 ASCII。Kotlin 的块注释可嵌套，嫌疑是词法器在注释深度上失手；拼接写法语义完全相同且已验证可编译。
11. **改完 Kotlin 先跑 `python tools/check_kotlin_structure.py`**：括号配平、包名与目录一致、合并残留，一秒出结果；CI 的 Static checks job 也会跑它。

---

## 界面规范（Material 3）

本轮把界面从「手写固定浅色」改成按 M3 规范：

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

## 决策落点（迁移文档 §11.3 留给新对话的问题）

| 问题 | 结论 | 理由 |
| --- | --- | --- |
| 扫码方式 | **ZXing（journeyapps embedded）** + 剪贴板粘贴 + 手动输入 | ZXing 不依赖 Google Play Services，国产 ROM 可用；三种入口互为兜底 |
| 设置页项 | 当前链接、换链接/重新加载、通知权限、清除通知、电池优化白名单、订阅所有工作区开关、诊断日志（查看/复制/分享）、版本 | 只保留能影响「通知能不能到」和「调试能不能做」的项；「仅 Wi-Fi 保活」「通知开关」价值低未做 |
| 语言 | 仅中文 | 与鸿蒙版一致 |
| minSdk | **26（Android 8.0）** | 通知渠道是 API 26+ 才有的概念，再低要为渠道写一套降级分支；26 覆盖已足够 |
| ABI 拆分 | 不做，单一通用 APK | 纯 Kotlin 无 native 库 |
| 应用内更新检测 | 第一版不做 | 壳的原生代码变更频率低，手动装 Release 即可 |

---

## 本机开发

本机不安装 JDK / Android SDK，**不要尝试本地构建**。可本地运行的只有协议层测试：

```bash
cd android-shell
node --test          # 35 项：线格式、分片重组、通道客户端、会话索引、注入层
```

其余一切以 CI 为准。改完 Kotlin 直接在日志里看编译错误——这也是为什么 CI 用 `--stacktrace`。

## 目录

```
android-shell/
├── app/src/main/
│   ├── assets/
│   │   ├── zcode-protocol.js      # 线协议：值编解码、rpc-frame、ChannelClient、RemoteClient
│   │   └── inject.js              # document-start 注入：WS hook、可见性劫持、心跳、任务定位
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
└── tools/                         # Node 测试（协议层、注入层、假桌面）
```
