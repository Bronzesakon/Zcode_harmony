/**
 * inject.js — document-start script installed into the ZCode remote page.
 *
 * Responsibilities, in order of importance:
 *   1. Hook WebSocket so we see every relay frame the page sends and receives.
 *   2. Decode the page's own sessions-index stream (passive: no protocol writes).
 *   3. Open our own workspace bridges and subscribe for the workspaces the page
 *      is not showing, so tasks keep notifying while the user looks elsewhere.
 *   4. Keep the page convinced it is visible, so it does not pause itself when
 *      the app goes to the background.
 *   5. Expose __zcodeShellLocateTask() for notification taps.
 *   6. Report which visual state the page is in (boot / control view, narrow or
 *      wide, light or dark) so the native strip behind the status bar can take
 *      the matching fixed surface colour. Names only, never colours.
 *   7. Stand in for the page's scrollbar: zero its space-taking rail and draw an
 *      overlay bar, so the page's content is centred and the position indicator
 *      survives (Android WebView will not render overlay scrollbars itself).
 *   8. Stall watchdog for the conversation view: on entry beacons, page-log
 *      failure events and a heartbeat patrol, a 3s decision ladder runs —
 *      nudge first (close the shared relay socket so the page runs its own
 *      reconnect/resubscribe ladder), reload only if still stuck, and give up
 *      after 2 consecutive reloads until a recovery signal lands.
 *   9. Sink the page's own logs: the production page reports every lifecycle
 *      event (subscribe/store/recovery) solely to `window.zcode?.log`, which
 *      nobody provided — here it becomes native log lines ("页面: …").
 *
 * Installed via WebViewCompat.addDocumentStartJavaScript, i.e. BEFORE any page
 * script runs. That timing is mandatory: the page opens its WebSocket during
 * boot, and a hook installed later would miss the connection and the auth
 * handshake.
 *
 * Everything here is defensive. This script runs inside someone else's
 * production app shell: a thrown error could break their UI, so every entry
 * point is wrapped, and every failure degrades to "notify a bit less" rather
 * than "page is broken".
 */
