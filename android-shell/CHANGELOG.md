# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发版流程：改动 → 更新本文件顶部段落 → `git tag vX.Y.Z` → 推送。CI 会读取 `## [X.Y.Z]` 段落作为 Release 说明。

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

### Notes
- 单元测试 35 项（JS 协议层与注入层）+ 20 项（Kotlin 通知判定算法），全部在 CI 运行。
- 仅中文界面；`minSdk 26`，`compileSdk/targetSdk 35`；纯 Kotlin/Java 无 native 库，单一通用 APK。
- 首次提交前无法在本机构建（D13），构建结果以 CI 为准。