(function () {
    'use strict';

    var G = typeof globalThis !== 'undefined' ? globalThis : window;
    if (G.__zcodeShellInstalled) {
        // Idempotent: addDocumentStartJavaScript can be installed more than
        // once across navigations in some WebView versions.
        return;
    }
    G.__zcodeShellInstalled = true;

    // 注入时机的取证行：document-start 跑进来时 document 必然还是 'loading'。
    // 若不是，说明这一次加载 document-start 没有生效（原生侧的 onPageStarted/
    // onPageFinished 补注接住了它）——根因线索直接进日志。
    if (typeof document !== 'undefined' && document.readyState !== 'loading') {
        var lateNote = '注入未在 document-start 生效，由加载期补注；页面现有连接将从原型层收编' +
            '（收编前的入站帧缺失，最长约一个心跳周期）';
        try {
            if (G.ZCodeShell && typeof G.ZCodeShell.postMessage === 'function') {
                G.ZCodeShell.postMessage(JSON.stringify(
                    {event: 'diag', data: {level: 'warn', message: lateNote}}));
            }
        } catch (e) {}
    }

    var P = G.ZcodeProtocol;
    var bridge = G.ZCodeShell || null;

    // -----------------------------------------------------------------------
    // native bridge
    // -----------------------------------------------------------------------
    function post(event, data) {
        if (!bridge || typeof bridge.postMessage !== 'function') {
            return;
        }
        try {
            bridge.postMessage(JSON.stringify({event: event, data: data}));
        } catch (e) {
            // Never let a bridge failure escape into the page.
        }
    }

    function diag(level, message) {
        post('diag', {level: level, message: message});
    }

    // -----------------------------------------------------------------------
    // 0. page log sink（window.zcode.log）
    //
    // 生产构建里，页面自己的日志出口只有一个：logger chunk 把 J.info/warn/error
    // 与 J.lifecycle.* 全部（且仅）投给 `window.zcode?.log?.(level, args)`，
    // console 在 PROD 被短路；而 WebView 里 window.zcode 原本不存在，所以页面
    // 对「我断了 / 我在重试」的全部自述一直在静默丢弃（docs/05 审计，2026-09-12）。
    // 这里供给一个最小 sink：把 (level, args) 原样转成 pagelog 消息交给原生日志。
    // 快照审计证实全 bundle 对 window.zcode 只有这一处可选调用，定义 {log} 无
    // 副作用；已存在时绝不覆盖。debug 级页面本来就不外发，无需过滤。
    // -----------------------------------------------------------------------
    var pagelogSeen = false;
    var pagelogWindowCount = 0;
    var PAGE_LOG_WINDOW_MAX = 60;

    function describeLogArg(value) {
        if (value === null || value === undefined) {
            return String(value);
        }
        var type = typeof value;
        if (type === 'string') {
            return value.length > 400 ? value.substring(0, 400) + '…' : value;
        }
        if (type === 'number' || type === 'boolean') {
            return String(value);
        }
        try {
            var seen = [];
            var text = JSON.stringify(value, function (key, val) {
                if (val && typeof val === 'object') {
                    if (seen.indexOf(val) >= 0) {
                        return '[circular]';
                    }
                    seen.push(val);
                    if (seen.length > 8) {
                        return '[deep]';
                    }
                }
                return val;
            });
            if (typeof text === 'string') {
                return text.length > 400 ? text.substring(0, 400) + '…' : text;
            }
        } catch (e) {
            // fall through to String()
        }
        return String(value);
    }

    /** 页面日志里的机器可读事件名（args 里带 {event: …} 的那一项）。 */
    function pageLogEventName(args) {
        if (!args || typeof args.length !== 'number') {
            return null;
        }
        for (var i = 0; i < args.length; i += 1) {
            var a = args[i];
            if (a && typeof a === 'object' && typeof a.event === 'string') {
                return a.event;
            }
        }
        return null;
    }

    function installPageLogSink() {
        try {
            if (G.zcode && typeof G.zcode.log === 'function') {
                return;
            }
            if (!G.zcode || typeof G.zcode !== 'object') {
                G.zcode = {};
            }
            G.zcode.log = function (level, args) {
                try {
                    pagelogWindowCount += 1;
                    if (pagelogWindowCount > PAGE_LOG_WINDOW_MAX) {
                        return; // 洪峰保护：计数每个心跳窗口清零（heartbeatTick）
                    }
                    if (!pagelogSeen) {
                        pagelogSeen = true;
                        if (client) {
                            client.suppressPageRpcMirror = true;
                        }
                        diag('info', '页面日志汇已接通（window.zcode.log）');
                    }
                    var list = [];
                    if (args && typeof args.length === 'number') {
                        for (var i = 0; i < args.length && i < 6; i += 1) {
                            list.push(describeLogArg(args[i]));
                        }
                    }
                    var event = pageLogEventName(args);
                    if (event) {
                        notePageLogEvent(event);
                    }
                    post('pagelog', {level: String(level || 'info'), args: list});
                } catch (e) {
                    // 日志汇绝不能反过来打断页面
                }
            };
        } catch (e) {
            diag('warn', '页面日志汇安装失败: ' + e);
        }
    }
    installPageLogSink();

    if (!P) {
        diag('error', 'zcode-protocol.js 未加载，注入层无法工作');
        return;
    }

    /**
     * 读不到原生配置时的兜底——**必须与 Prefs 的真实默认值一致**。
     *
     * 2026-09-17：D7「订阅所有工作区」删除后这里不再有 `subscribeAll`——注入层**恒被动**
     * （只读壳契约，见 SHELL_READ_ONLY）。兜底里如果残留一个"开订阅"的字段，就等于给
     * 只读契约留了个后门（2026-09-15 就是这么踩的）。
     */
    function configDefaults() {
        return {passiveObserve: true};
    }

    function config() {
        if (!bridge || typeof bridge.config !== 'function') {
            return configDefaults();
        }
        try {
            var raw = bridge.config();
            var parsed = raw ? JSON.parse(raw) : null;
            return parsed && typeof parsed === 'object' ? parsed : configDefaults();
        } catch (e) {
            return configDefaults();
        }
    }

    // -----------------------------------------------------------------------
    // 1. visibility hijack (decision D12) —— **已停用**（2026-09-15 晚）
    //
    // The page decides whether to keep its relay work running from the Page
    // Visibility API. Spoofing it keeps the page in "foreground" mode while the
    // app is backgrounded. This does NOT stop the browser from throttling
    // timers — only the foreground service and renderer priority do that — but
    // it stops the page from deliberately pausing itself.
    //
    // ⚠️ 上面这段是它当初的立论，真机把它两头都否掉了（2026-09-15 晚的证据）：
    //
    //   * **它挡不住节流**：Chromium 的定时器节流与冻结看的是**真实**可见性，
    //     不看这个 JS API。真机 22:08–22:15 的后台 7 分钟里，劫持全程生效，
    //     而页面自己那条 10 秒心跳只跳了 6 次（被压到约 1 次/分钟）。
    //   * **它掐掉了页面的自救**：页面自己的传输层有 suspend→recover 逻辑
    //     （bundle 里监听 `visibilitychange`/`pagehide`/`freeze`），我们把
    //     window/document 上的这些事件全吞掉、还把 `visibilityState` 谎报成
    //     visible——于是页面既不知道自己去过后台，也不知道自己回来了。
    //     真机 22:15:26 的现场就是它的后果：回前台后页面自己重连成功
    //     （1006 → 1 秒内 open），但重新订阅一直没被 ack，对话详情一直 0 行，
    //     只有重启应用（全新 bootstrap）才恢复。
    //
    // 结论：**不再对页面说假话**。要让页面在后台继续工作，得从 Android 侧解决
    // "窗口可见性"（PiP / 覆盖窗 / 原生承载），JS 层这个谎是白撒的。
    // 2026-09-16 审计：历史实现（谎报 visible + 吞掉 lifecycle 事件）已删除——
    // 它只会让页面既不知道自己去过后台、也不知道自己回来了，回前台的订阅永远
    // 拿不到 ack。这里只保留一行日志：它本身就是"壳没有在骗页面"的诊断量。
    // -----------------------------------------------------------------------
    function installVisibilityHijack() {
        diag('info', '可见性劫持已停用：页面拿到真实可见性，生命周期事件照常送达');
    }

    // -----------------------------------------------------------------------
    // 2. WebSocket hook
    // -----------------------------------------------------------------------
    var NativeWebSocket = G.WebSocket;
    var injecting = false;
    var sockets = [];
    var activeSocket = null;
    var deviceSid = null;
    // KICK 实验素材（Tier2 可行性验证用）：页面建线用的 URL 与 auth_init 帧
    // 原文。两者都是会话凭证级内容，只留内存、绝不进日志（safePath 只留路径）。
    var lastRelayUrl = null;
    var lastAuthInitText = null;
    // Tier2（原生直连 relay）凭证移交：URL + sid/hash/mid，一次性 post 给原生，
    // 原生仅驻内存。凭证参数名与页面一致（sid=deviceSid, hash=passHash, mid=deviceMid）。
    var relayCredsPosted = false;

    function maybePostRelayCreds() {
        if (relayCredsPosted || !lastRelayUrl) {
            return;
        }
        var query;
        try {
            query = new URL(G.location.href).searchParams;
        } catch (e) {
            return;
        }
        var sid = query.get('sid') || '';
        var hash = query.get('hash') || '';
        var mid = query.get('mid') || '';
        if (!sid || !hash) {
            return;
        }
        relayCredsPosted = true;
        post('relaycreds', {
            url: lastRelayUrl,
            deviceSid: sid,
            passHash: hash,
            deviceMid: mid
        });
        diag('info', 'Tier2: relay 凭证已移交原生（仅内存，不落日志）');
    }

    function installWebSocketHook() {
        // **被动旁观总开关**（`diag_cmd passive_off`，见 Prefs.passiveObserve 的注释）。
        // 关掉时这里直接返回：不 hook WebSocket ⇒ 不建 socket 索引、不逐帧观测、不建协议
        // 客户端、不发心跳探针。留下的只有"零侵入三件事"（滚动条 CSS / 状态页面上报 /
        // 页面日志汇），用来判定"是不是我们对每帧的同步处理拖死了页面"。
        if (config().passiveObserve === false) {
            diag('warn', '被动旁观已关闭（诊断开关 active=false）：跳过 WebSocket hook 与全部协议观测');
            return;
        }
        if (typeof NativeWebSocket !== 'function') {
            diag('error', 'window.WebSocket 不存在，无法 hook');
            return;
        }

        // Constructor wrapper: keeps statics and prototype intact so the page's
        // own instanceof checks and OPEN/CONNECTING constants keep working.
        function ShellWebSocket(url, protocols) {
            var socket = protocols === undefined ?
                new NativeWebSocket(url) :
                new NativeWebSocket(url, protocols);
            trackSocket(socket, url);
            return socket;
        }
        Object.setPrototypeOf(ShellWebSocket, NativeWebSocket);
        ShellWebSocket.prototype = NativeWebSocket.prototype;
        try {
            Object.defineProperty(ShellWebSocket, 'name', {value: 'WebSocket'});
        } catch (e) {
            // cosmetic only
        }
        G.WebSocket = ShellWebSocket;

        // Outbound observation. Installed ONCE, at prototype level, so sockets
        // created before this hook (there are none at document-start, but the
        // page may create more later) are covered as well.
        var proto = NativeWebSocket.prototype;
        var originalSend = proto.send;
        var wrappedSend = function (data) {
            try {
                adoptSocket(this);
            } catch (e) {}
            if (!injecting && typeof data === 'string') {
                try {
                    observeText(data, true);
                } catch (e) {
                    // observation must never break the page's send
                }
            }
            return originalSend.apply(this, arguments);
        };
        wrappedSend.__zcodeShellWrapped = true;
        proto.send = wrappedSend;

        // Outbound close observation. Which side tore the connection down decides
        // what the fix is — the desktop, the transport, the page itself, or our own
        // recovery — and the close event alone cannot say. The caller does.
        //
        // Take frame [1] and the column of it, and you get THIS line: the wrapper
        // builds the Error inside itself, so the naive `stack.split('\n')[1]` names
        // the wrapper, not the caller. That is how a round of field work concluded
        // "the page closes its own socket" from a log line that was really just
        // pointing at itself. So: walk the frames, drop our own wrapper, and keep a
        // few of what is left — `forceReconnect` shows up by name, the page shows
        // up as its bundle position.
        var originalClose = proto.close;
        var wrappedClose = function () {
            try {
                adoptSocket(this);
            } catch (e) {}
            try {
                var lines = String((new Error()).stack || '').split('\n');
                var frames = [];
                for (var i = 1; i < lines.length && frames.length < 3; i += 1) {
                    var frame = lines[i].trim();
                    if (!frame || frame.indexOf('wrappedClose') >= 0) {
                        continue;
                    }
                    frames.push(frame.substring(0, 90));
                }
                diag('debug', 'socket.close() 被调用' +
                    (arguments.length ? '(' + String(arguments[0]) + ')' : '（无参）') +
                    ' 来自 ' + (frames.join(' ← ') || '未知（只有包装函数帧）'));
            } catch (e) {
                // observation must never break the page's close
            }
            return originalClose.apply(this, arguments);
        };
        wrappedClose.__zcodeShellWrapped = true;
        proto.close = wrappedClose;
    }

    var knownSockets = typeof WeakSet === 'function' ? new WeakSet() : null;

    function socketKnown(socket) {
        if (knownSockets) {
            return knownSockets.has(socket);
        }
        return sockets.indexOf(socket) >= 0;
    }

    /**
     * 晚注入恢复（零重连）：hook 挂在 WebSocket.prototype 上，对补注前就已
     * 创建的页面 socket 同样生效——它下一次任何 send/close 都会把活实例作为
     * `this` 送进来。当场收编（补挂 message 监听、设为 activeSocket），壳的
     * 入站观测与自建桥接即刻恢复，页面全程无感、无需重建连接。
     */
    function adoptSocket(socket) {
        if (!socket || socketKnown(socket)) {
            return;
        }
        trackSocket(socket);
        if (!activeSocket) {
            activeSocket = socket;
        }
        diag('warn', '注入晚于页面建线，已从原型层收编现有 socket（零重连）');
    }

    /** 页面开过的 socket 类型（只记 pathname，凭证一律不进日志）。 */
    var socketPaths = {};

    function trackSocket(socket, url) {
        if (socketKnown(socket)) {
            return;
        }
        if (url) {
            lastRelayUrl = url;
            maybePostRelayCreds();
            // 诊断（2026-09-15）：页面到底开了**几种** WebSocket。
            // 起因：relay 那条上只观测到 `controller/*` 与 `sessions-index/*`，
            // **从来没有 `conversation/*`**，可页面的会话商店明明拿到了 snapshot。
            // 第一件要问的事就是"对话流是不是走了另一条 socket"——页面里确实还有一条
            // `/ws/remote-control/window/<token>`（见 docs/05 的 bundle 分析）。
            try {
                var path = String(url).split('?')[0].replace(/^[a-zA-Z]+:\/\/[^/]+/, '');
                socketPaths[path] = (socketPaths[path] || 0) + 1;
                if (socketPaths[path] === 1) {
                    diag('info', '页面 socket 类型首次出现：' + path);
                }
            } catch (e) {
                // 诊断失败不影响建线
            }
        }
        if (knownSockets) {
            knownSockets.add(socket);
        }
        sockets.push(socket);
        if (sockets.length > 8) {
            sockets.shift();
        }
        if (!activeSocket) {
            activeSocket = socket;
        }
        var handler = function (event) {
            if (event && event.data !== undefined) {
                observeMessage(event.data);
            }
        };
        try {
            socket.addEventListener('message', handler);
            socket.addEventListener('open', function () {
                activeSocket = socket;
                liveness.socketsOpened += 1;
                diag('info', 'relay socket open (#' + liveness.socketsOpened + ')');
            });
            socket.addEventListener('close', function (event) {
                liveness.socketsClosed += 1;
                diag('warn', 'relay socket closed (code=' +
                    (event ? event.code : '?') + ' clean=' +
                    (event ? event.wasClean : '?') + ')');
                relayPaired = false;
                resetClient();
                if (activeSocket === socket) {
                    activeSocket = null;
                    for (var i = sockets.length - 1; i >= 0; i--) {
                        if (sockets[i].readyState === 1) {
                            activeSocket = sockets[i];
                            break;
                        }
                    }
                }
            });
        } catch (e) {
            diag('warn', 'socket 监听安装失败: ' + e);
        }
    }

    function observeMessage(data) {
        liveness.inboundFrames += 1;
        liveness.lastInboundAt = Date.now();
        var startedAt = now();
        if (typeof data === 'string') {
            observeText(data, false);
            recordDecode(startedAt, data.length);
        } else if (data && typeof data.text === 'function') {
            data.text().then(function (text) {
                var started = now();
                observeText(text, false);
                recordDecode(started, text.length);
            }).catch(function () {});
        } else if (data instanceof ArrayBuffer) {
            try {
                var decoded = new TextDecoder('utf-8').decode(data);
                observeText(decoded, false);
                recordDecode(startedAt, data.byteLength);
            } catch (e) {
                // ignore
            }
        }
    }

    // -----------------------------------------------------------------------
    // 2b. main-thread cost instrumentation
    //
    // "A conversation takes forever to open" cannot be answered from the native
    // side: the shell only sees the bridge callbacks it asked for. The numbers
    // below separate the two candidate explanations — our own frame decoding
    // hogging the page's thread, versus the page waiting on its own network/RPC —
    // and they are reported into the same diagnostics log as everything else, so
    // one exported file tells the whole story.
    //
    // One inbound frame costs `JSON.parse` plus the rpc-frame assembler (base64,
    // crc32, chunk reassembly), all of it on the page's thread, so the whole
    // observation is measured rather than just the parse. Long tasks are counted
    // as well: if our frame bursts correlate with 300 ms main-thread stalls, the
    // subscription itself is the problem.
    // -----------------------------------------------------------------------

    /** A long task is worth a line of its own above this; a few per window, max. */
    var LONG_TASK_LOG_MS = 200;
    var LONG_TASK_LOG_MAX = 5;

    var perf = {
        windowStartedAt: Date.now(),
        windowFrames: 0,
        windowChars: 0,
        windowDecodeMs: 0,
        windowLongTasks: 0,
        windowLongTaskMs: 0,
        decodedFrames: 0,
        inboundChars: 0,
        decodeMs: 0,
        decodeMsMax: 0,
        longTasks: 0,
        longTaskMs: 0,
        longTaskMaxMs: 0,
        longTasksLogged: 0
    };

    /**
     * Per-window link counters reported on the perf line.
     *
     * These exist to answer questions the field log could not: while the app is
     * backgrounded, is the desktop still sending pair acks, are OUR probes actually
     * going out, and is the PAGE's own heartbeat still beating? The last one is the
     * crux for the page's ~2-minute reconnect: its ack watchdog is armed from its
     * own relay client, so "the page stopped beating because the renderer throttles
     * hidden-page timers" and "the page is still beating and something else arms the
     * watchdog" need different fixes and look identical from the outside.
     */
    var linkWindow = {acks: 0, probes: 0, pageBeats: 0, outFrames: 0, outChars: 0};

    function now() {
        try {
            return (G.performance && G.performance.now) ?
                G.performance.now() : Date.now();
        } catch (e) {
            return Date.now();
        }
    }

    /**
     * A URL or name with its query string removed and its length bounded.
     *
     * The remote page's URLs carry `sid`/`hash`/`mid` in the query string, and
     * these strings end up in a log file the user is asked to share. Only the
     * path is ever useful for diagnostics, so only the path is kept.
     */
    function safePath(value) {
        var text = String(value === undefined || value === null ? '' : value);
        var cut = text.indexOf('?');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        cut = text.indexOf('#');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        return text.length > 80 ? text.substring(0, 77) + '...' : text;
    }

    function recordDecode(startedAt, chars) {
        var cost = now() - startedAt;
        perf.decodedFrames += 1;
        perf.inboundChars += chars || 0;
        perf.decodeMs += cost;
        if (cost > perf.decodeMsMax) {
            perf.decodeMsMax = cost;
        }
    }

    function longTaskAttribution(entry) {
        try {
            var list = entry.attribution || [];
            if (list.length && list[0]) {
                var first = list[0];
                var where = first.containerName ? ':' + safePath(first.containerName) : '';
                return '[' + (first.containerType || '?') + where + ']';
            }
        } catch (e) {
            // ignore
        }
        return '';
    }

    function installLongTaskObserver() {
        try {
            if (typeof G.PerformanceObserver !== 'function') {
                return;
            }
            var observer = new G.PerformanceObserver(function (list) {
                try {
                    var entries = list.getEntries();
                    for (var i = 0; i < entries.length; i++) {
                        var duration = entries[i].duration || 0;
                        perf.longTasks += 1;
                        perf.longTaskMs += duration;
                        if (duration > perf.longTaskMaxMs) {
                            perf.longTaskMaxMs = duration;
                        }
                        if (duration >= LONG_TASK_LOG_MS &&
                            perf.longTasksLogged < LONG_TASK_LOG_MAX) {
                            perf.longTasksLogged += 1;
                            diag('debug', '长任务 ' + Math.round(duration) + 'ms ' +
                                longTaskAttribution(entries[i]));
                        }
                    }
                } catch (e) {
                    // instrumentation must never break the page
                }
            });
            observer.observe({entryTypes: ['longtask']});
        } catch (e) {
            // Not supported here: the counters simply stay at zero.
        }
    }

    /** 上一次上报给原生的对话正文（去重：只在变化时发）。 */
    var lastConvTextSent = '';

    /** Periodic one-liner, emitted only when there was traffic to report. */
    function reportPerf() {
        var frames = perf.decodedFrames - perf.windowFrames;
        var longTasks = perf.longTasks - perf.windowLongTasks;
        var acks = linkWindow.acks;
        var probes = linkWindow.probes;
        var pageBeats = linkWindow.pageBeats;
        var outFrames = linkWindow.outFrames;
        var outChars = linkWindow.outChars;
        linkWindow.acks = 0;
        linkWindow.probes = 0;
        linkWindow.pageBeats = 0;
        linkWindow.outFrames = 0;
        linkWindow.outChars = 0;
        if (frames === 0 && longTasks === 0 && acks === 0 && probes === 0 && pageBeats === 0 &&
            outFrames === 0) {
            return;
        }
        var elapsed = Date.now() - perf.windowStartedAt;
        var chars = perf.inboundChars - perf.windowChars;
        var decodeMs = perf.decodeMs - perf.windowDecodeMs;
        var longTaskMs = perf.longTaskMs - perf.windowLongTaskMs;
        var socket = activeSocket;
        // 对话流的"最新正文"上报（提取逻辑见 zcode-protocol.js 的
        // _trackConversationText）：这条流走的是**网页自己那条 socket**，
        // 所以后台跟手不必再让原生另开一条连接（也就不会再 KICK 页面）。
        var conv = null;
        var convStats = null;
        try {
            if (client && typeof client.latestConversationText === 'function') {
                conv = client.latestConversationText();
                if (conv && conv.text && conv.text !== lastConvTextSent) {
                    lastConvTextSent = conv.text;
                    post('convtext', {
                        topic: conv.topic,
                        text: conv.text,
                        frames: conv.frames
                    });
                }
            }
            // 帧计数**独立于**"有没有正文"取：两者混在一起时，"没帧"和"有帧但没解出
            // 正文"在日志里长得一模一样（2026-09-15 的教训，见 conversationFrameStats）。
            if (client && typeof client.conversationFrameStats === 'function') {
                convStats = client.conversationFrameStats();
            }
        } catch (e) {
            // 上报失败绝不影响节拍本身
        }
        diag('debug', '页面开销 ' + Math.round(elapsed / 1000) + 's：收帧 ' + frames +
            ' 个（' + Math.round(chars / 1024) + 'K 字符，解码合计 ' +
            Math.round(decodeMs) + 'ms，单帧最长 ' + Math.round(perf.decodeMsMax) + 'ms）· ' +
            '长任务 ' + longTasks + ' 个（合计 ' + Math.round(longTaskMs) +
            'ms，最长 ' + Math.round(perf.longTaskMaxMs) + 'ms）· ' +
            '发帧 ' + outFrames + ' 个（' + Math.round(outChars / 1024) + 'K 字符）· ' +
            '链路 ack ' + acks + ' · 探针 ' + probes + ' · paired ' + relayPaired +
            ' socket ' + (socket ? socket.readyState : -1) +
            ' · 页面心跳 ' + pageBeats +
            ' · 对话帧 ' + (convStats ? convStats.frames : 0) +
            '（topic ' + (convStats ? convStats.topics : 0) + ' 个 · 订阅 ' +
            (convStats ? convStats.subs : 0) + ' · 重锚 ' + (convStats ? convStats.resyncs : 0) +
            ' · 正文 ' + (convStats ? convStats.textLen : 0) + ' 字）');
        perf.windowStartedAt = Date.now();
        perf.windowFrames = perf.decodedFrames;
        perf.windowChars = perf.inboundChars;
        perf.windowDecodeMs = perf.decodeMs;
        perf.windowLongTasks = perf.longTasks;
        perf.windowLongTaskMs = perf.longTaskMs;
    }

    /**
     * Navigation timing for the page itself, once per load.
     *
     * `onPageFinished` on the native side measures the document, but not what it
     * was waiting for: this adds TTFB, DOMContentLoaded, load and the slowest
     * resource, which is what distinguishes "the network is slow" from "the relay
     * is slow" from "the page's own JS is slow".
     */
    function reportLoadTiming() {
        try {
            var nav = null;
            var resources = [];
            if (G.performance && typeof G.performance.getEntriesByType === 'function') {
                var navs = G.performance.getEntriesByType('navigation');
                nav = navs && navs[0] ? navs[0] : null;
                resources = G.performance.getEntriesByType('resource') || [];
            }
            var parts = [];
            if (nav) {
                parts.push('ttfb=' + Math.round(nav.responseStart));
                parts.push('DOMContentLoaded=' + Math.round(nav.domContentLoadedEventEnd));
                parts.push('load=' + Math.round(nav.loadEventEnd));
            }
            var transferred = 0;
            var slowest = null;
            for (var i = 0; i < resources.length; i++) {
                transferred += resources[i].transferSize || 0;
                if (!slowest || resources[i].duration > slowest.duration) {
                    slowest = resources[i];
                }
            }
            parts.push('资源 ' + resources.length + ' 个 / ' +
                Math.round(transferred / 1024) + 'KB');
            if (slowest) {
                parts.push('最慢 ' + safePath(slowest.name) + ' ' +
                    Math.round(slowest.duration) + 'ms');
            }
            diag('info', '页面加载计时：' + parts.join(' · '));
        } catch (e) {
            // instrumentation must never break the page
        }
    }

    // -----------------------------------------------------------------------
    // 3. protocol client wiring
    // -----------------------------------------------------------------------
    var client = null;
    var relayPaired = false;

    /**
     * What the page already streams is a fact about the PAGE, not about one
     * client instance. `resetClient()` runs on every relay disconnect, so this
     * object has to outlive it: without it the rebuilt client re-opens an active
     * bridge for the very workspace the page is showing, the desktop rejects the
     * duplicate with rpc-transport-fault, and the reopen loop keeps hammering the
     * channel the user's own conversation request is queued on.
     */
    /**
     * 页面**自己**正在显示的那个工作区（后台上承唯一该开桥的那一个）。
     * 来源是页面自己发的 `workspace-bridge-open` 帧；变化时才上报一次（见 observeText）。
     */
    var lastPageWorkspace = '';

    var pageCoverage = {
        passive: {},
        outboundListenIds: {},
        bridgeWorkspace: {},
        // Fault history, also per-PAGE rather than per-client: a relay rebuild
        // must not reset the strike count on a workspace the desktop refuses
        // every time (that reset was the loop; see zcode-protocol.js).
        faultStreak: {},
        cooldownUntil: {}
    };

    function createClient() {
        var cfg = config();
        // 接线自证：**0 个工作区** = 原生没把运行集给过来（接线问题）；**有工作区但
        // 0 条会话** = 确实没有在跑的任务（数据问题）。这两件事的处理完全不同。
        diag('debug', '原生运行集种子：' +
            Object.keys(cfg.runningSessions || {}).length + ' 个工作区');
        var next = new P.RemoteClient({
            send: injectPayload,
            log: function (message) {
                diag('debug', message);
            },
            // 原生给的"在跑会话"种子（工作区 → 会话 id）：开桥时要先订哪几条对话用它。
            // 它**活过页面重载**，而 JS 侧自己攒的清单活不过——详见
            // zcode-protocol.js 的 _conversationCandidates 与 WebAppBridge.config 的注释。
            nativeRunningSessions: cfg.runningSessions || {},
            // 但**光有创建时的快照不够**：`config()` 是同步 JS 接口，而原生 TaskStore 是
            // 随索引帧长起来的——刚重装/重启后创建 client 时它还是空的（真机 2026-09-15：
            // 一直报"0 个工作区"）。所以开桥那一刻**再问一次**，这才是真正新鲜的种子。
            nativeRunningSessionsProvider: function () {
                try {
                    var fresh = config();
                    return fresh && fresh.runningSessions ? fresh.runningSessions : {};
                } catch (e) {
                    return {};
                }
            },
            sharedState: pageCoverage
        });
        next.gen = ++clientGenSeq;
        next.bornAt = Date.now();
        next.onSessions = function (update) {
            post('sessions', update);
            try {
                noteRunningActivity(update);
            } catch (e) {}
        };
        next.onPageRpcCall = function (call) {
            try {
                notePageRpcCall(call);
            } catch (e) {
                // the fallback watcher must never break the page's traffic
            }
        };
        next.onPageRpcResult = function (result) {
            try {
                notePageRpcResult(result);
            } catch (e) {
                // the upload trace must never break the page's traffic
            }
        };
        next.onPageRpcSilence = function (info) {
            diag('warn', '页面调用无回包 ' + Math.round((info.ageMs || 0) / 1000) +
                's：' + info.name + '（桌面端从未回答）');
        };
        next.suppressPageRpcMirror = pagelogSeen;
        // 只上报，不做任何"没起来就重试"的动作：注入层**没有**主动开桥这回事了
        // （2026-09-17 删 D7；原实现在这里对 status.active 为假重试 retryStart）。
        next.onStatus = function (status) {
            post('status', status);
        };
        return next;
    }

    function ensureClient() {
        if (!client) {
            client = createClient();
        }
        return client;
    }

    /**
     * 每个工作区"最近一次看到 running 任务有活动"的时间（sessions-index 推送）。
     * 僵尸订阅检测的判据之一：任务在跑（桌面端在产出）而页面桥零帧。
     */
    var runningActivityByKey = {};

    function noteRunningActivity(update) {
        if (!update || !update.key || !update.sessions) {
            return;
        }
        for (var i = 0; i < update.sessions.length; i += 1) {
            var task = update.sessions[i];
            if (task && task.phase === 'running') {
                var at = task.lastActivityAt || Date.now();
                if (at > (runningActivityByKey[update.key] || 0)) {
                    runningActivityByKey[update.key] = at;
                }
            }
        }
    }

    function resetClient() {
        if (client) {
            try {
                client.dispose();
            } catch (e) {
                // ignore
            }
            client = null;
            diag('warn', 'relay 断开，重建协议客户端（页面覆盖情况保留）');
        }
    }

    // -----------------------------------------------------------------------
    // 3b.（已删除）"什么时候开我们自己的桥"
    //
    // 2026-09-17（D7 删除）：这里原本是"主动开桥"的调度器（`maybeStartActive`：
    // 配对后等页面安静 800ms、最多推迟 12s，然后 `client.start()` 给**每个**工作区
    // 在页面那条 socket 上开桥 + 订索引）。它是 D7「订阅所有工作区」的 Tier1 实现，
    // 2026-09-15 真机 A/B 定罪：与页面自己的订阅争用 → 页面卡"工作中"+转圈。
    // 一并删掉的还有 `ACTIVE_START_*` 四个常量、`startScheduled`/`startAttempts`。
    //
    // **只读壳契约**（见 SHELL_READ_ONLY）：注入层永不主动开桥；多工作区覆盖由
    // Tier2（原生自开 socket，`RelayBridge`）+ 发现链承载。别把这段调度器加回来。
    // -----------------------------------------------------------------------

    /**
     * Sends a business payload on the socket the page has open.
     *
     * [quiet] downgrades "no socket is open right now" from a warning to a debug
     * line. The heartbeat probe uses it: a probe that lands in the gap between
     * the page closing its socket and opening the next one is routine (the page
     * rebuilds the connection on its own), whereas the same message from our own
     * protocol client means a request was actually lost.
     */
    function injectPayload(payload, quiet) {
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            socket = null;
            for (var i = sockets.length - 1; i >= 0; i--) {
                if (sockets[i].readyState === 1) {
                    socket = sockets[i];
                    break;
                }
            }
            if (!socket) {
                if (quiet) {
                    diag('debug', '暂无可用的 relay socket（等页面重连后由下一次心跳补上）');
                } else {
                    diag('warn', '注入失败: 没有可用的 relay socket');
                }
                return;
            }
            activeSocket = socket;
        }
        var frame = {
            type: 'data',
            payload: payload,
            client_ts: Date.now()
        };
        liveness.outboundFrames += 1;
        injecting = true;
        try {
            socket.send(JSON.stringify(frame));
        } catch (e) {
            diag('warn', '注入发送失败: ' + e);
        } finally {
            injecting = false;
        }
    }

    /**
     * 发送**顶层控制帧**（`pair_status_query` 这类）。
     *
     * 为什么不能复用 [injectPayload]：那个函数把内容包成
     * `{type:'data', payload:…}`——那是**数据面**（rpc-frame）的形状；而 relay 的
     * 控制帧必须是**顶层 `type`**（页面自己的客户端就是 `this.send({type:'pair_status_query',
     * device_sid, client_ts})`，服务端按顶层 type 分发）。
     *
     * 真机 2026-09-15 定案：此前心跳探针走的是 injectPayload，于是它一直被服务端当成
     * 一个解不开的 data 载荷丢掉——**从来没有被 ack 过**。指纹就是后台那两列数字：
     * `探针 1` 而 `链路 ack 0`。前台看不出来（页面自己的心跳定时器在跑），一旦退后台，
     * 页面的自链式定时器被 Chromium 节流，没有 ack，页面的 30s ack 看门狗就判死并
     * `i4t.reconnectAfterStaleWaiting` 关掉 socket——这就是"回后台立刻断线"的根因。
     */
    function sendControlFrame(payload) {
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            socket = null;
            for (var i = sockets.length - 1; i >= 0; i--) {
                if (sockets[i].readyState === 1) {
                    socket = sockets[i];
                    break;
                }
            }
        }
        if (!socket || socket.readyState !== 1) {
            return false;
        }
        activeSocket = socket;
        injecting = true;
        try {
            socket.send(JSON.stringify(payload));
            return true;
        } catch (e) {
            diag('warn', '控制帧发送失败: ' + e);
            return false;
        } finally {
            injecting = false;
        }
    }

    function observeText(text, outbound) {
        if (!text) {
            return;
        }
        var frame;
        try {
            frame = JSON.parse(text);
        } catch (e) {
            return;
        }
        if (!frame || typeof frame !== 'object') {
            return;
        }
        if (frame.type === 'auth_init' && typeof frame.device_sid === 'string') {
            // Learned, never logged: it is a session credential.
            deviceSid = frame.device_sid;
            lastAuthInitText = text;
            maybePostRelayCreds();
            return;
        }
        if (frame.type === 'auth_ack' || frame.type === 'pair_status_ack') {
            if (outbound) {
                return;
            }
            relayPaired = frame.pair_status === 'matched';
            lastPairAckAt = Date.now();
            liveness.pairAcks += 1;
            linkWindow.acks += 1;
            if (relayPaired) {
                // 配对成功＝链路真的回来了，KICKED 自愈的连续计数到此清零。
                if (kickedAwayAt) {
                    kickedAwayAt = 0;
                    diag('info', '页面在后台重新配对成功，取消待处理的 KICKED 自愈');
                }
                if (kickedHealCount() > 0) {
                    storeSet(KICKED_HEAL_STORE, '0');
                }
                // 只建客户端 + 跟随页面：**不**在这里安排任何主动开桥
                // （2026-09-17 删 D7；原实现是 `maybeStartActive()`）。
                ensureClient();
            } else {
                resetClient();
            }
            return;
        }
        if (frame.type === 'pair_status_query') {
            if (outbound) {
                // The PAGE's own heartbeat — the timer the native pump stands in
                // for. Our own probe is sent while `injecting` is set, so it never
                // reaches this branch. Counting the page's beats is how we tell
                // "its timer is throttled away while hidden" (no beats) from "it is
                // still beating and something else is arming the watchdog".
                linkWindow.pageBeats += 1;
            }
            return;
        }
        if (frame.type === 'error') {
            // relay 级错误。KICKED ＝ 本端被顶掉：壳在后台的原生接管（预期），
            // 或另一台控制端接入（不是我们的战场）。页面自己的传输层会因此
            // 进入终态（"已被其他设备接管"页，只能手动点"重新连接"），所以
            // 这里记一笔，回前台时由壳重载页面把链路重新拿回来。
            if (String(frame.code) === 'KICKED') {
                noteRelayKicked();
            }
            return;
        }
        if (frame.type !== 'data' || !frame.payload ||
            typeof frame.payload !== 'object') {
            return;
        }
        if (outbound) {
            // The page's outbound volume: the upload path ships file chunks as
            // relay frames, so a multi-hundred-KB burst here IS the "the page
            // is sending my file" signature (reported in 页面开销).
            linkWindow.outFrames += 1;
            linkWindow.outChars += text.length;
            // 页面**自己**在看的那个工作区/任务：它的 `workspace-bridge-open` 帧里
            // 就带着 `workspaceKey`（有时还有 `taskId`）。这是后台原生承载唯一该开桥的
            // 那一个——真机 2026-09-16 定案：原生扫全部工作区会让桌面端把 host 收掉
            // （docs/16 §8/§10），而页面自己永远只开它正在看的那个。转给原生（只记
            // 键名，不含内容）。
            try {
                if (frame.payload.zcode_type === 'workspace-bridge-open') {
                    var wsKey = frame.payload.workspaceKey;
                    if (typeof wsKey === 'string' && wsKey.length > 0 && wsKey !== lastPageWorkspace) {
                        lastPageWorkspace = wsKey;
                        post('pagews', {
                            key: wsKey,
                            taskId: typeof frame.payload.taskId === 'string' ? frame.payload.taskId : ''
                        });
                    }
                }
            } catch (e) {
                // 观测失败绝不影响页面自己的发送
            }
            try {
                ensureClient().acceptObservedPayload(frame.payload, true);
            } catch (e) {
                diag('warn', 'outbound 处理失败: ' + e);
            }
            return;
        }
        var target = ensureClient();
        try {
            // Routing (our own bridges, our own pending requests) ...
            target.acceptPayload(frame.payload);
            // ... and passive observation of the page's own bridges.
            target.acceptObservedPayload(frame.payload, false);
        } catch (e) {
            diag('warn', 'inbound 处理失败: ' + e);
        }
    }

    // -----------------------------------------------------------------------
    // 4. heartbeat, staleness recovery, and the resume posture
    //
    // The page has its own heartbeat, but it is subject to the same background
    // suspension as everything else in the renderer — while the app is
    // backgrounded this interval can stop firing for the whole stint. So the
    // tick body is exposed as __zcodeShellHeartbeat and the foreground service
    // drives it every 15s (see ShellRuntime).
    //
    // Two rules govern when this layer may tear the socket down. Both are
    // conservative on purpose: closing the socket is the heaviest thing we can
    // do — recovery belongs to the page, and a rebuild costs a full re-open of
    // workspaces and tasks.
    //
    //   1. One stale reading is not a verdict. The first tick that finds the
    //      ack stale only logs and probes again; the second consecutive one
    //      rebuilds.
    //   2. On return to the foreground the decision is made on whether frames
    //      were still arriving, not on the ack clock. A link that was still
    //      delivering is left alone (ack clock reset, one probe sent); only a
    //      link that has been silent for a minute is rebuilt. Before this, every
    //      return from a long background closed a socket the page was about to
    //      recover on its own, turning one self-heal into a full re-open.
    // -----------------------------------------------------------------------
    var HEARTBEAT_MS = 10000;
    var STALE_MS = 90000;
    /** Floor between two forced rebuilds, so a dead desktop cannot make us churn. */
    var RECONNECT_MIN_GAP_MS = 180000;
    /** Consecutive stale readings required before the socket is rebuilt. */
    var STALE_PROBES = 2;
    /** A foreground return with the link silent this long is treated as dead. */
    var RESUME_DEAD_LINK_MS = 60000;
    /**
     * 回前台"死链兜底"：观察窗与限流（逻辑见 healDeadLinkOnResume）。
     *
     * ⚠️ **尚未真机验证**（2026-09-15 晚加的）。它要解决的现场是：
     * 长后台期间页面定时器被节流 → 桌面端判设备离线 → 回前台时页面自己重连成功
     * （1006 → 1 秒内 open）**但重新订阅收不到 ack**，对话详情一直 `rows: 0`，
     * 只有重启应用（全新 bootstrap）才恢复。兜底＝对**已死的链路**重载一次页面，
     * 与用户手动重启等价；健康链路永远不碰（见 SHELL_READ_ONLY 的边界）。
     */
    var RESUME_HEAL_GRACE_MS = 5000;
    var RESUME_HEAL_MIN_GAP_MS = 300000;
    var lastResumeHealAt = 0;
    var resumeHealTimer = null;
    /** Ticks closer together than this are dropped so the timer and the pump cannot double up. */
    var MIN_TICK_GAP_MS = 5000;
    /** Cap on automatic reloads after a KICKED, counted across the reload itself. */
    var KICKED_HEAL_CAP = 2;
    var KICKED_HEAL_STORE = 'zcodeShellKickedHeals';
    var lastPairAckAt = 0;
    var lastForcedReconnectAt = 0;
    var lastTickAt = 0;
    var staleTicks = 0;
    var appForeground = true;
    var backgroundStartedWallMs = 0;
    var lastBackgroundSilenceLoggedAt = 0;

    /**
     * **只读壳**（2026-09-15 真机定案）。
     *
     * 这一层原本有四件"动手"的事，全部被真机日志定罪为**自伤**。定罪证据是
     * `socket.close() 被调用 … 来自 …` 那行——它点名调用者，抓到的原话：
     *
     *   at nudgeReconnect ← at fallbackCheck
     *       "进对话 5s 铁判准"；20:27:28–20:28:06 一轮 40 秒内拆了 10 次
     *   at forceReconnect ← at G.__zcodeShellSetAppForeground
     *       每次回前台、只要入站帧静默 >60s，就先拆了再说（20:53:04）
     *   at forceReconnect ← at heartbeatTick ← at G.__zcodeShellHeartbeat
     *       心跳 ack 陈旧（18:24、18:35、19:40、20:56…）
     *   stallReloadIfAllowed → location.reload()
     *
     * 拆掉的每一次都是**页面正用着的那条** relay 连接，页面只能按自己的梯子重建：
     * 用户看到的就是"发消息转圈""要重连好几次才出来""返回页面是它自己在重连"。
     *
     * 对照组（2026-09-15 21:44:27 起 `passive_off`，这四件事全部失去 socket 句柄）：
     * 页面自己的对话订阅 ack 之后 **5 分钟零生命周期事件**——没有 close/connect、
     * 没有 unsub/resub、没有 KICKED、没有轻推。页面收发能力本身是完好的。
     *
     * 因此定为政策：**壳永不关闭页面的 socket、永不自动重载页面**。观测照旧（只读），
     * 后台保活只留"补心跳"一件事。要做对照实验时把这里改成 false 即可。
     */
    var SHELL_READ_ONLY = true;

    /** 只读壳拒绝动手时的日志节流（后台每 10s 一条会把日志刷没）。 */
    var lastReadOnlyRefusalAt = 0;

    /**
     * 记一条"本会在这一刻动页面"的日志。它不只是说明，还是**诊断量**：
     * 什么时候我们会想拆线，就说明那一刻页面的链路在观测上已经不健康了。
     */
    function noteReadOnlyRefusal(action, reason) {
        var nowMs = Date.now();
        if (lastReadOnlyRefusalAt && nowMs - lastReadOnlyRefusalAt < 60000) {
            return;
        }
        lastReadOnlyRefusalAt = nowMs;
        diag('warn', '只读壳：不' + action + '（本会触发的原因：' + reason + '）');
    }

    /**
     * relay 把本端顶掉（KICKED）。
     *
     * 两种来源：① 壳在后台的原生接管——这是**预期**行为（relay 是单控制端互斥
     * 的，原生要接管就必须把页面顶下去）；② 另一台控制端接入——不是我们的战场。
     * 共同点是页面自己的传输层会因此进终态（"已被其他设备接管"，只有手动点
     * "重新连接"才回得来），所以壳必须负责把它救回来：
     *
     *   * 后台被顶 → 什么都不做（那时页面不该在跑），只在回前台时重载一次，
     *     让页面用同一条链路重新握手（原生槽位那时已交还）。
     *   * 前台被顶 → 不自动干预（可能与另一台控制端在抢）。
     *
     * 重载夹在 1.5 s 之后：原生交还需要先关掉它自己的 socket，立刻重载会再被
     * 顶一次。跨 reload 用 sessionStorage 记次数，配对成功即清零，所以最坏也
     * 只是重载两次而不是死循环。
     */
    var kickedAwayAt = 0;

    function kickedHealCount() {
        return parseInt(storeGet(KICKED_HEAL_STORE), 10) || 0;
    }

    /**
     * 回前台"死链兜底"（2026-09-15 晚加，**尚未真机验证**）。
     *
     * 现场（2026-09-15 22:15:26 真机日志）：长后台 7 分钟后回前台，页面自己把
     * 死了 385s 的 socket 收尸并 1 秒内重连成功（`1006` → `relay socket open (#2)`），
     * 紧接着 `conversation subscription started`——**但永远没有 acknowledged**，
     * 页面体征一直 `{"chat":true,"timeline":true,"rows":0}`，对话详情空白；
     * 重启应用（全新 bootstrap）后 250ms 就 ack 回来、快照 234 行。
     *
     * 判据（三条同时成立才动手，全是"页面已经失败"的证据）：
     *   ① 回前台时链路观测已静默 > RESUME_DEAD_LINK_MS；
     *   ② 观察窗 RESUME_HEAL_GRACE_MS 内**没有任何入站帧**（页面自己没恢复）；
     *   ③ 体征显示"在对话视图里但 0 行"（正是"重订阅没 ack"的形状）。
     * 三条都成立时才重载，且 5 分钟限流一次——与用户手动重启等价，但不用他动手。
     * 健康链路一条都不碰：只要有帧在进，② 就不成立。
     */
    function healDeadLinkOnResume(silenceMs) {
        if (resumeHealTimer) {
            clearTimeout(resumeHealTimer);
        }
        var framesAtResume = liveness.inboundFrames;
        var resumedAt = Date.now();
        resumeHealTimer = setTimeout(function () {
            resumeHealTimer = null;
            if (!appForeground) {
                diag('info', '回前台兜底：观察窗内又退到后台，本轮不判');
                return;
            }
            if (liveness.inboundFrames !== framesAtResume) {
                diag('info', '回前台兜底：链路已自行恢复（静默 ' +
                    Math.round(silenceMs / 1000) + 's 后收到 ' +
                    (liveness.inboundFrames - framesAtResume) + ' 帧），不介入');
                return;
            }
            var v = null;
            try {
                v = readVitals();
            } catch (e) {
                // 读不到体征就不动手：判据不齐宁可不干预
            }
            if (!v || v.chat !== true || v.rows > 0) {
                diag('warn', '回前台兜底：静默 ' + Math.round(silenceMs / 1000) +
                    's 且观察窗内零入站帧，但界面不是"对话 0 行"（chat=' +
                    (v ? v.chat : '?') + ' rows=' + (v ? v.rows : '?') + '），不介入');
                return;
            }
            var gap = Date.now() - lastResumeHealAt;
            if (gap <= RESUME_HEAL_MIN_GAP_MS) {
                diag('info', '回前台兜底被限流跳过（距上次重载 ' + Math.round(gap / 1000) +
                    's < ' + Math.round(RESUME_HEAL_MIN_GAP_MS / 1000) + 's）');
                return;
            }
            lastResumeHealAt = Date.now();
            diag('warn', '回前台兜底：静默 ' + Math.round(silenceMs / 1000) +
                's、观察窗 ' + Math.round((Date.now() - resumedAt) / 1000) +
                's 内零入站帧、对话 0 行 → 重载页面一次（' +
                Math.round(RESUME_HEAL_MIN_GAP_MS / 60000) + ' 分钟限流）');
            try {
                G.location.reload();
            } catch (e) {
                diag('warn', '回前台兜底重载失败: ' + e);
            }
        }, RESUME_HEAL_GRACE_MS);
    }

    function noteRelayKicked() {
        if (appForeground) {
            diag('warn', '页面连接被顶掉（relay 返回 KICKED，前台）：不自动干预');
            return;
        }
        kickedAwayAt = Date.now();
        diag('warn', '页面连接被顶掉（relay 返回 KICKED，应用在后台）：' +
            '回前台将自动重载页面恢复');
    }

    function healKickedOnForeground() {
        // 只读壳里**唯一保留**的页面级干预，理由是它针对的是一条已经死掉的链路：
        // KICKED 是终态（页面进入"已被其他设备接管"，自己不会回来），重载是唯一
        // 恢复手段，不存在"打断健康连接"的问题。回前台才做，且跨 reload 记次数封顶。
        if (!kickedAwayAt) {
            return;
        }
        kickedAwayAt = 0;
        var count = kickedHealCount();
        if (count >= KICKED_HEAL_CAP) {
            diag('error', 'KICKED 自愈：已连续重载 ' + count + ' 次仍未配对，停止自动重载');
            return;
        }
        storeSet(KICKED_HEAL_STORE, String(count + 1));
        diag('warn', 'KICKED 自愈：1.5s 后重载页面（第 ' + (count + 1) + '/' +
            KICKED_HEAL_CAP + ' 次），让页面重新配对');
        setTimeout(function () {
            try {
                G.location.reload();
            } catch (e) {
                diag('warn', 'KICKED 自愈重载失败: ' + e);
            }
        }, 1500);
    }

    /**
     * Liveness counters.
     *
     * These exist to answer one question that cannot be answered by reading the
     * code: while the app is backgrounded for half an hour, is the relay link
     * still delivering? `pairAcks` is the decisive number — the desktop answers
     * every heartbeat, so a non-zero count across a background window proves the
     * connection is alive even when no task happens to be running (task deltas
     * alone would be silent on a quiet desktop).
     */
    var liveness = {
        inboundFrames: 0,
        pairAcks: 0,
        outboundFrames: 0,
        socketsOpened: 0,
        socketsClosed: 0,
        /**
         * Ticks executed while the app was backgrounded, and how long after
         * going background the first one came. The delay is the whole point: a
         * heartbeat that runs in the background starts ticking at once, while a
         * link that only came back with the foreground produces its "background"
         * ticks in one burst right at the end of the window — the counters alone
         * cannot tell those apart.
         */
        backgroundTicks: 0,
        backgroundFirstTickDelayMs: -1,
        startedAt: Date.now(),
        lastInboundAt: 0
    };

    function reportLiveness() {
        var socket = activeSocket;
        post('liveness', {
            inboundFrames: liveness.inboundFrames,
            pairAcks: liveness.pairAcks,
            outboundFrames: liveness.outboundFrames,
            socketsOpened: liveness.socketsOpened,
            socketsClosed: liveness.socketsClosed,
            backgroundTicks: liveness.backgroundTicks,
            backgroundFirstTickDelayMs: liveness.backgroundFirstTickDelayMs,
            lastInboundAgoMs: liveness.lastInboundAt ?
                Date.now() - liveness.lastInboundAt : -1,
            paired: relayPaired,
            socketState: socket ? socket.readyState : -1,
            deviceSidKnown: deviceSid !== null,
            injectedReady: true
        });
    }

    function startHeartbeat() {
        setInterval(function () {
            heartbeatTick();
        }, HEARTBEAT_MS);
    }

    /**
     * One heartbeat: report the counters, then keep the desktop's pairing state
     * warm with a pair_status_query.
     *
     * Returns false when the tick was dropped by the gap guard.
     */
    function heartbeatTick() {
        var nowMs = Date.now();
        if (nowMs - lastTickAt < MIN_TICK_GAP_MS) {
            return false;
        }
        lastTickAt = nowMs;
        pagelogWindowCount = 0;
        if (!appForeground) {
            liveness.backgroundTicks += 1;
            if (liveness.backgroundFirstTickDelayMs < 0 && backgroundStartedWallMs > 0) {
                liveness.backgroundFirstTickDelayMs = nowMs - backgroundStartedWallMs;
            }
            // Tier1 后台连续性取证：泵在跳但入站帧停了，说明 renderer 被冻结或
            // 链路半死——这是"后台实况窗断掉/滞后"最可能的形状，留证据行。
            if (liveness.lastInboundAt && nowMs - liveness.lastInboundAt > 60000 &&
                nowMs - lastBackgroundSilenceLoggedAt > 60000) {
                lastBackgroundSilenceLoggedAt = nowMs;
                diag('warn', '后台链路静默 ' +
                    Math.round((nowMs - liveness.lastInboundAt) / 1000) +
                    's（泵仍在跳，入站帧停了）');
            }
        }
        // 卡死看门狗的心跳巡检：覆盖没有进对话信标的卡死（如后台挂起回来、
        // 恢复期页面自己没再发 subscribe）。僵尸订阅检测也在这里：
        // 任务在跑（sessions-index 活动）而页面桥零业务帧 ≥45s、DOM 却健康——
        // 2026-09-13 真机实证的形态（socket 重建后页面 runtime 不重建、零自愈）。
        try {
            var vit = readVitals();
            if (vitalsStalled(vit)) {
                stallArm('心跳巡检: 正文未就绪');
            } else if (vit && vit.chat && zombieSuspected(nowMs)) {
                stallArm('心跳巡检: 僵尸订阅（任务在跑但页面桥 ' + zombieSilenceS +
                    's 零帧）', true);
            } else if (vit && (stallState.armed || stallState.gaveUp)) {
                stallCancel('心跳巡检: 界面已恢复');
            }
        } catch (e) {
            // 巡检失败不影响心跳本身
        }
        reportLiveness();
        reportPerf();
        if (client && client.reportPageRpcWindow) {
            try {
                // What the page itself asked the desktop for, and how long the
                // answer took. This is the only visibility into "opening a task
                // hangs": those requests are the page's, not ours.
                client.reportPageRpcWindow();
            } catch (e) {
                // instrumentation must never break the page
            }
        }
        if (!relayPaired || !deviceSid) {
            return true;
        }
        if (lastPairAckAt && nowMs - lastPairAckAt > STALE_MS) {
            staleTicks += 1;
            if (staleTicks < STALE_PROBES) {
                diag('warn', 'relay 心跳陈旧 ' +
                    Math.round((nowMs - lastPairAckAt) / 1000) + 's，先探测再决定（第 ' +
                    staleTicks + ' 次）');
            } else {
                forceReconnect('心跳陈旧 ' + Math.round((nowMs - lastPairAckAt) / 1000) + 's');
            }
        } else {
            staleTicks = 0;
        }
        // 顶层控制帧，**不能**走 injectPayload：那个函数包成 {type:'data',payload:…}
        // 是数据面的形状，而 relay 的控制帧必须顶层 type——见 sendControlFrame 的注释
        // （真机 2026-09-15：走 injectPayload 的探针从来没被 ack 过，那正是"回后台
        // 立刻断线"的根因）。
        //
        // **只读壳：前台一帧都不发。** 页面自己的 10s 心跳在前台是准的（日志里
        // `页面心跳` 就是 ~1/10s），我们再插一帧只是往它正用着的那条连接上加噪声，
        // 而"壳不许碰页面链路"是这版政策的全部意义。只有退到后台、页面的定时器被
        // Chromium 节流时，才由原生泵补这一帧喂住桌面端的配对状态。
        if (!appForeground && sendControlFrame({
            type: 'pair_status_query',
            device_sid: deviceSid,
            client_ts: nowMs
        })) {
            linkWindow.probes += 1;
        }
        return true;
    }

    /**
     * 只读壳契约（见 SHELL_READ_ONLY）：**永不执行**。只留一行日志说明"本来会在
     * 哪一刻拆线"，因为这句话本身就是页面链路健康的诊断量。
     * 2026-09-16 审计：历史实现（关掉页面 socket 逼它自己重连）已删除——真机定罪
     * 它是自伤（40 秒里拆了 10 次，用户看到的就是"转圈 / 要重连好几次"）。
     */
    function forceReconnect(reason) {
        noteReadOnlyRefusal('重建页面 socket', reason);
        return false;
    }

    /**
     * Native-driven heartbeat (the foreground service drives this while the app
     * is backgrounded, where the interval above cannot run).
     */
    G.__zcodeShellHeartbeat = function () {
        return heartbeatTick();
    };

    // -----------------------------------------------------------------------
    // 5. notification tap -> locate the task (decision D11)
    //
    // The page is an SPA with no documented route for a task, so this matches
    // on the task title and clicks the row. It is the single most fragile
    // feature in the shell and is expected to break when the page is
    // redesigned: it degrades to "the app opened, task not highlighted".
    // -----------------------------------------------------------------------
    var CLICKABLE = {
        A: 1, BUTTON: 1, LI: 1
    };

    function isClickable(el) {
        if (!el || el.nodeType !== 1) {
            return false;
        }
        if (CLICKABLE[el.tagName] === 1) {
            return true;
        }
        var role = el.getAttribute && el.getAttribute('role');
        if (role === 'button' || role === 'option' || role === 'menuitem' || role === 'tab') {
            return true;
        }
        try {
            if (G.getComputedStyle && G.getComputedStyle(el).cursor === 'pointer') {
                return true;
            }
        } catch (e) {
            // ignore
        }
        return false;
    }

    function clickElement(el) {
        var opts = {bubbles: true, cancelable: true, view: G};
        try {
            el.scrollIntoView({block: 'center'});
        } catch (e) {
            // ignore
        }
        // Full pointer sequence: React-style handlers listen on pointerdown /
        // mousedown, plain handlers on click.
        var events = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
        for (var i = 0; i < events.length; i++) {
            var type = events[i];
            var ev;
            try {
                if (type.indexOf('pointer') === 0 && typeof G.PointerEvent === 'function') {
                    ev = new G.PointerEvent(type, opts);
                } else if (type.indexOf('mouse') === 0) {
                    ev = new G.MouseEvent(type, opts);
                } else {
                    ev = new G.MouseEvent('click', opts);
                }
                el.dispatchEvent(ev);
            } catch (e) {
                // ignore a single failed event type
            }
        }
    }

    function findTaskElement(title) {
        if (!title) {
            return null;
        }
        // Prefer leaf elements so we click the innermost node carrying the
        // title rather than a whole container.
        var nodes = document.querySelectorAll('body *');
        var exact = null;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.childElementCount !== 0) {
                continue;
            }
            var text = (el.textContent || '').trim();
            if (text !== title) {
                continue;
            }
            var ancestor = el;
            for (var depth = 0; depth < 6 && ancestor; depth++) {
                if (isClickable(ancestor)) {
                    return ancestor;
                }
                ancestor = ancestor.parentElement;
            }
            if (!exact) {
                exact = el;
            }
        }
        return exact;
    }

    var locateTimer = null;

    G.__zcodeShellLocateTask = function (sessionId, title) {
        var attempts = 0;
        if (locateTimer) {
            clearInterval(locateTimer);
            locateTimer = null;
        }
        locateTimer = setInterval(function () {
            attempts += 1;
            try {
                var el = findTaskElement(title);
                if (el) {
                    clickElement(el);
                    diag('info', '已定位任务: ' + title);
                    clearInterval(locateTimer);
                    locateTimer = null;
                    return;
                }
            } catch (e) {
                diag('warn', '定位脚本出错: ' + e);
                clearInterval(locateTimer);
                locateTimer = null;
                return;
            }
            if (attempts >= 10) {
                // Give up quietly: the app is open, the user can find the task.
                diag('info', '未能在页面上定位任务(页面可能已改版): ' + title);
                clearInterval(locateTimer);
                locateTimer = null;
            }
        }, 300);
        return true;
    };

    // -----------------------------------------------------------------------
    // 5b. 进对话铁判准：5 s 内对话详情没就绪 → 直接刷新
    //
    // 用户 2026-09-13 拍板的口径（"5 s 内加载不出对话详情就是铁判准，直接触发页面
    // 刷新"）。判据不看 DOM 长什么样，只看**页面自己有没有把对话内容拿到**：
    //
    //   就绪（信标窗内任一成立即算，此后本轮不再干预）——
    //     1. 页面日志 v4.conversation.store.connect.completed（页面的
    //        conversation store 连上、快照已应用；空会话同样会走到这里，
    //        所以"新建任务"不会被误刷）；
    //     2. 页面日志 v4.conversation.subscribe.acknowledged / .activated；
    //     3. DOM 时间线已有行（rows > 0）；
    //     4. 页面桥在信标之后收到过任何入站帧（桌面端确实回话了）。
    //   未就绪 → 到点直接 reload。**跳过"轻推"这一档**：轻推关的是共享 socket，
    //   而"桌面端不回话"的形态里换一条连接才有用（旧 3 s DOM 判定把轻推串在刷新
    //   前面，实测要 ~9 s 才刷，且标题回退态还要被轻推绕一圈）。
    //
    // 终止性只留两件（其余守卫按用户要求全部删掉：不看草稿、不看前台、不做标题
    // 交叉验证）：15 s 最小刷新间隔（跨 reload 由 sessionStorage 记），以及连续
    // 刷新上限（到顶放弃，任何就绪信号到达即复位）。没有间隔，刚 reload 的新页
    // 会在 +5 s 被判"没内容"从而无限刷新；有了它，卡住的页面每 ~15 s 重来一次，
    // 加载成功的页面一次都不刷。
    // -----------------------------------------------------------------------
    var FALLBACK_TITLE_TEXT = '新建任务';
    var FALLBACK_PLACEHOLDER_PREFIX = '向 ZCode 提问';
    var FALLBACK_PLACEHOLDER_KNOWN = {
        '继续输入以排队后续修改': 1,
        '提出后续修改要求': 1,
        '初始化任务中': 1
    };
    // 两种进对话的页面行为都观测到过：任务列表点进去发 subscribeConversationV4
    // （D1-b，10:04）；会话视图打开/恢复则只发 conversationRowsRangeV4（13:08 实录，
    // 全程无 subscribe——按单一信标武装会整窗漏掉）。两个都当进对话信标。
    var FALLBACK_ENTRY_METHODS = {
        'zcode-agent.subscribeConversationV4': 1,
        'zcode-agent.conversationRowsRangeV4': 1
    };
    /** 进入会话后先给页面 3s 自己恢复；进行中流文本连续 5s 不更新也触发同一恢复梯子。 */
    var FALLBACK_CHECK_MS = 3000;
    /**
     * "前台对话流多久没新帧算异常"。
     *
     * **5s → 60s（2026-09-15 真机定案）**。桌面端的推送本来就稀疏（README「已知问题 B」：
     * 页面拿到快照后整段只有心跳帧是常态），5 秒静默被判成"卡死"的直接后果是看门狗
     * **每秒布防、每 4 秒撤防一轮、永不停止**，其中一部分还会走到"轻推"（关 socket）。
     * 真机实测 15 分钟内：布防 19 次 / 轻推 11 次 / socket close 13 次——用户看到的
     * "进对话要重连好多次才出来"和"发送按钮一直转圈"都是它。
     */
    var CONVERSATION_STALE_MS = 60000;
    var FALLBACK_RELOAD_GAP_MS = 15000;
    var FALLBACK_STORE_AT = 'zcodeShellFastRefreshAt';

    var fallbackTimer = null;
    var fallbackState = {
        lastReloadAt: 0,
        beaconAt: 0,
        beaconGen: 0,
        readyAt: 0,
        readyBy: '',
        chatView: false,
        nudgedAt: 0
    };
    var clientGenSeq = 0;

    function fallbackElementVisible(el) {
        try {
            if (typeof el.getBoundingClientRect !== 'function') {
                // No geometry in this environment (tests, old engines):
                // assume visible — the other guards still apply.
                return true;
            }
            var rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
        } catch (e) {
            return true;
        }
    }

    function findHeaderText() {
        // Whether the fallback title text is visible at all, and how literally
        // it was found: 'leaf' = a childless element carries exactly that text
        // (the normal case), 'deep' = only a container with children matches
        // (the header wraps the title in spans/icons — still a match), '' = no
        // visible occurrence.
        var nodes = document.querySelectorAll('body *');
        var deep = false;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if ((el.textContent || '').trim() !== FALLBACK_TITLE_TEXT) {
                continue;
            }
            if (el.childElementCount === 0) {
                if (fallbackElementVisible(el)) {
                    return 'leaf';
                }
                continue;
            }
            if (!deep && fallbackElementVisible(el)) {
                deep = true;
            }
        }
        return deep ? 'deep' : '';
    }

    function elementDisabled(el) {
        try {
            if (el.disabled === true) {
                return true;
            }
            if (el.getAttribute && el.getAttribute('disabled') !== null) {
                return true;
            }
            if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') {
                return true;
            }
        } catch (e) {
            // fall through to false
        }
        return false;
    }

    /**
     * The chat composer: identified by one of its known placeholders, falling
     * back to any disabled textarea (the grey state may carry no placeholder
     * at all). Returns null when nothing looks like the composer.
     */
    function composerElement() {
        var nodes = document.querySelectorAll('body *');
        var disabledOne = null;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var tag = el.tagName;
            if (tag !== 'TEXTAREA' && tag !== 'INPUT') {
                continue;
            }
            var placeholder = el.getAttribute ? el.getAttribute('placeholder') : null;
            if (typeof placeholder === 'string' &&
                (placeholder.indexOf(FALLBACK_PLACEHOLDER_PREFIX) === 0 ||
                    FALLBACK_PLACEHOLDER_KNOWN[placeholder] === 1)) {
                return el;
            }
            if (disabledOne === null && elementDisabled(el)) {
                disabledOne = el;
            }
        }
        return disabledOne;
    }

    function storeGet(key) {
        try {
            return G.sessionStorage ? G.sessionStorage.getItem(key) : null;
        } catch (e) {
            return null;
        }
    }

    function storeSet(key, value) {
        try {
            if (G.sessionStorage) {
                G.sessionStorage.setItem(key, value);
            }
        } catch (e) {
            // Storage can be denied; the in-memory stamp still bounds one load.
        }
    }

    /**
     * 进对话信标：重置 5 s 铁判准窗（快速切换时以最后一次信标为准）。
     * 页面卡住时它每 ~10 s 重发一次同样的请求，信标因此会连续重来——那正是
     * 我们希望它重来的形态：每次都重新给 5 s，就绪信号一到就停。
     */
    function scheduleFallbackCheck() {
        if (fallbackTimer) {
            clearTimeout(fallbackTimer);
        }
        fallbackTimer = setTimeout(fallbackCheck, FALLBACK_CHECK_MS);
        fallbackState.beaconAt = Date.now();
        fallbackState.beaconGen = client ? client.gen : 0;
        fallbackState.readyAt = 0;
        fallbackState.readyBy = '';
        fallbackState.nudgedAt = 0;
    }

    /**
     * 页面自述的"对话内容已就绪"：store 连上（快照已应用）或订阅确认。
     * 这是铁判准的第一手信号——比任何 DOM 形态都靠前，且空会话同样会走到
     * store.connect.completed，所以判"就绪"不会误伤新建任务。
     */
    function noteConversationReady(reason) {
        if (!fallbackState.beaconAt) {
            return;
        }
        if (Date.now() - fallbackState.beaconAt > FALLBACK_CHECK_MS * 4) {
            // 早已过了窗口（这轮已经判过/刷过），不再回填。
            return;
        }
        if (!fallbackState.readyAt) {
            fallbackState.readyAt = Date.now();
            fallbackState.readyBy = reason;
        }
    }

    /** 本轮信标窗内的就绪证据；空数组＝对话详情没到。 */
    function conversationReadyReasons() {
        var state = fallbackState;
        var reasons = [];
        if (state.readyAt && state.readyAt >= state.beaconAt) {
            reasons.push(state.readyBy || '页面自述已就绪');
        }
        try {
            var v = readVitals();
            if (v && v.rows > 0) {
                reasons.push('时间线 ' + v.rows + ' 行');
            }
        } catch (e) {
            // 体征读不到不算证据，也不算反证
        }
        var clientNow = client;
        var conversationAt = clientNow && typeof clientNow.lastPageConversationTrafficAt === 'function' ?
            clientNow.lastPageConversationTrafficAt() : 0;
        if (conversationAt && conversationAt >= state.beaconAt) {
            reasons.push('对话流有新帧');
        }
        return reasons;
    }

    /**
     * 铁判准判定点（信标 +5 s）。就绪 → 本轮收工；未就绪 → 直接刷新页面。
     * 链路在此期间换代（页面已经在自己重连）就跳过本轮：那种形态下页面正在
     * 恢复，插一刀只会更慢；换代后的新信标会重新给 5 s。
     */
    function fallbackCheck() {
        if (fallbackTimer) {
            // 自然到期之外的手动触发（测试/控制台）也要清掉挂起的定时器，
            // 否则同一检查会跑两次。
            clearTimeout(fallbackTimer);
        }
        fallbackTimer = null;
        var state = fallbackState;
        if (!state.beaconAt) {
            return;
        }
        var clientNow = client;
        if (state.beaconGen && (!clientNow || clientNow.gen !== state.beaconGen)) {
            diag('debug', '进对话 5s 判定：期间链路换代（' +
                (clientNow ? '第 ' + state.beaconGen + '→' + clientNow.gen : '客户端已销毁') +
                '），本轮不判（页面正按自己的梯子重连）');
            return;
        }
        var reasons = conversationReadyReasons();
        if (reasons.length) {
            diag('debug', '进对话 ' +
                Math.round((Date.now() - state.beaconAt) / 1000) + 's 已就绪（' +
                reasons.join('、') + '）');
            stallCancel('进对话 5s 判定：对话详情已就绪');
            return;
        }
        var el = '';
        try {
            var header = findHeaderText();
            el = header ? '标题回退' : '';
            var composer = composerElement();
            if (composer && elementDisabled(composer)) {
                el = el ? el + '+输入框未就绪' : '输入框未就绪';
            }
        } catch (e) {
            el = 'DOM 不可读';
        }
        diag('warn', '进对话 5s 未出对话详情' + (el ? '（' + el + '）' : '') +
            '：先尝试最小内推（重建页面 relay 连接）');
        if (!state.nudgedAt) {
            state.nudgedAt = Date.now();
            nudgeReconnect('进对话 5s 未出详情');
            fallbackTimer = setTimeout(fallbackCheck, STALL_RELOAD_MS);
            return;
        }
        diag('warn', '最小内推后仍未出对话详情：整体刷新页面');
        reloadForMissingConversation('进对话 5s 未出对话详情');
    }

    /**
     * 铁判准的刷新动作。与卡死看门狗共享同一套终止性状态（15 s 间隔 + 连续
     * 上限 + 就绪即复位），但**不经过轻推**：见 5b 节头部的决策说明。
     */
    function reloadForMissingConversation(reason) {
        // 和"轻推"同源的一条路：进对话没看到内容就重载页面。真机里它同样是拿
        // "我们的观测"去否定"页面的事实"——只读壳下只记录，不动页面。
        // 2026-09-16 审计：历史实现（两级刷新 + sessionStorage 限流 + 连续上限）已删除。
        stallState.gaveUp = true;
        diag('warn', '只读壳：不因"' + reason + '"重载页面（只记录，等页面自己恢复）');
        postPageVitals('giveup');
    }

    /** Upload-named page RPCs get explicit lines: that is the file-send chain. */
    var UPLOAD_RPC_RE = /upload|attachment|artifact/i;

    /**
     * 进对话信标（页面自己发出的会话请求）。已有在跑的判定窗就不重置——
     * 重置会把"5 s 铁判准"变成"5 s + 每次重试顺延"：真机实测正是这样拖到 ~9 s
     * 才刷（页面 transport 的 await 门控让订阅请求晚 4 s 才发出去，信标跟着晚）。
     * 窗口只由"进入对话"那一刻起算，DOM 视图信标通常几百毫秒内就到。
     */
    function noteConversationEntryBeacon(method, args) {
        var age = fallbackState.beaconAt ? Date.now() - fallbackState.beaconAt : -1;
        var session = args && args.sessionId ? String(args.sessionId) : '';
        if (age >= 0 && age < FALLBACK_CHECK_MS) {
            diag('debug', '进对话信标 ' + method + (session ? '（' + session + '）' : '') +
                '落在此前已武装的 5s 窗内，不顺延');
            return;
        }
        diag('debug', '进对话信标 ' + method + (session ? '（' + session + '）' : '') +
            '→ 武装 5s 铁判准');
        scheduleFallbackCheck();
    }

    function notePageRpcCall(call) {
        if (!call) {
            return;
        }
        if (UPLOAD_RPC_RE.test(call.name)) {
            diag('info', '页面上传调用开始：' + call.name);
        }
        if (FALLBACK_ENTRY_METHODS[call.name]) {
            noteConversationEntryBeacon(call.name, call.args);
        }
    }

    function notePageRpcResult(result) {
        if (!result || !UPLOAD_RPC_RE.test(result.name)) {
            return;
        }
        if (result.ok) {
            diag('info', '页面上传调用完成 ' + Math.round(result.cost || 0) + 'ms：' + result.name);
        } else {
            diag('warn', '页面上传调用失败 ' + Math.round(result.cost || 0) + 'ms：' + result.name +
                (result.message ? ' · ' + String(result.message).substring(0, 120) : ''));
        }
    }

    /** Test and console debugging surface for the fast refresh. */
    G.__zcodeShellFallback = {
        note: notePageRpcCall,
        beacon: noteConversationEntryBeacon,
        check: fallbackCheck,
        ready: noteConversationReady,
        readyReasons: conversationReadyReasons,
        state: function () {
            return fallbackState;
        },
        timer: function () {
            return fallbackTimer;
        },
        arm: stallArm,
        cancel: stallCancel,
        vitals: readVitals,
        fire: stallFire,
        stall: function () {
            return stallState;
        }
    };

    // -----------------------------------------------------------------------
    // 5c. 卡死看门狗（stall watchdog，2026-09-12 用户拍板的三级处置）
    //
    // 需求：卡死 3s 内必须有可见的恢复动作，动作从轻到重，刷新是最后一档。
    //
    //   phase 0（布防后 3s）→ 先看桌面端是否还在下发：在发就跳过干预（内容在
    //     路上，掐 socket 只会更慢）；没在发就"轻推"——关闭共享 relay socket，
    //     让页面走它自己的重连-重订阅梯子。可见性劫持（第 1 节）摘掉了页面的
    //     pagehide/visibilitychange/freeze 监听，页面自己的 suspend→recover
    //     快路在本壳里永远不可达，所以"借页面自身恢复"只剩 socket 这一条通路；
    //     页面对 socket 重建有一整套设计好的恢复（重连→换代→重订阅），代价
    //     约 2-5s，不丢 UI，快照审计（docs/05 @4700682/@2257650）证实。
    //   phase 1（再 3s）→ 仍无内容才 reload。连续 2 次到顶放弃（sessionStorage
    //     计数跨 reload 边界），恢复信号到达后自动复位；15s 刷新间隔保留。
    //
    // 撤防/复位信号：DOM 就绪（标题+输入框）、页面日志的订阅确认
    // （v4.conversation.subscribe.acknowledged / store.connect.completed）、
    // 心跳巡检恢复正常。错误横幅（chat-error-banner）出现即撤防——那是页面
    // 在正常报错，刷新解决不了。
    // -----------------------------------------------------------------------
    var STALL_NUDGE_MS = 3000;
    var STALL_RELOAD_MS = 3000;
    var STALL_RELOAD_CAP = 2;
    /**
     * 撤防后多久之内不再重新布防。
     *
     * 真机 2026-09-15：20:18–20:50 这一段看门狗布防 **267 次**、撤防 265 次——
     * 全部是同一对理由在打转（`前台对话流连续 Ns 无新动态` → 3s 后 `判定时已恢复`，
     * 下一跳再布防）。原因是两条判据看的东西不同：巡检看的是"我们有没有看到对话帧"，
     * 判定看的是"DOM 健不健康"，而"我们看不到帧、页面却是好的"恰恰是常见状态。
     * 只读壳下这个看门狗已经不能动手了，它的价值只剩"什么时候我们会想动手"这条
     * 诊断量，所以给它一个退避，别用 537 行日志把真正的事件淹掉。
     */
    var STALL_REARM_COOLDOWN_MS = 60000;
    var STALL_STORE_RELOADS = 'zcodeShellStallReloads';
    /** 布防的页面日志事件：仅梯子已耗尽的终态（retry_scheduled 是页面还在自救，不动）。 */
    var PAGE_LOG_STALL_EVENTS = {
        'v4.conversation.store.connect.failed': 1,
        'v4.conversation.subscribe.failed': 1
    };
    var PAGE_LOG_RECOVER_EVENTS = {
        'v4.conversation.store.connect.completed': 1,
        'v4.conversation.subscribe.acknowledged': 1,
        'v4.conversation.subscribe.activated': 1
    };
    var stallState = {
        armed: false,
        phase: 0,
        timer: null,
        since: 0,
        deadline: 0,
        armReason: '',
        gaveUp: false,
        lastReloadAt: 0,
        skipNudge: false,
        streamStalled: false,
        /** 上次撤防的时刻（见 STALL_REARM_COOLDOWN_MS）。 */
        lastCancelAt: 0,
        // 连续刷新计数的内存权威；sessionStorage 是跨 reload 边界的镜像
        // （storage 可能被拒，内存值仍保住单次加载内的上限语义）。
        reloadCount: parseInt((function () {
            try {
                return G.sessionStorage ? G.sessionStorage.getItem(STALL_STORE_RELOADS) : null;
            } catch (e) {
                return null;
            }
        })(), 10) || 0
    };

    /**
     * DOM 体征（场景三观察锚，快照 docs/05 第 4.6 节的标记全来自这里）。
     * 只读属性，不碰布局；任何读取失败都归一成 null。
     */
    function readVitals() {
        try {
            var tl = document.querySelector('[data-v4-timeline-scroll]');
            var composer = composerElement();
            return {
                chat: !!document.querySelector('[data-mobile-page="chat"]'),
                timeline: !!tl,
                rows: tl ? parseInt(tl.getAttribute('data-row-count') || '0', 10) || 0 : -1,
                following: tl ? tl.getAttribute('data-following') : null,
                loadingOlder: tl ? tl.getAttribute('data-loading-older') === 'true' : false,
                loading: !!document.querySelector('[data-zcode-chat-loading-animate]'),
                errorBanner: !!document.querySelector('[data-testid="chat-error-banner"]'),
                liveTail: !!document.querySelector('[data-v4-running-live-tail]'),
                composerDisabled: !!(composer && elementDisabled(composer)),
                fallbackTitle: findHeaderText() !== ''
            };
        } catch (e) {
            return null;
        }
    }

    /** 卡态判定：聊天页在、无错误横幅、且（输入框灰着 或 标题回退）。 */
    function vitalsStalled(v) {
        if (!v || !v.chat || v.errorBanner) {
            return false;
        }
        return v.composerDisabled || v.fallbackTitle;
    }

    function postPageVitals(why) {
        try {
            var v = readVitals();
            if (v) {
                post('pagevitals', {why: why, vitals: v});
            }
        } catch (e) {}
    }

    var ZOMBIE_FRAME_SILENCE_MS = 45000;
    var ZOMBIE_ACTIVITY_FRESH_MS = 60000;
    var zombieSilenceS = 0;

    /**
     * 僵尸订阅指纹（2026-09-13 真机实证）：桌面端对本会话的任务仍在产出
     * （sessions-index 里 running 任务的 lastActivityAt 在 60s 内推进），
     * 而页面桥 ≥45s 没有收到任何业务帧，且 DOM 完全健康——三方都以为
     * 别人在办。socket 重建后页面 runtime 不重建是根因。
     */
    function zombieSuspected(nowMs) {
        var clientNow = client;
        if (!clientNow || typeof clientNow.lastPageBridgeTrafficAt !== 'function' ||
            typeof clientNow.pageBridgeSessionIds !== 'function') {
            return false;
        }
        // 桌面活着才布防：pair ack 不是 matched（桌面掉线/休眠）时刷新页面无意义。
        if (!relayPaired) {
            return false;
        }
        var lastTraffic = Math.max(clientNow.lastPageBridgeTrafficAt() || 0,
            clientNow.bornAt || 0);
        var silence = nowMs - lastTraffic;
        if (silence < ZOMBIE_FRAME_SILENCE_MS) {
            return false;
        }
        var ids = clientNow.pageBridgeSessionIds();
        for (var key in ids) {
            var at = runningActivityByKey[key] || 0;
            if (at && nowMs - at < ZOMBIE_ACTIVITY_FRESH_MS) {
                zombieSilenceS = Math.round(silence / 1000);
                return true;
            }
        }
        return false;
    }

    function stallArm(reason, skipNudge) {
        if (stallState.gaveUp) {
            return;
        }
        var v = readVitals();
        if (v && v.errorBanner) {
            return;
        }
        var nowMs = Date.now();
        if (!stallState.armed) {
            stallState.armed = true;
            stallState.phase = 0;
            stallState.since = nowMs;
            stallState.deadline = nowMs + STALL_NUDGE_MS;
            stallState.skipNudge = skipNudge === true;
        } else if (stallState.phase === 0) {
            // 首判没到前允许顺延；phase 1 起页面自己的 10s 重试信标会不断
            // 进来，绝不能让它们把刷新判定无限顺延。
            stallState.deadline = nowMs + STALL_NUDGE_MS;
            if (skipNudge === true) {
                stallState.skipNudge = true;
            }
        }
        stallState.armReason = reason;
        if (!stallState.timer) {
            stallState.timer = setTimeout(stallFire,
                Math.max(0, stallState.deadline - nowMs));
        }
        diag('info', '卡死看门狗布防（' + reason + '）phase=' + stallState.phase +
            '，' + Math.round(Math.max(0, stallState.deadline - nowMs) / 1000) + 's 后判定');
        postPageVitals('arm');
    }

    function stallFire() {
        stallState.timer = null;
        if (!stallState.armed) {
            return;
        }
        if (stallState.skipNudge) {
            // 僵尸布防的"恢复"判据是帧流，不是 DOM——僵尸的 DOM 本来就健康，
            // 用 vitalsStalled 判会在 3s 判定点把自己撤掉（首轮真机实测踩中）。
            if (!zombieSuspected(Date.now())) {
                stallCancel('判定时帧流已恢复');
                return;
            }
        } else {
            var v = readVitals();
            // 只在体征"可读且健康"时撤防；读不到（DOM 半拆/极端环境）不算恢复，
            // 继续走梯子——布防理由本身已经是证据。
            if (v && !vitalsStalled(v)) {
                stallCancel('判定时已恢复');
                return;
            }
        }
        if (stallState.phase === 0) {
            stallState.phase = 1;
            if (stallState.skipNudge) {
                // 僵尸订阅形态：轻推（关 socket）只会让页面重连配对，runtime
                // 照旧不重建（2026-09-13 实测），直接进刷新判定。
                diag('info', '卡死看门狗：僵尸订阅形态，跳过轻推直达刷新判定');
            } else {
                var trafficAt = client && typeof client.lastPageBridgeTrafficAt === 'function' ?
                    client.lastPageBridgeTrafficAt() : 0;
                if (trafficAt >= stallState.since) {
                    diag('info', '卡死看门狗：桌面端仍在下发（内容在路上），跳过轻推');
                } else if (Date.now() - lastForcedReconnectAt <= RECONNECT_MIN_GAP_MS) {
                    // **这里以前缺了一道门**：`nudgeReconnect` 头顶的注释写着"与
                    // forceReconnect 共享 180s 限流时钟"，但它只写 `lastForcedReconnectAt`
                    // 从不读——于是"布防→判定→轻推"可以每 4 秒一轮无限循环，每次都把
                    // 页面的 socket 拆掉（2026-09-15 真机：11 次轻推）。
                    // 受限流时不轻推、**也不进刷新判定**：刚干预过就不该再加一刀。
                    stallCancel('距上次链路干预不足 ' +
                        Math.round(RECONNECT_MIN_GAP_MS / 1000) + 's，不重复轻推');
                    return;
                } else {
                    nudgeReconnect(stallState.armReason || '页面无进展');
                }
            }
            stallState.deadline = Date.now() + STALL_RELOAD_MS;
            stallState.timer = setTimeout(stallFire, STALL_RELOAD_MS);
            return;
        }
        stallReloadIfAllowed();
    }

    /**
     * 轻推：关掉共享 socket，恢复归页面。
     *
     * ⚠️ **限流必须在这里（唯一收口），不能只放在某个调用方**——2026-09-15 真机教训：
     * 上一版只在看门狗那条路上加了限流，而"进对话 5s 未出详情"这条**直接调用**它，
     * 于是形成一个正反馈环：
     *
     *     关 socket → 页面重连并重新 subscribeConversationV4（那正是"进对话信标"）
     *     → 5s 窗口重新武装 → 新连接 5 秒内必然加载不完 → 再关 socket → …
     *
     * 实测每 ~4 秒一轮、连续 10 次，用户看到的就是"点进对话要重连好多次才出来"，
     * 以及"发消息一直转圈"（socket 在发送途中被拆）。**每一次轻推都保证了下一次失败。**
     *
     * 与心跳陈旧路径的 [forceReconnect] 共享同一个 180s 时钟——这也是本函数注释里
     * 一直写着、但此前没有实现的意图：一次干预之后，这条链路上 180 秒内不再开刀。
     */
    function nudgeReconnect(reason) {
        // 只读壳：连"轻推"也不做。这条路上最恶性的正反馈（关 socket→页面重连
        // →5s 窗重新武装→再关）从此不存在，日志里只留一行"本会在何时动手"。
        // 2026-09-16 审计：历史实现（关掉页面 socket 逼它重连）已删除。
        noteReadOnlyRefusal('轻推页面 socket', reason);
        return false;
    }

    function stallReloadIfAllowed() {
        // 只读壳：**永不自动重载**。重载会把页面自己的订阅、视图、滚动位置全部推倒
        // （用户回来看到的是"它自己在重连/重载"），而它换来的只是一次握手——收益远
        // 小于代价。看门狗到此为止，只把状态记清楚。
        // 2026-09-16 审计：历史实现（刷新间隔 + 连续上限 + location.reload）已删除。
        stallState.gaveUp = true;
        stallState.armed = false;
        diag('warn', '只读壳：不自动重载页面（看门狗停止干预，等页面自己恢复）');
        postPageVitals('giveup');
    }

    function stallCancel(reason) {
        var wasActive = stallState.armed || stallState.gaveUp;
        if (stallState.timer) {
            clearTimeout(stallState.timer);
            stallState.timer = null;
        }
        stallState.armed = false;
        stallState.phase = 0;
        stallState.skipNudge = false;
        // 记住撤防时刻：巡检据此退避（见 STALL_REARM_COOLDOWN_MS），
        // 否则"布防→3s 判定恢复→立刻再布防"会每 4 秒刷一轮日志。
        stallState.lastCancelAt = Date.now();
        if (stallState.gaveUp) {
            diag('info', '卡死看门狗解除放弃态: ' + reason);
        }
        stallState.gaveUp = false;
        if (wasActive) {
            stallState.reloadCount = 0;
            storeSet(STALL_STORE_RELOADS, '0');
            diag('info', '卡死看门狗撤防: ' + reason);
            postPageVitals('cancel');
        }
    }

    function conversationStreamStallTick() {
        try {
            if (!appForeground || !relayPaired || !fallbackState.chatView || stallState.gaveUp) {
                return;
            }
            var at = client && typeof client.lastPageConversationTrafficAt === 'function' ?
                client.lastPageConversationTrafficAt() : 0;
            if (!at || Date.now() - at < CONVERSATION_STALE_MS) {
                return;
            }
            if (!stallState.armed &&
                Date.now() - stallState.lastCancelAt > STALL_REARM_COOLDOWN_MS) {
                stallArm('前台对话流连续 ' + Math.round((Date.now() - at) / 1000) + 's 无新动态', false);
            }
        } catch (e) {
            // Recovery monitoring must never affect the page.
        }
    }

    function startConversationStreamMonitor() {
        // 同上：被动旁观关掉时，这个 1s 一跳的卡死巡检也一并停掉（它本来就是围着
        // 逐帧观测与看门狗转的）。
        if (config().passiveObserve === false) {
            return;
        }
        if (G.__zcodeShellConversationMonitor) return;
        G.__zcodeShellConversationMonitor = setInterval(conversationStreamStallTick, 1000);
    }


    function notePageLogEvent(name) {
        if (PAGE_LOG_STALL_EVENTS[name]) {
            if (vitalsStalled(readVitals())) {
                stallArm('页面日志 ' + name);
            } else {
                diag('debug', '页面日志失败事件（当前界面无卡态，不布防）: ' + name);
            }
        } else if (PAGE_LOG_RECOVER_EVENTS[name]) {
            noteConversationReady(name);
            stallCancel('页面日志 ' + name);
        }
    }

    // -----------------------------------------------------------------------
    // 5d. 诊断指令入口（adb 驱动的测试点）
    //
    //   adb shell am start -n com.zcode.remote/.MainActivity \
    //       -a com.zcode.remote.action.DIAG --es diag_cmd kick_test|l1_test|vitals
    //
    //   vitals   读一次 DOM 体征并落日志；
    //   l1_test  手动触发一次轻推（验证 socket 关闭→页面自愈链路）；
    //   kick_test Tier2 可行性实验：用页面同款 URL+auth_init 开第二条
    //            WebSocket，观察 relay 的 KICK/takeover 语义（第二条是被
    //            接纳还是把旧连接踢掉）。全部结果走 diag/pagelog 落日志，
    //            实验连接 30s 后自动关闭。
    // -----------------------------------------------------------------------
    function relayKickTest() {
        if (!lastRelayUrl) {
            diag('warn', 'KICK 实验：没有已知的 relay URL（本轮注入晚于建线？）');
            return false;
        }
        diag('warn', 'KICK 实验：用同款凭证开第二条 WebSocket → ' + safePath(lastRelayUrl));
        var ws;
        try {
            ws = new NativeWebSocket(lastRelayUrl);
        } catch (e) {
            diag('warn', 'KICK 实验：建线失败 ' + e);
            return false;
        }
        var openedAt = Date.now();
        var TAG = 'KICK实验';
        ws.addEventListener('open', function () {
            diag('warn', TAG + '：新连接 open，重放 auth_init');
            try {
                if (lastAuthInitText) {
                    ws.send(lastAuthInitText);
                } else {
                    diag('warn', TAG + '：没有捕获到 auth_init，无法握手');
                }
            } catch (e) {
                diag('warn', TAG + '：发送失败 ' + e);
            }
        });
        ws.addEventListener('message', function (ev) {
            var text = typeof ev.data === 'string' ? ev.data : '';
            var summary = text ? text.substring(0, 60) : '(空)';
            try {
                var f = JSON.parse(text);
                summary = f.type + (f.pair_status ? ':' + f.pair_status : '') +
                    (f.error ? ':' + f.error : '');
            } catch (e) {}
            diag('warn', TAG + '：入站 ' + summary);
        });
        ws.addEventListener('close', function (ev) {
            diag('warn', TAG + '：新连接关闭 code=' + ev.code + ' clean=' + ev.wasClean +
                '（存活 ' + (Date.now() - openedAt) + 'ms）');
        });
        ws.addEventListener('error', function () {
            diag('warn', TAG + '：新连接 error');
        });
        var old = activeSocket;
        if (old && old.readyState === 1) {
            var onOldClose = function (ev) {
                diag('warn', TAG + '：旧 socket 被关闭 code=' + ev.code +
                    ' clean=' + ev.wasClean + ' ← 旧连接被踢的证据');
            };
            try {
                old.addEventListener('close', onOldClose);
                setTimeout(function () {
                    try {
                        old.removeEventListener('close', onOldClose);
                    } catch (e) {}
                }, 30000);
            } catch (e) {}
        }
        setTimeout(function () {
            try {
                if (ws.readyState === 1) {
                    diag('warn', TAG + '：30s 到点，主动关闭实验连接');
                    ws.close();
                }
            } catch (e) {}
        }, 30000);
        return true;
    }

    // -----------------------------------------------------------------------
    // 5e. degrade 实验（B 路线测试点：不 reload 逼页面全量恢复）
    //
    // 僵尸订阅态（配对健康、runtime 全灭、页面零自愈）下，页面唯一不丢 UI 的
    // 恢复通路是它自己的 bridge-degraded 处理器：匹配当前桥 id → markDegraded
    // → T() → recoverConnection + 重开工作区/任务 → runtime 在新 socket 上重建。
    // 页面桥对象跨 socket 重建存活（getBridgeSessionId 返回旧 id），而壳从被动
    // 观察里记录了同一个 id（pageBridgeSessionIds）——所以可以合成一帧
    // {zcode_type:'bridge-degraded'} 用 MessageEvent 派发到共享 socket 上，
    // 页面自己的 message 监听会把它当服务端帧处理。帧格式与 zcode-protocol.js
    // 解析的完全同源。成功判据：页面日志出现订阅级联（unsubscribe→subscribe→ack）。
    // -----------------------------------------------------------------------
    function relayDegradeTest() {
        var clientNow = client;
        if (!clientNow || typeof clientNow.pageBridgeSessionIds !== 'function') {
            diag('warn', 'degrade实验：协议客户端不在');
            return false;
        }
        var ids = clientNow.pageBridgeSessionIds();
        var keys = Object.keys(ids);
        if (keys.length === 0) {
            diag('warn', 'degrade实验：没有记录到任何页面桥 id（页面还没开过桥？）');
            return false;
        }
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            diag('warn', 'degrade实验：没有活动 socket');
            return false;
        }
        diag('warn', 'degrade实验：向 ' + keys.length + ' 个页面桥注入 bridge-degraded（同帧派发）');
        var dispatched = 0;
        for (var i = 0; i < keys.length; i += 1) {
            var key = keys[i];
            var envelope = JSON.stringify({
                type: 'data',
                payload: {
                    zcode_type: 'bridge-degraded',
                    bridgeSessionId: ids[key],
                    reason: 'shell-degrade-test'
                },
                client_ts: Date.now()
            });
            try {
                var ev = new G.MessageEvent('message', {data: envelope});
                socket.dispatchEvent(ev);
                dispatched += 1;
                diag('warn', 'degrade实验：已派发 [' + key + '] bridgeSessionId=' + ids[key]);
            } catch (e) {
                diag('warn', 'degrade实验：派发失败 [' + key + ']: ' + e);
            }
        }
        diag('warn', 'degrade实验：派发完成 ' + dispatched + '/' + keys.length +
            '，成功判据=页面日志出现订阅级联（unsubscribe→subscribe→ack）');
        return dispatched > 0;
    }

    // -----------------------------------------------------------------------
    // 后台失速自愈：推动页面走**它自己**的恢复路径（2026-09-16 实验件）
    //
    // 现场（真机 2026-09-15 23:09–23:33，v129 只读壳，22 分钟不间断采样）：
    // 退后台约 60s 后入站帧与 `链路 ack` **同时**归零，而页面那条 socket 的
    // `readyState` 始终 1（OPEN）、`paired` 始终 true —— 客户端视角的**僵尸连接**：
    // 远端不再回任何东西，也没有任何 close 事件。页面自己在 hidden 时按设计挂起
    // （`suspend()` = stopHeartbeat + 清看门狗），于是它永远不知道链路已经死了；
    // 而回前台时页面的 `recoverConnection()` 一拨就回来，证明"重拨"本身就是解药。
    //
    // 所以这里**不重载、不替页面持连接**，只推动页面走它自己的恢复路径：
    //   event : window 上派发合成 `online` —— 页面统一生命周期 observer 的
    //           `onRecover('online')` → `recoverConnection()` → `reconnectNow()`。
    //           它只对"确实挂起过"的页面响应（observer 内部标志），后台若页面
    //           没有挂起，这一条是空操作。
    //   close : 直接 close 页面那条 socket —— 页面 close 处理器在非挂起态会走它
    //           自己的 `scheduleReconnect` 梯子（0.5s 起退避）。
    //   auto  : 先派发 event；2.5s 后若 socket 身份未变且仍 OPEN，再补一次 close。
    //           默认值：两种页面状态各走一条，且只走一条。
    //
    // 这条写操作的两句自问（「实现要点」14 的规矩）：
    //   ① 页面自己做不到这件事吗？——做不到：它不知道自己已经瞎了（没有入站帧
    //      就没有任何信号，看门狗又被自己的 suspend 清掉了）。
    //   ② 怎么知道它已经失败了？——"完全没有任何入站帧"持续 ≥35s，而壳每 10s
    //      还在发 probe：健康的链路上 probe 一定有 ack（真机健康窗 `链路 ack 1~5/10s`），
    //      连 ack 都没有就是链路已死，不是"桌面端安静"。
    // -----------------------------------------------------------------------

    /** 一行链路现场（只读，供日志与判据用）。 */
    function describeRelay() {
        var states = [];
        for (var i = 0; i < sockets.length; i++) {
            states.push(sockets[i].readyState);
        }
        return 'socket=' + (activeSocket ? activeSocket.readyState : -1) +
            ' sockets=[' + states.join(',') + ']' +
            ' paired=' + relayPaired +
            ' inboundAgo=' + (liveness.lastInboundAt ?
                Math.round((Date.now() - liveness.lastInboundAt) / 1000) + 's' : 'never') +
            ' ackAgo=' + (lastPairAckAt ?
                Math.round((Date.now() - lastPairAckAt) / 1000) + 's' : 'never') +
            ' vis=' + (typeof document !== 'undefined' ? String(document.visibilityState) : '?') +
            ' fg=' + appForeground;
    }

    function closeRelaySocketForNudge(reason) {
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            return false;
        }
        try {
            socket.close(1000, reason);
            return true;
        } catch (e) {
            diag('warn', '恢复推动：close 抛错 ' + e);
            return false;
        }
    }

    /**
     * 推动页面自行恢复。返回推动前的现场字符串（原生日志与它对齐看时序）。
     */
    G.__zcodeShellNudgeRecover = function (mode) {
        try {
            var m = mode || 'auto';
            var before = describeRelay();
            var socket0 = activeSocket;
            var acts = [];
            if (m === 'event' || m === 'auto' || m === 'online' || m === 'pageshow') {
                var evName = (m === 'pageshow') ? 'pageshow' : 'online';
                try {
                    G.dispatchEvent(new G.Event(evName));
                    acts.push('dispatch:' + evName);
                } catch (e) {
                    acts.push('dispatch-failed:' + evName + '(' + e + ')');
                }
            }
            if (m === 'close') {
                acts.push('close=' + closeRelaySocketForNudge('shell-nudge'));
            }
            diag('warn', '恢复推动 ' + m + '：' + acts.join(' + ') + ' · 前 ' + before);
            if (m === 'auto' && typeof G.setTimeout === 'function') {
                G.setTimeout(function () {
                    try {
                        if (activeSocket === socket0 && socket0 && socket0.readyState === 1) {
                            diag('warn', '恢复推动：合成事件未生效（socket 未变），改走 close · ' +
                                describeRelay());
                            closeRelaySocketForNudge('shell-nudge-fallback');
                        } else {
                            diag('info', '恢复推动：socket 已变化（页面自己在重连）· ' +
                                describeRelay());
                        }
                    } catch (e) {
                        diag('warn', '恢复推动 auto 收尾失败: ' + e);
                    }
                }, 2500);
            }
            return before;
        } catch (e) {
            diag('warn', '恢复推动失败: ' + e);
            return '';
        }
    };

    /**
     * 网络自检：用一条**全新连接**打 HTTP（默认页面自己 origin）。
     *
     * 判据：墙来的时候，若这一条也失败/挂住 ⇒ App 的网络在后台被平台限制了
     * （那么原生承载同样救不了）；若它成功而 relay 那条是死的 ⇒ 只是那条连接
     * 坏了，重拨即可（那么"推动页面重拨"就是完整解）。
     */
    G.__zcodeShellNetProbe = function (url, tag) {
        try {
            var started = Date.now();
            var target = url || (String(G.location.origin) + '/remote/v4');
            target = target + (target.indexOf('?') >= 0 ? '&' : '?') + '__zcprobe=' + started;
            var host = String(target).replace(/^([a-zA-Z]+:\/\/[^/]+).*$/, '$1');
            diag('warn', '网络自检[' + (tag || '') + '] 发起 ' + host +
                ' online=' + (typeof navigator !== 'undefined' ? String(navigator.onLine) : '?') +
                ' vis=' + (typeof document !== 'undefined' ? String(document.visibilityState) : '?'));
            G.fetch(target, {cache: 'no-store', credentials: 'omit'}).then(function (r) {
                diag('warn', '网络自检[' + (tag || '') + ']：HTTP ' + r.status + ' 用时 ' +
                    (Date.now() - started) + 'ms');
            }).catch(function (e) {
                diag('warn', '网络自检[' + (tag || '') + ']：失败 用时 ' +
                    (Date.now() - started) + 'ms · ' + e);
            });
            return true;
        } catch (e) {
            diag('warn', '网络自检发起失败: ' + e);
            return false;
        }
    };

    /**
     * 原生承载交还后的兜底（回前台时由原生调用一次）。
     *
     * 正常路径：可见性恢复 → 页面自己的 `T('visible')` → `recoverConnection()` →
     * `reconnectNow()`，页面重拨回来，**不重载**。
     *
     * 但页面若已经掉进**失败态**（`i4t.dispose` 之后 `intentionallyClosed=true`、
     * socket 关掉、`paired=false`），它自己回不来——真机 00:12:57 交还后 37 秒
     * 仍是 `socket=-1 paired=false`，只有手动重载才回来（同 docs 里 KICKED 终态的
     * 性质）。所以这里给一个**极窄**的兜底：8s 后若既没有一条 OPEN 的 socket、
     * 这段窗口里又零入站帧，才重载一次；沿用死链兜底那条 5 分钟限流。
     */
    G.__zcodeShellAfterCarrierReturn = function (graceMs) {
        try {
            if (appForeground !== true) {
                diag('info', '交还兜底：此刻不在前台，跳过');
                return false;
            }
            var framesAtStart = liveness.inboundFrames;
            var waitMs = graceMs && graceMs > 0 ? graceMs : 8000;
            G.setTimeout(function () {
                try {
                    if (appForeground !== true) return;
                    var open = false;
                    for (var i = 0; i < sockets.length; i++) {
                        if (sockets[i].readyState === 1) {
                            open = true;
                        }
                    }
                    var gotFrames = liveness.inboundFrames !== framesAtStart;
                    if (open || gotFrames) {
                        diag('info', '交还后页面已自行恢复（open=' + open + ' 新入站帧=' + gotFrames +
                            '）· ' + describeRelay());
                        return;
                    }
                    if (lastResumeHealAt && Date.now() - lastResumeHealAt <= RESUME_HEAL_MIN_GAP_MS) {
                        diag('warn', '交还兜底：页面仍未开线，但距上次兜底重载不足 ' +
                            Math.round(RESUME_HEAL_MIN_GAP_MS / 60000) + ' 分钟，限流跳过');
                        return;
                    }
                    lastResumeHealAt = Date.now();
                    diag('warn', '交还兜底：' + Math.round(waitMs / 1000) +
                        's 后页面既没有 OPEN 的 socket 也没有新入站帧（失败态自己回不来）——重载一次 · ' +
                        describeRelay());
                    G.location.reload();
                } catch (e) {
                    diag('warn', '交还兜底收尾失败: ' + e);
                }
            }, waitMs);
            return true;
        } catch (e) {
            diag('warn', '交还兜底发起失败: ' + e);
            return false;
        }
    };

    // -----------------------------------------------------------------------
    // 诊断：往会话输入框里发一条消息（**只为造流做端到端验收**，不参与生产逻辑）
    //
    // 为什么需要：验收"后台承载能把正文推到流体云"必须先有一条持续输出的会话，
    // 而 adb 灌不进 WebView 的输入框（`input text` 实测无效、uiautomator 也读不到
    // WebView 内部节点）。注入层就在页面里，可以走 React 认得的写法：
    // 原生 value setter + `input` 事件，然后派发完整指针序列点发送（或回车）。
    //
    // 先 `probe_composer` 看清 DOM，再 `compose|<文本>` 发送；两条都只由 adb 显式触发。
    // -----------------------------------------------------------------------

    /** 候选输入框：textarea 优先，其次 contenteditable。 */
    function findComposer() {
        var cands = [];
        try {
            cands = document.querySelectorAll('textarea, [contenteditable="true"], [role="textbox"]');
        } catch (e) {
            return null;
        }
        var best = null;
        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            var r = null;
            try {
                r = el.getBoundingClientRect();
            } catch (e2) {
                r = null;
            }
            if (!r || r.width < 40 || r.height < 16) {
                continue;
            }
            if (!best || r.top > best.rect.top) {
                best = {el: el, rect: r};
            }
        }
        return best;
    }

    function describeElement(el) {
        if (!el) {
            return 'null';
        }
        var r = null;
        try {
            r = el.getBoundingClientRect();
        } catch (e) {
            r = null;
        }
        return '<' + String(el.tagName).toLowerCase() + '>' +
            ' type=' + (el.getAttribute && el.getAttribute('type')) +
            ' aria=' + (el.getAttribute && el.getAttribute('aria-label')) +
            ' ph=' + (el.getAttribute && el.getAttribute('placeholder')) +
            ' disabled=' + (el.disabled === true) +
            ' box=' + (r ? Math.round(r.left) + ',' + Math.round(r.top) + ' ' +
                Math.round(r.width) + 'x' + Math.round(r.height) : '?');
    }

    /** 把页面底部所有可点的东西列出来，好认出发送键。 */
    G.__zcodeShellProbeComposer = function () {
        try {
            var found = findComposer();
            diag('info', '输入框探测：' + (found ? describeElement(found.el) : '没找到 textarea/contenteditable'));
            var btns = [];
            try {
                btns = document.querySelectorAll('button, [role="button"], [type="submit"]');
            } catch (e) {
                btns = [];
            }
            var lines = [];
            for (var i = 0; i < btns.length && lines.length < 12; i++) {
                var el = btns[i];
                var r = null;
                try {
                    r = el.getBoundingClientRect();
                } catch (e3) {
                    r = null;
                }
                if (!r || r.width < 8 || r.height < 8) {
                    continue;
                }
                if (r.top < (G.innerHeight || 800) * 0.55) {
                    continue;   // 只看下半屏（输入区）
                }
                lines.push(describeElement(el) +
                    ' text=' + String(el.textContent || '').trim().substring(0, 12));
            }
            diag('info', '发送键候选（下半屏 ' + lines.length + ' 个）：' + lines.join(' | '));
            return true;
        } catch (e) {
            diag('warn', '输入框探测失败: ' + e);
            return false;
        }
    };

    /** 找发送键：贴着输入框右侧那一颗（排除列表/工具条上的按钮）。 */
    function findSendButton(composerRect) {
        var cands = [];
        try {
            cands = document.querySelectorAll('button, [role="button"], [type="submit"]');
        } catch (e) {
            return null;
        }
        var fallback = null;
        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            var r = null;
            try {
                r = el.getBoundingClientRect();
            } catch (e2) {
                r = null;
            }
            if (!r || r.width < 8 || r.height < 8 || el.disabled === true) {
                continue;
            }
            var label = String((el.getAttribute && (el.getAttribute('aria-label') ||
                el.getAttribute('title'))) || '') + String(el.textContent || '');
            if (label.indexOf('发送') >= 0 || label.indexOf('发送消息') >= 0 ||
                label.indexOf('Send') >= 0 || label.indexOf('提交') >= 0) {
                return el;
            }
            // 贴着输入框（同一行、在它右边）的那一颗才算候选；列表/工具条上的按钮
            // 上下都可能撞进来，所以只在"垂直重叠输入框"的窄带里找。
            if (!composerRect) {
                continue;
            }
            var vOverlap = r.bottom > composerRect.top + 2 && r.top < composerRect.bottom - 2;
            if (!vOverlap) {
                continue;
            }
            if (r.left < composerRect.right - 24) {
                continue;
            }
            if (!fallback || r.left > fallback.rect.left) {
                fallback = {el: el, rect: r};
            }
        }
        return fallback ? fallback.el : null;
    }

    /**
     * 往输入框里塞字。
     *
     * 页面用的是 **React 受控的 contenteditable div**（`vitals` 探测到
     * `<div> box=29,651 306x40`），直接赋 `textContent` 不生效——React 的受控值会
     * 在下一个 render 里被清掉（真机 00:36:58 现场：`填入=""`）。所以先走
     * `execCommand('insertText')`（Chromium 里等价于真实输入，React 认），
     * 失败再退到"赋 textContent + 派发 beforeinput/input（inputType=insertText）"。
     *
     * @return 塞完之后输入框里的实际内容（截断）。
     */
    function fillComposer(el, text) {
        try {
            el.focus();
        } catch (e) {
            // focus 失败不致命
        }
        var ok = false;
        try {
            ok = document.execCommand && document.execCommand('insertText', false, text);
        } catch (e2) {
            ok = false;
        }
        if (!ok) {
            try {
                el.textContent = text;
                var init = {bubbles: true, cancelable: true, inputType: 'insertText', data: text};
                var ev;
                try {
                    ev = new G.InputEvent('beforeinput', init);
                } catch (e3) {
                    ev = new G.Event('beforeinput', {bubbles: true, cancelable: true});
                }
                el.dispatchEvent(ev);
                try {
                    ev = new G.InputEvent('input', init);
                } catch (e4) {
                    ev = new G.Event('input', {bubbles: true});
                }
                el.dispatchEvent(ev);
            } catch (e5) {
                diag('warn', '填入退路失败: ' + e5);
            }
        }
        try {
            return String(el.textContent === undefined ? el.value : el.textContent).substring(0, 40);
        } catch (e6) {
            return '?';
        }
    }

    /**
     * 填入文本并发送。`compose|<文本>`；只由 adb 显式触发。
     * 返回一行摘要字符串（原生日志与它对齐看时序）。
     */
    G.__zcodeShellComposeSend = function (text) {
        try {
            if (!text) {
                return '空文本，未发送';
            }
            var found = findComposer();
            if (!found) {
                diag('warn', '发送失败：页面里找不到输入框');
                return 'no-composer';
            }
            var el = found.el;
            var filled = fillComposer(el, text);
            if (!filled) {
                diag('warn', '发送失败：填进去又被清空（受控输入没认这次输入）· ' + describeElement(el));
                return 'fill-rejected';
            }
            var btn = findSendButton(found.rect);
            var how = 'none';
            if (btn) {
                clickElement(btn);
                how = 'click:' + describeElement(btn);
            } else {
                // 退路：回车（多数聊天 UI 用 Enter 发送）
                try {
                    el.focus();
                    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                        el.dispatchEvent(new G.KeyboardEvent(type, {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                            bubbles: true, cancelable: true
                        }));
                    });
                    how = 'enter';
                } catch (e) {
                    how = 'enter-failed:' + e;
                }
            }
            diag('warn', '已发送输入：填入="' + filled + '" · 方式=' + how + ' · ' +
                describeElement(el));
            return 'sent=' + how;
        } catch (e) {
            diag('warn', '发送失败: ' + e);
            return 'error:' + e;
        }
    };

    G.__zcodeShellDiag = function (cmd) {
        try {
            if (cmd === 'probe_composer') {
                return G.__zcodeShellProbeComposer();
            }
            if (cmd.indexOf('compose|') === 0) {
                G.__zcodeShellComposeSend(cmd.substring(8));
                return true;
            }
            if (cmd === 'bg_redial') {
                G.__zcodeShellNudgeRecover('event');
                return true;
            }
            if (cmd.indexOf('bg_redial:') === 0) {
                G.__zcodeShellNudgeRecover(cmd.substring(10));
                return true;
            }
            if (cmd === 'bg_http') {
                G.__zcodeShellNetProbe(null, '');
                return true;
            }
            if (cmd.indexOf('bg_http|') === 0) {
                G.__zcodeShellNetProbe(cmd.substring(8), 'custom');
                return true;
            }
            if (cmd === 'bg_state') {
                diag('info', '链路现场: ' + describeRelay());
                return true;
            }
            if (cmd === 'vitals') {
                diag('info', '页面体征: ' + JSON.stringify(readVitals()));
                return true;
            }
            if (cmd === 'l1_test') {
                diag('warn', '诊断指令：手动触发卡死轻推');
                return nudgeReconnect('l1_test 手动触发');
            }
            if (cmd === 'kick_test') {
                return relayKickTest();
            }
            if (cmd === 'degrade_test') {
                return relayDegradeTest();
            }
            if (cmd === 'deadlink_test') {
                // 回前台死链兜底的真机测试点：真机没法为了测它去后台待 7 分钟，
                // 所以把"链路观测静默"这件事直接伪造出来（把 lastInboundAt 拨旧
                // 120s），再走一次回前台判定。判据：健康链路上应当**不重载**
                // （观察窗内会有帧进来），死链+对话 0 行时应当重载一次。
                var before = liveness.lastInboundAt;
                liveness.lastInboundAt = Date.now() - 120000;
                diag('warn', '诊断指令：伪造链路静默 120s（原 lastInboundAt=' + before +
                    '），走一次回前台判定');
                G.__zcodeShellSetAppForeground(true);
                return true;
            }
            diag('warn', '未知诊断指令: ' + cmd);
            return false;
        } catch (e) {
            diag('warn', '诊断指令执行失败: ' + e);
            return false;
        }
    };


/** Called by the native side when the app's foreground state changes. */
    G.__zcodeShellSetAppForeground = function (foreground) {
        diag('info', 'app foreground = ' + (foreground ? 'true' : 'false'));
        appForeground = foreground;
        if (!foreground) {
            // A fresh window: the survival verdict is about ticks from here on.
            // The COUNT deliberately keeps its value — the native side records a
            // base per window and subtracts it. Zeroing it here looked harmless
            // and was not: waking a screen-off phone produces a foreground →
            // background → foreground flap within a few ms (`am start` does it
            // too), and that zeroed the counter 2ms before the verdict was read,
            // so two field rounds reported "心跳未执行" while the pump was
            // demonstrably ticking every 15s.
            backgroundStartedWallMs = Date.now();
            liveness.backgroundFirstTickDelayMs = -1;
            return;
        }
        // 被 relay 顶掉的终态页面不会自愈（只有手动"重新连接"才回得来），
        // 所以回前台第一件事就是把它重载回来。放在所有链路判断之前——被顶掉时
        // relayPaired 已经是 false，走不到下面的分支。
        healKickedOnForeground();
        if (!relayPaired || !deviceSid) {
            diag('info', '回前台时链路未就绪，交由页面自行重连');
            reportPageState();
            return;
        }
        // An unbounded background stint is the one thing this layer cannot see
        // from the inside (its own timers stop), so the native side tells us we
        // are back — and the verdict is made on the frames that were still
        // arriving, not on the ack clock. A live link is left alone; a silent
        // one is rebuilt now rather than waiting for the stale branch.
        var silence = liveness.lastInboundAt ? Date.now() - liveness.lastInboundAt : -1;
        if (silence < 0 || silence > RESUME_DEAD_LINK_MS) {
            if (SHELL_READ_ONLY) {
                // 这里曾经是"回前台先把静默的 socket 拆了重建"（20:53:04 的现场）。
                // 代价是每一次回前台都逼页面重连一次；而页面自己有陈旧看门狗
                // （i4t.reconnectAfterStaleWaiting + 退避梯子），判得比我们准。
                diag('warn', '回前台：链路观测静默' +
                    (silence < 0 ? '（本轮从未收到帧）' : ' ' + Math.round(silence / 1000) + 's') +
                    '，只读壳不拆线；交给 healDeadLinkOnResume 判"页面是否真的没恢复"');
                healDeadLinkOnResume(silence);
                reportPageState();
                return;
            }
            forceReconnect(silence < 0 ?
                '回前台且从未收到帧' :
                '回前台时已静默 ' + Math.round(silence / 1000) + 's');
            reportPageState();
            return;
        }
        // 只读壳：不写、**也不假装收到过 ack**。原先这里把 lastPairAckAt 拨到现在，
        // 等于用一个没发生的 ack 去掩盖真实的陈旧——那是自欺，观测层最不该做的事。
        // 链路活不活，接下来 10s 内页面自己的心跳会给答案。
        if (SHELL_READ_ONLY) {
            staleTicks = 0;
            diag('info', '回前台：链路观测正常（静默 ' + Math.round(silence / 1000) +
                's），只读壳不介入');
            reportPageState();
            return;
        }
        lastPairAckAt = Date.now();
        staleTicks = 0;
        // 同上：控制帧必须顶层 type，不能包成 data 载荷（否则永远拿不到 ack）。
        sendControlFrame({
            type: 'pair_status_query',
            device_sid: deviceSid,
            client_ts: Date.now()
        });
        reportPageState();    };

    /**
     * Re-reports the page's visual state. Called on every return to the
     * foreground: the native strip behind the status bar must agree with the page
     * that is under it, and both the theme and the page's own breakpoint can have
     * changed while the app was away (a system theme switch does not mutate the
     * DOM, so the observer alone would miss it).
     */
    function reportPageState() {
        if (typeof G.__zcodeShellReportPageState === 'function') {
            G.__zcodeShellReportPageState();
        }
    }

    /**
     * Reports the CSS viewport metrics.
     *
     * This exists to make layout problems measurable instead of a screenshot
     * argument: if innerWidth * devicePixelRatio does not match the WebView's
     * own width, the page is being laid out at a different scale than it is
     * displayed at, which shows up as content sitting off-centre relative to
     * the scrollbar.
     */
    /**
     * 滚动条到底占了多少宽——"14px 有没有真的还回来"的**唯一硬判据**。
     *
     * 根文档量不出来：2026-09-15 真机基线是 `innerWidth == clientWidth == 363`
     * （dpr 3.5 下的 1272），根滚动条并不占位。占位的是页面自己那个内部滚动容器
     * （`[data-v4-timeline-scroll]`，class 里还带着 Tailwind 的
     * `[scrollbar-gutter:stable]`）——README 里"内容盒 1222 / 屏幕 1272"讲的就是它。
     *
     * 所以量它的 `offsetWidth - clientWidth`：归零前应为 **14**（正是网页自己的
     * `::-webkit-scrollbar{width:14px}`），归零后应为 **0**，同时它的宽度应从
     * 349 回到 363。这三个数字一出来，滚动条这件事就不必再靠截图争论。
     */
    function scrollbarReport() {
        try {
            var nodes = document.querySelectorAll('[data-v4-timeline-scroll]');
            if (!nodes || nodes.length === 0) {
                return ' 时间线=未找到';
            }
            var out = '';
            for (var i = 0; i < nodes.length && i < 2; i += 1) {
                var el = nodes[i];
                out += ' 时间线' + i + '=offsetW' + el.offsetWidth +
                    '/clientW' + el.clientWidth +
                    '/(滚动条占宽' + (el.offsetWidth - el.clientWidth) + ')';
            }
            return out;
        } catch (e) {
            return ' 时间线=测量失败';
        }
    }

    function reportViewport() {
        // Wrapped because it runs from a timer: by the time it fires the
        // document may be going away, and an exception here would escape into
        // whatever is tearing the page down.
        try {
            var doc = document.documentElement || {};
            var vv = window.visualViewport;
            post('diag', {
                level: 'info',
                message: '视口 innerWidth=' + window.innerWidth +
                    ' innerHeight=' + window.innerHeight +
                    ' clientWidth=' + (doc.clientWidth || 0) +
                    ' scrollWidth=' + (doc.scrollWidth || 0) +
                    ' dpr=' + (window.devicePixelRatio || 0) +
                    ' scale=' + (vv ? Math.round(vv.scale * 100) / 100 : 'n/a') +
                    scrollbarReport()
            });
        } catch (e) {
            // page torn down; nothing to report
        }
    }

    /**
     * Asks the injected layer to report its liveness counters immediately.
     * Used by the native side when the app returns to the foreground, so the
     * background-survival verdict has a number to compare against.
     */
    G.__zcodeShellReportLiveness = function () {
        reportLiveness();
        return true;
    };

    // -----------------------------------------------------------------------
    // 6. page visual state -> native status bar surface
    //
    // The app bar is gone; the strip behind the status bar is painted by the
    // native side with one of the remote page's own fixed surface colours, and
    // it must not read colours at runtime. So this layer reports NAMES: which
    // visual state the DOM is in, and which theme the page resolved for itself.
    // The native side looks both up in a table (core/PageBarColor.kt).
    //
    // The state is derived the way the page derives its own layout, not from the
    // device's screen size:
    //   * `.zcode-boot-loading`   the pre-rendered boot shell (removed once the
    //                             app mounts) and every status page (KICKED,
    //                             takeover) — all of them put the page background
    //                             at the top, so they all map to 'boot';
    //   * `.bg-background-win-alt` the control view's root;
    //   * `(max-width: 767px)`    the page's own breakpoint for the phone shell.
    //                             Narrow means the page's own title bar sits
    //                             directly under the status bar (bg-header), wide
    //                             means the shell area does (win-alt).
    //
    // Theme: the page stamps `data-zcode-browser-theme-surface` on <html> when it
    // resolves its theme (inline bootstrap in index.html, `syncBrowserThemeSurface`),
    // which is authoritative — the page's theme can differ from the system's. The
    // prefers-color-scheme query is only the fallback, and the only signal that
    // exists while the boot shell is up.
    // -----------------------------------------------------------------------
    var NARROW_QUERY = '(max-width: 767px)';
    var DARK_QUERY = '(prefers-color-scheme: dark)';

    function mediaMatches(query) {
        try {
            return !!(G.matchMedia && G.matchMedia(query).matches);
        } catch (e) {
            return false;
        }
    }

    function pageStateName() {
        try {
            if (!document.documentElement) {
                return '';
            }
            if (document.getElementsByClassName('zcode-boot-loading').length > 0) {
                return 'boot';
            }
            if (document.getElementsByClassName('bg-background-win-alt').length === 0) {
                return 'boot';
            }
            return mediaMatches(NARROW_QUERY) ? 'main-header' : 'main-surface';
        } catch (e) {
            return '';
        }
    }

    function pageThemeName() {
        try {
            var root = document.documentElement;
            var stamped = root && root.getAttribute
                ? root.getAttribute('data-zcode-browser-theme-surface')
                : null;
            if (stamped === 'dark' || stamped === 'light') {
                return stamped;
            }
            // The same bootstrap also writes `style.colorScheme`, so a page that
            // is mid-boot still answers before the attribute lands.
            var inline = root && root.style ? String(root.style.colorScheme || '') : '';
            if (inline.indexOf('dark') >= 0) {
                return 'dark';
            }
            if (inline.indexOf('light') >= 0) {
                return 'light';
            }
        } catch (e) {
            // fall through to the media query below
        }
        return mediaMatches(DARK_QUERY) ? 'dark' : 'light';
    }

    var lastPageState = '';
    var pageStateScheduled = false;

    function pushPageState() {
        pageStateScheduled = false;
        var state = pageStateName();
        if (!state) {
            return;
        }
        var theme = pageThemeName();
        var token = state + '|' + theme;
        if (token === lastPageState) {
            return;
        }
        lastPageState = token;
        post('pagestate', {state: state, theme: theme});
    }

    /**
     * React renders in bursts, and the boot shell is removed in the same frame the
     * control view mounts — so coalesce to one report per settled tick instead of
     * one per mutation.
     */
    function schedulePageState() {
        if (pageStateScheduled) {
            return;
        }
        pageStateScheduled = true;
        setTimeout(pushPageState, 0);
        noteChatViewEntered();
    }

    /**
     * 进对话的**第二个**信标：DOM 视图。
     *
     * 页面进入对话视图时会挂上 `[data-mobile-page="chat"]`（`readVitals` 一直用
     * 它判"当前在不在对话里"）。为什么铁判准也要认它：第一个信标是页面自己发的
     * `subscribeConversationV4`/`conversationRowsRangeV4`，可当页面传输层已经坏掉
     * 时，用户点进任务**连这个请求都不会发出去**——只认 RPC 信标就会整窗漏掉，
     * 那正是"还是做不到"的形态。视图一旦出现就武装 5 s，与 RPC 信标等价。
     */
    function noteChatViewEntered() {
        var has = false;
        try {
            has = !!document.querySelector('[data-mobile-page="chat"]');
        } catch (e) {
            return;
        }
        if (has === fallbackState.chatView) {
            return;
        }
        fallbackState.chatView = has;
        if (!has) {
            return;
        }
        var age = fallbackState.beaconAt ? Date.now() - fallbackState.beaconAt : -1;
        if (age >= 0 && age < FALLBACK_CHECK_MS) {
            // 已经有一个在跑的窗口（页面自己发的 RPC 信标）→ 不重置：重置会把
            // 它刚记下的就绪证据抹掉，空会话（没有行、也没有新入站帧）就会被
            // 误判成"没内容"而白刷一次。
            return;
        }
        diag('debug', '进入对话视图（DOM 信标）→ 武装 5s 铁判准');
        scheduleFallbackCheck();
    }

    function watchMedia(query) {
        try {
            var mq = G.matchMedia && G.matchMedia(query);
            if (!mq) {
                return;
            }
            var handler = function () {
                schedulePageState();
            };
            if (mq.addEventListener) {
                mq.addEventListener('change', handler);
            } else if (mq.addListener) {
                mq.addListener(handler);
            }
        } catch (e) {
            // An old engine without matchMedia simply keeps the last state.
        }
    }

    /**
     * Rotation, split screen and foldables cross the page's breakpoint without
     * any DOM mutation, so the query itself is watched as well; a theme switch
     * does mutate the attribute and is caught by the observer.
     */
    function installPageStateReporter() {
        if (G.__zcodeShellPageStateHooked) {
            return;
        }
        if (!document.documentElement) {
            // document-start can land before <html> exists — the same race
            // installScrollbarWidth guards against. Returning here without a
            // retry would be silent and total: the status bar would never follow
            // the page for the whole life of the document. (Observed working on
            // WebView 154, where the element is already there, which is exactly
            // why this must not depend on that.)
            document.addEventListener('DOMContentLoaded', installPageStateReporter);
            return;
        }
        G.__zcodeShellPageStateHooked = true;
        if (G.MutationObserver) {
            try {
                new G.MutationObserver(schedulePageState).observe(document.documentElement, {
                    subtree: true,
                    childList: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'data-zcode-browser-theme-surface']
                });
            } catch (e) {
                diag('warn', '页面状态观察器安装失败: ' + e);
            }
        } else {
            // Degraded, not broken: the breakpoint and the system theme are still
            // watched, so rotation and a theme switch keep working; only a
            // state change that mutates the DOM without crossing either query
            // would be missed.
            diag('debug', '页面状态观察器缺少 MutationObserver：只跟踪断点与主题，不再回头验 DOM');
        }
        watchMedia(NARROW_QUERY);
        watchMedia(DARK_QUERY);
        schedulePageState();
    }

    /** Native asks for a fresh report, e.g. when the app returns to the foreground. */
    G.__zcodeShellReportPageState = function () {
        lastPageState = '';
        schedulePageState();
        return true;
    };

    // -----------------------------------------------------------------------
    // 7. overlay scrollbar
    //
    // The page ships a classic, space-taking scrollbar in its own stylesheet
    // (`::-webkit-scrollbar{width:14px;height:14px}` with a 3px-inset pill
    // thumb), and Chrome turns any ::-webkit-scrollbar width into a classic
    // bar: "when you set the width or height of ::-webkit-scrollbar, an overlay
    // scrollbar is always displayed, effectively turning it into a classic
    // scrollbar" (developer.chrome.com/docs/css-ui/scrollbar-styling). Measured
    // on the device that costs the content box 14.3 CSS px — 1222px of a 1272px
    // screen at dpr 3.5 — so the conversation, composer included, sat 7 CSS px
    // left of centre behind an empty strip.
    //
    // Android WebView cannot hand that width back on its own: it switches
    // overlay scrollbar rendering off entirely, because the root scrollbar is
    // expected to be drawn by the Android view (chromium issue 40226034, from
    // the WebView owners). That is also why the standard `scrollbar-width`
    // property has no effect there, verified on the device. ArkWeb does not do
    // that, which is why the same page is centred in the HarmonyOS shell with a
    // bar that appears while scrolling and fades.
    //
    // So: zero the page's rail (the one page-side rule, and what gives the
    // content its width back) and draw the indicator ourselves, in the page's
    // own geometry and its own colour token, appearing on scroll and fading out
    // after it — an overlay, exactly the thing being stood in for. Idle cost is
    // zero: nothing is created, measured or timed until something in the page
    // actually scrolls.
    // -----------------------------------------------------------------------
    // gutter 保险：页面把 [scrollbar-gutter:stable] 挂在主聊天滚动容器上（快照
    // docs/05 @2259500）。当前 WebView 在条宽归零后会把预留槽一起收掉（2026-09-12
    // 真机确认输入框已回正），这条规则是防页面改版/引擎升级把预留带回来的保险，
    // 今天是 no-op。
    // 这一条与网页作者自己的内嵌方案是同一套写法：他们的 bundle 里有个函数
    // （快照 @1422852，`zcode-coding-plan-hide-scrollbar`）在把页面塞进 WebView
    // 时注入的正是 `html, body, * { scrollbar-width: none !important; }` +
    // `html::-webkit-scrollbar, body::-webkit-scrollbar, *::-webkit-scrollbar
    //  { display: none !important; width: 0 !important; height: 0 !important; }`。
    // 归零 + gutter 保险，一次写在同一条规则里。
    //
    // gutter 那条原来只针对 `[data-v4-timeline-scroll]`（页面把 Tailwind 的
    // `[scrollbar-gutter:stable]` 挂在主聊天容器上）。现在改成全局：我们既然把
    // **所有**滚动条都归零了，那么**任何**地方再预留槽位都是错的（设置页的几个
    // 面板也挂了同一个工具类）。2026-09-15 真机基线：根文档并不占位
    // （innerWidth == clientWidth == 363 CSS px），占位的是这些内部容器。
    var SCROLLBAR_CSS =
        'html,body,*{scrollbar-width:none!important;scrollbar-gutter:auto!important}' +
        'html::-webkit-scrollbar,body::-webkit-scrollbar,*::-webkit-scrollbar' +
        '{display:none!important;width:0!important;height:0!important}';

    function installScrollbarWidth() {
        try {
            var parent = document.head || document.documentElement;
            if (!parent) {
                // document-start can land before <html> exists.
                document.addEventListener('DOMContentLoaded', installScrollbarWidth);
                return;
            }
            var style = document.createElement('style');
            style.setAttribute('data-zcode-shell', 'scrollbar-width');
            style.textContent = SCROLLBAR_CSS;
            parent.appendChild(style);
        } catch (e) {
            diag('warn', '滚动条宽度置零失败: ' + e);
        }
    }

    // 网页自己那条滚动条，按它自己的宣言逐字复刻成悬浮版。下面每个数字、每条
    // 声明都来自页面的样式表（快照 index-BMndL2ru.css @368578）：
    //
    //   ::-webkit-scrollbar{width:14px;height:14px}              <- 轨道（rail）
    //   ::-webkit-scrollbar-track{background:0 0}                <- 轨道透明
    //   ::-webkit-scrollbar-thumb{background:var(--color-border);
    //     background-clip:padding-box;border:3px solid #0000;
    //     border-radius:9999px;min-width:32px;min-height:32px}   <- 滑块
    //
    // 并且这几个值在运行时**从页面自己的样式表里读回来**（[readPageBarSpec]），
    // 所以这个悬浮条不可能与它顶替的那条走样：页面改了滚动条样式，这里自动跟。
    // 盒模型也是逐字复刻的——轨道里放一个 border-box 的滑块，用页面自己那条
    //透明边框内缩——因此滑块出现的位置与页面自己那条完全一致，两端各内缩 3px。
    var BAR_FALLBACK = {rail: 14, inset: 3, radius: '9999px', min: 32};
    var BAR_FADE_MS = 700;
    var BAR_COLOR_FALLBACK = 'rgba(128,128,128,0.5)';
    var BAR_Z = 2147483647;

    var barSpec = null;
    var barEl = null;
    var barThumb = null;
    var barTarget = null;
    var barRect = null;
    var barFrame = 0;
    var barHideTimer = 0;
    var barLive = false;
    // 滚动条几何诊断的节流时间戳（滚动期间最多 2s 一条日志）。
    var barDiagAt = 0;
    // Scrollers the page deliberately keeps bar-less; verdicts are cached per
    // element so the check runs at most once per scroller.
    var barSkipped = typeof WeakSet === 'function' ? new WeakSet() : null;

    function scheduleFrame(fn) {
        if (typeof G.requestAnimationFrame === 'function') {
            return G.requestAnimationFrame(fn);
        }
        return setTimeout(fn, 16);
    }

    /** px 数值；非像素长度（空串、auto、变量）一律当 0，即"这条声明不算数"。 */
    function pxOf(value) {
        var n = parseFloat(value);
        return isFinite(n) && n > 0 ? n : 0;
    }

    /**
     * 把页面自己的 ::-webkit-scrollbar / -thumb 规则读一遍（只读一次）。
     * 快照是 /remote/v4 下的同源样式表，cssRules 可读；读不到的（跨源表）保留
     * 兜底值。0 宽/0 高的规则（页面自己的 scrollbar-hide、xterm 视口，以及**我们
     * 自己注入的归零规则**）都因为 pxOf 返回 0 而被跳过，不会污染这份规格。
     */
    function readPageBarSpec() {
        if (barSpec) {
            return barSpec;
        }
        var spec = {
            rail: BAR_FALLBACK.rail,
            inset: BAR_FALLBACK.inset,
            radius: BAR_FALLBACK.radius,
            min: BAR_FALLBACK.min,
        };
        barSpec = spec;
        try {
            var sheets = document.styleSheets || [];
            for (var i = 0; i < sheets.length; i++) {
                var rules = null;
                try {
                    rules = sheets[i].cssRules;
                } catch (e) {
                    continue; // 读不了的样式表；用兜底值
                }
                if (!rules) {
                    continue;
                }
                for (var j = 0; j < rules.length; j++) {
                    var rule = rules[j];
                    var sel = rule.selectorText;
                    if (!sel || sel.indexOf('::-webkit-scrollbar') < 0) {
                        continue;
                    }
                    var css = rule.style;
                    if (!css) {
                        continue;
                    }
                    if (sel.indexOf('-thumb') >= 0) {
                        var bw = pxOf(css.borderTopWidth) || pxOf(css.borderWidth);
                        if (bw) {
                            spec.inset = bw;
                        }
                        if (css.borderRadius) {
                            spec.radius = css.borderRadius;
                        }
                        var mh = pxOf(css.minHeight);
                        if (mh) {
                            spec.min = mh;
                        }
                    } else if (sel.indexOf('-track') < 0 && sel.indexOf('-corner') < 0) {
                        var w = pxOf(css.width);
                        if (w) {
                            spec.rail = w;
                        }
                    }
                }
            }
        } catch (e) {
            // 用兜底值；形状仍是页面那条的形状
        }
        return spec;
    }

    function barElement() {
        if (barEl) {
            return barEl;
        }
        var spec = readPageBarSpec();
        var el = document.createElement('div');
        el.setAttribute('data-zcode-shell', 'scrollbar');
        var s = el.style;
        // Out of flow and never a hit target: the page's own interaction and
        // layout must not be able to tell it is there.
        s.position = 'fixed';
        s.left = '0px';
        s.top = '0px';
        s.width = spec.rail + 'px';
        // -track{background:0 0}：页面的轨道是透明的，所以轨道什么都不画，
        // 屏幕上只有滑块本体。
        s.background = 'transparent';
        s.opacity = '0';
        s.pointerEvents = 'none';
        s.zIndex = String(BAR_Z);
        s.transition = 'opacity 160ms linear';
        s.willChange = 'transform, opacity';

        // 滑块**宽度直接用 inset 算出来**（rail - 2*inset，即页面那条 14px 轨道里
        // 可见的 8px 药丸），不走 `border:3px solid transparent` +
        // `background-clip:padding-box` 那套。
        //
        // 为什么弃用那套（虽然它与页面的写法逐字一致）：2026-09-15 真机上它没能生效，
        // 滑块撑满了整条 14px 轨道——视觉上"粗了一倍"，用户当场指出。14px × dpr 3.5
        // = 49 物理像素，那确实粗得离谱。几何用常量算出来就没有这个失败模式，而且
        // 渲染结果与页面那条**逐像素一致**：8 CSS px 可见宽，距右缘、上下各 3px。
        var thumb = document.createElement('div');
        var t = thumb.style;
        t.position = 'absolute';
        t.left = spec.inset + 'px';
        t.right = spec.inset + 'px';
        t.top = '0px';
        t.boxSizing = 'border-box';
        t.borderRadius = spec.radius;
        // The page's colour token, read from the scroller at paint time: it is
        // defined on the page's theme wrapper, so it follows light/dark for
        // free and nothing here has to know either value.
        t.background = BAR_COLOR_FALLBACK;
        el.appendChild(thumb);

        (document.body || document.documentElement).appendChild(el);
        barEl = el;
        barThumb = thumb;
        return el;
    }

    /**
     * 滚动条几何诊断：滚动期间最多 2s 一条，把"到底多宽"变成可读数字。
     *
     * 这是"滑块粗不粗"唯一不靠肉眼争论的判据：容器占宽应为 0、滑块可见宽应为
     * 8px（28 物理像素）。若哪天又变粗，这条日志会直接指出是哪一项不对。
     */
    function barReportGeometry(scroller, spec) {
        var now = Date.now();
        if (now - barDiagAt < 2000) {
            return;
        }
        barDiagAt = now;
        try {
            var dpr = window.devicePixelRatio || 1;
            var visible = Math.max(1, spec.rail - spec.inset * 2);
            var rect = barThumb.getBoundingClientRect();
            diag('info', '滚动条几何: 容器占宽=' + (scroller.offsetWidth - scroller.clientWidth) +
                ' 容器宽=' + scroller.clientWidth +
                ' 轨道=' + spec.rail + 'px 内缩=' + spec.inset + 'px' +
                ' 滑块可见宽=' + visible + 'px(' + Math.round(visible * dpr) + '物理)' +
                ' 实测滑块宽=' + (Math.round(rect.width * 10) / 10) + 'px' +
                ' 圆角=' + spec.radius + ' 最短=' + spec.min);
        } catch (e) {
            // 诊断本身失败不影响任何东西
        }
    }

    function barPaint(scroller) {
        var el = barElement();
        var spec = barSpec || readPageBarSpec();
        if (!barRect) {
            barRect = scroller.getBoundingClientRect();
            try {
                var token = G.getComputedStyle(scroller).getPropertyValue('--color-border');
                if (token) {
                    barThumb.style.background = token.trim();
                }
            } catch (e) {
                // keep the fallback; the bar is still visible and correct
            }
        }
        var overflow = scroller.scrollHeight - scroller.clientHeight;
        // 轨道就是滚动容器自己的高（经典滚动条不额外留白）。
        var rail = barRect.height;
        if (overflow <= 0 || rail <= spec.min) {
            el.style.opacity = '0';
            return;
        }
        var size = Math.max(spec.min, Math.round(rail * scroller.clientHeight / scroller.scrollHeight));
        var y = (rail - size) * (scroller.scrollTop / overflow);
        var x = barRect.right - spec.rail;
        el.style.height = rail + 'px';
        barThumb.style.height = size + 'px';
        el.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(barRect.top) + 'px,0)';
        barThumb.style.transform = 'translate3d(0,' + Math.round(y) + 'px,0)';
        el.style.opacity = '1';
        barReportGeometry(scroller, spec);
    }

    function barOnFrame() {
        barFrame = 0;
        try {
            if (barTarget) {
                barPaint(barTarget);
            }
        } catch (e) {
            // the page is being torn down; the bar simply stops updating
        }
    }

    function barHide() {
        barHideTimer = 0;
        barLive = false;
        if (barEl) {
            barEl.style.opacity = '0';
        }
    }

    function barSkippedHere(el) {
        if (barSkipped && barSkipped.has(el)) {
            return true;
        }
        var skip = false;
        try {
            skip = !!(el.closest && (
                el.closest('[class*="scrollbar-hide"]') ||
                el.closest('[data-zcode-pptx-render-surface]') ||
                el.closest('.xterm-viewport')
            ));
        } catch (e) {
            skip = false;
        }
        if (skip && barSkipped) {
            barSkipped.add(el);
        }
        return skip;
    }

    function barOnScroll(event) {
        try {
            var target = event.target;
            if (target === document || target === document.documentElement) {
                target = document.scrollingElement || target;
            }
            if (!target || target.nodeType !== 1 ||
                typeof target.getBoundingClientRect !== 'function') {
                return;
            }
            // Vertical overflow only, and never where the page hid the bar on
            // purpose (its own `.scrollbar-hide`, terminal viewport, ...).
            if (target.scrollHeight - target.clientHeight <= 0) {
                return;
            }
            if (barSkippedHere(target)) {
                return;
            }
            barTarget = target;
            if (!barLive) {
                // Burst start: re-measure once, then only read scrollTop per
                // frame — no layout reads in the scroll path.
                barLive = true;
                barRect = null;
            }
            if (!barFrame) {
                barFrame = scheduleFrame(barOnFrame);
            }
            if (barHideTimer) {
                clearTimeout(barHideTimer);
            }
            barHideTimer = setTimeout(barHide, BAR_FADE_MS);
        } catch (e) {
            // never let a scroll listener break the page
        }
    }

    function installOverlayScrollbar() {
        try {
            // `scroll` does not bubble, but it still travels the capture path
            // from window down to the target — so one listener sees every
            // scroller in the page, including ones the page creates later, and
            // without hard-coding a single selector.
            document.addEventListener('scroll', barOnScroll, {capture: true, passive: true});
            // Cached geometry only lives for one burst; a resize can move the
            // scroller between bursts (keyboard, orientation, page re-layout).
            window.addEventListener('resize', function () {
                barRect = null;
            });
        } catch (e) {
            diag('warn', '悬浮滚动条安装失败: ' + e);
        }
    }

    // -----------------------------------------------------------------------
    // boot
    // -----------------------------------------------------------------------
    installScrollbarWidth();
    installOverlayScrollbar();
    try {
        installPageStateReporter();
    } catch (e) {
        diag('error', '页面状态观察器安装失败: ' + e);
    }
    try {
        installVisibilityHijack();
    } catch (e) {
        diag('error', '可见性劫持失败: ' + e);
    }
    try {
        installWebSocketHook();
    } catch (e) {
        diag('error', 'WebSocket hook 失败: ' + e);
    }
    startConversationStreamMonitor();
    installLongTaskObserver();
    // 只报 href：`subscribeAll` 随 D7 一起删除（2026-09-17），别再往这帧里加"能力开关"。
    post('ready', {href: location.href});
    reportLiveness();
    // After the first layout pass, and again whenever the viewport changes.
    setTimeout(reportViewport, 1200);
    window.addEventListener('resize', function () {
        setTimeout(reportViewport, 300);
    });
    // Navigation timing has to wait for the load event (and a moment after it,
    // so the loadEventEnd of late resources is populated).
    if (document.readyState === 'complete') {
        setTimeout(reportLoadTiming, 1000);
    } else {
        window.addEventListener('load', function () {
            setTimeout(reportLoadTiming, 1000);
        });
    }
})();
