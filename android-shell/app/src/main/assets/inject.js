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

    function config() {
        if (!bridge || typeof bridge.config !== 'function') {
            return {subscribeAll: true};
        }
        try {
            var raw = bridge.config();
            var parsed = raw ? JSON.parse(raw) : null;
            return parsed && typeof parsed === 'object' ? parsed : {subscribeAll: true};
        } catch (e) {
            return {subscribeAll: true};
        }
    }

    // -----------------------------------------------------------------------
    // 1. visibility hijack (decision D12)
    //
    // The page decides whether to keep its relay work running from the Page
    // Visibility API. Spoofing it keeps the page in "foreground" mode while the
    // app is backgrounded. This does NOT stop the browser from throttling
    // timers — only the foreground service and renderer priority do that — but
    // it stops the page from deliberately pausing itself.
    // -----------------------------------------------------------------------
    var LIFECYCLE_EVENTS = {
        visibilitychange: 1,
        pagehide: 1,
        freeze: 1,
        blur: 1
    };

    function installVisibilityHijack() {
        var readOnly = function (value) {
            return {get: function () {
                return value;
            }, configurable: true};
        };
        var noopHandler = {
            get: function () {
                return null;
            },
            set: function () {},
            configurable: true
        };
        try {
            Object.defineProperty(Document.prototype, 'hidden', readOnly(false));
            Object.defineProperty(Document.prototype, 'visibilityState', readOnly('visible'));
            // Some engines expose these as own properties of the instance.
            Object.defineProperty(document, 'hidden', readOnly(false));
            Object.defineProperty(document, 'visibilityState', readOnly('visible'));
            Object.defineProperty(Document.prototype, 'onvisibilitychange', noopHandler);
            if (typeof Window !== 'undefined' && Window.prototype) {
                Object.defineProperty(Window.prototype, 'onpagehide', noopHandler);
                Object.defineProperty(Window.prototype, 'onblur', noopHandler);
            }
            if (Document.prototype.hasFocus) {
                Document.prototype.hasFocus = function () {
                    return true;
                };
            }
        } catch (e) {
            diag('warn', '可见性属性劫持部分失败: ' + e);
        }

        var originalAdd = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function (type, listener, options) {
            // Only window/document lifecycle signals are suppressed; element
            // level events (input, scroll, blur on a field) are untouched.
            if (LIFECYCLE_EVENTS[type] === 1 && (this === document || this === window)) {
                return;
            }
            return originalAdd.call(this, type, listener, options);
        };
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

    function installWebSocketHook() {
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

    function trackSocket(socket, url) {
        if (socketKnown(socket)) {
            return;
        }
        if (url) {
            lastRelayUrl = url;
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
        diag('debug', '页面开销 ' + Math.round(elapsed / 1000) + 's：收帧 ' + frames +
            ' 个（' + Math.round(chars / 1024) + 'K 字符，解码合计 ' +
            Math.round(decodeMs) + 'ms，单帧最长 ' + Math.round(perf.decodeMsMax) + 'ms）· ' +
            '长任务 ' + longTasks + ' 个（合计 ' + Math.round(longTaskMs) +
            'ms，最长 ' + Math.round(perf.longTaskMaxMs) + 'ms）· ' +
            '发帧 ' + outFrames + ' 个（' + Math.round(outChars / 1024) + 'K 字符）· ' +
            '链路 ack ' + acks + ' · 探针 ' + probes + ' · paired ' + relayPaired +
            ' socket ' + (socket ? socket.readyState : -1) +
            ' · 页面心跳 ' + pageBeats);
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
    var startScheduled = false;
    var startAttempts = 0;

    /**
     * What the page already streams is a fact about the PAGE, not about one
     * client instance. `resetClient()` runs on every relay disconnect, so this
     * object has to outlive it: without it the rebuilt client re-opens an active
     * bridge for the very workspace the page is showing, the desktop rejects the
     * duplicate with rpc-transport-fault, and the reopen loop keeps hammering the
     * channel the user's own conversation request is queued on.
     */
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
        var next = new P.RemoteClient({
            send: injectPayload,
            log: function (message) {
                diag('debug', message);
            },
            subscribeAll: cfg.subscribeAll !== false,
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
        next.onStatus = function (status) {
            post('status', status);
            if (!status.active && next.subscribeAll && startAttempts < 3) {
                // The desktop may not have been ready for our workspace list
                // yet; retry a couple of times before settling for passive.
                startAttempts += 1;
                setTimeout(function () {
                    next.retryStart();
                }, 20000);
            }
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
        startScheduled = false;
    }

    // -----------------------------------------------------------------------
    // 3b. when to open our own bridges
    //
    // Not the moment pairing completes. The page is usually still loading its
    // first conversation then, and one bridge costs four RPCs per workspace
    // (hello, initialize, subscribe, listen) on the *same* relay socket: on
    // device, seven workspaces took ~10 s of solid handshaking, all of it queued
    // in front of whatever the user was opening. So the subscription waits for
    // the page's own traffic to go quiet, with a hard cap so a busy page can
    // never postpone notifications indefinitely.
    // -----------------------------------------------------------------------

    /** First opportunity to start (the previous fixed delay). */
    var ACTIVE_START_DELAY_MS = 1500;
    /** Our bridges only start once the socket has been quiet this long. */
    var ACTIVE_START_QUIET_MS = 800;
    /** Re-check interval while the page is still busy. */
    var ACTIVE_START_RETRY_MS = 1000;
    /** Upper bound on the deferral, however busy the page is. */
    var ACTIVE_START_MAX_DEFER_MS = 12000;

    function maybeStartActive() {
        if (!relayPaired || startScheduled || !client) {
            return;
        }
        if (client.isStarted && client.isStarted()) {
            // The desktop answers every heartbeat with a pair ack, and each ack
            // re-enters here. start() is a no-op after the first call, so logging
            // again would claim a restart that never happened — 125 of those
            // buried the real (re)subscribe lines in the first field log.
            return;
        }
        startScheduled = true;
        var deadline = Date.now() + ACTIVE_START_MAX_DEFER_MS;
        var deferredLogs = 0;

        var attempt = function () {
            if (!relayPaired || !client || !client.subscribeAll) {
                startScheduled = false;
                return;
            }
            var quietFor = liveness.lastInboundAt ?
                Date.now() - liveness.lastInboundAt : Number.MAX_VALUE;
            var inFlight = (client.inFlightPageRpcs && client.inFlightPageRpcs()) || 0;
            // Two conditions, not one. "The page stopped receiving frames" was
            // not enough: opening a task leaves a request in flight for seconds,
            // and the handshake burst then queues on the same relay socket right
            // in front of it. Now the burst also waits until the page has no
            // request outstanding. The 12s cap still bounds the wait, so a page
            // that always has something pending cannot postpone this forever.
            if (Date.now() < deadline && (quietFor < ACTIVE_START_QUIET_MS || inFlight > 0)) {
                if (deferredLogs < 3) {
                    deferredLogs += 1;
                    diag('debug', '推迟主动订阅：收帧于 ' +
                        (quietFor === Number.MAX_VALUE ? '∞' : Math.round(quietFor)) +
                        'ms 前，在飞页面请求 ' + inFlight + ' 个');
                }
                setTimeout(attempt, ACTIVE_START_RETRY_MS);
                return;
            }
            startScheduled = false;
            diag('debug', 'active subscribe start（页面已空闲 ' +
                (quietFor === Number.MAX_VALUE ? '∞' : Math.round(quietFor)) + 'ms）');
            client.start().catch(function () {});
        };

        setTimeout(attempt, ACTIVE_START_DELAY_MS);
    }

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
                ensureClient();
                maybeStartActive();
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
    /** Ticks closer together than this are dropped so the timer and the pump cannot double up. */
    var MIN_TICK_GAP_MS = 5000;
    var lastPairAckAt = 0;
    var lastForcedReconnectAt = 0;
    var lastTickAt = 0;
    var staleTicks = 0;
    var appForeground = true;
    var backgroundStartedWallMs = 0;
    var lastBackgroundSilenceLoggedAt = 0;

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
        // Sent on both branches on purpose: the probe is the recovery path for a
        // merely-stale link, and the desktop's ack is also what keeps the page's
        // own ack watchdog from declaring the connection dead.
        injectPayload({
            type: 'pair_status_query',
            device_sid: deviceSid,
            client_ts: nowMs
        }, true);
        linkWindow.probes += 1;
        return true;
    }

    /**
     * Rebuilds the relay socket by closing it: the recovery itself belongs to
     * the page, which is the only party that owns the socket's lifecycle. Rate
     * limited so a dead desktop cannot make us churn. Returns true when the
     * socket was actually closed.
     */
    function forceReconnect(reason) {
        if (Date.now() - lastForcedReconnectAt <= RECONNECT_MIN_GAP_MS) {
            return false;
        }
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            return false;
        }
        lastForcedReconnectAt = Date.now();
        staleTicks = 0;
        diag('warn', '强制重建 relay 连接以恢复（' + reason + '）');
        try {
            socket.close();
        } catch (e) {
            // ignore
        }
        return true;
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
    // 5b. 3s dual-state fast refresh
    //
    // Entering a conversation on a cold session can strand the page in one of
    // two states. State A: the header stays on the fallback title 新建任务 —
    // the page never resolved the task. State B: the title resolves but the
    // conversation content never arrives, and the composer sits there greyed
    // out — the page's own signal that content has not loaded. The page
    // swallows its failures silently and usually heals in 9–22 s; the user
    // chose the blunt version instead: check ONCE, 3 s after the entry beacon,
    // and reload the page if either state is on screen. After a reload the
    // page re-opens its last task on its own (observed in the field log).
    //
    // Everything the earlier design guarded is gone on purpose (user's call):
    // no draft guard, no foreground check, no title cross-check, no
    // per-session limit. ONE piece remains because without it the loop cannot
    // terminate: a 15 s minimum gap between reloads. A fresh page gets checked
    // at +3 s — always before content had a chance — so an unguarded check
    // would reload forever and the page would never finish loading. With the
    // gap, a stuck page retries every ~15 s and a loaded page stops for good.
    // The gap is also bridged across the reload itself via sessionStorage.
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
    var FALLBACK_CHECK_MS = 3000;
    var FALLBACK_RELOAD_GAP_MS = 15000;
    var FALLBACK_STORE_AT = 'zcodeShellFastRefreshAt';
    // 第二级检查：DOM 判不出「标题正确 + 输入框可用 + 内容不来」（2026-09-12 真机
    // 日志证实该形态存在且输入框并不灰），改用协议层信号——信标后页面桥若在
    // CONTENT 窗口内零入站帧，说明桌面端什么都没下发，刷新换一条连接。
    var FALLBACK_CONTENT_CHECK_MS = 10000;

    var fallbackTimer = null;
    var contentTimer = null;
    var fallbackState = {lastReloadAt: 0, beaconAt: 0, beaconGen: 0};
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

    /** 进对话信标：重置 3s DOM 检查与 10s 内容检查（快速切换时以最后一次为准）。 */
    function scheduleFallbackCheck() {
        if (fallbackTimer) {
            clearTimeout(fallbackTimer);
        }
        fallbackTimer = setTimeout(fallbackCheck, FALLBACK_CHECK_MS);
        if (contentTimer) {
            clearTimeout(contentTimer);
        }
        fallbackState.beaconAt = Date.now();
        fallbackState.beaconGen = client ? client.gen : 0;
        contentTimer = setTimeout(fallbackContentCheck, FALLBACK_CONTENT_CHECK_MS);
    }

    function fallbackCheck() {
        if (fallbackTimer) {
            // 自然到期之外的手动触发（测试/控制台）也要清掉挂起的定时器，
            // 否则同一检查会跑两次。
            clearTimeout(fallbackTimer);
        }
        fallbackTimer = null;
        var header = '';
        var composer = null;
        try {
            header = findHeaderText();
            composer = composerElement();
        } catch (e) {
            // DOM 半拆的这一拍不判定；下一个信标会重新武装。
            return;
        }
        var headerBad = header !== '';
        var composerBad = !!(composer && elementDisabled(composer));
        if (!headerBad && !composerBad) {
            // 全就绪本身是恢复信号：撤防 + 复位连续刷新计数。
            diag('debug', '3s 检查：标题与输入框均就绪');
            stallCancel('3s 检查：标题与输入框均就绪');
            return;
        }
        // 坏态交给卡死看门狗分级处置（先轻推、后刷新，见 5c 节），
        // 这里不再直接 reload。
        stallArm('3s DOM 检查: ' + (headerBad ? '标题回退' : '输入框未就绪'));
    }

    /**
     * 第二级检查（+10s）：DOM 全就绪但桌面端零下发。信标之后页面桥但凡收到过
     * 任何 rpc-frame（会话内容、rows 结果、事件都算），就认为内容在路上；一帧
     * 都没有才判卡死。链路重建（client 换代）的窗口跳过本轮——恢复期不插刀。
     */
    function fallbackContentCheck() {
        if (contentTimer) {
            clearTimeout(contentTimer);
        }
        contentTimer = null;
        var clientNow = client;
        if (!clientNow || clientNow.gen !== fallbackState.beaconGen) {
            diag('debug', '10s 内容检查：期间链路重建，本轮不判');
            return;
        }
        if (typeof clientNow.lastPageBridgeTrafficAt !== 'function') {
            return;
        }
        var trafficAt = clientNow.lastPageBridgeTrafficAt();
        if (trafficAt >= fallbackState.beaconAt) {
            diag('debug', '10s 内容检查：页面桥有下发（内容已在路上）');
            stallCancel('10s 内容检查：页面桥有下发（内容已在路上）');
            return;
        }
        stallArm('10s 内容检查: 页面桥零下发（标题与输入框正常但无任何入站帧）');
    }

    /** Upload-named page RPCs get explicit lines: that is the file-send chain. */
    var UPLOAD_RPC_RE = /upload|attachment|artifact/i;

    function notePageRpcCall(call) {
        if (!call) {
            return;
        }
        if (UPLOAD_RPC_RE.test(call.name)) {
            diag('info', '页面上传调用开始：' + call.name);
        }
        if (FALLBACK_ENTRY_METHODS[call.name]) {
            scheduleFallbackCheck();
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
        check: fallbackCheck,
        contentCheck: fallbackContentCheck,
        state: function () {
            return fallbackState;
        },
        timer: function () {
            return fallbackTimer;
        },
        contentTimer: function () {
            return contentTimer;
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
        var v = readVitals();
        // 只在体征"可读且健康"时撤防；读不到（DOM 半拆/极端环境）不算恢复，
        // 继续走梯子——布防理由本身已经是证据。
        if (v && !vitalsStalled(v)) {
            stallCancel('判定时已恢复');
            return;
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
     * 轻推：关掉共享 socket，恢复归页面。与心跳陈旧路径的 forceReconnect
     * 共享 180s 限流时钟，两条路不会在同一段链路上反复开刀。
     */
    function nudgeReconnect(reason) {
        var socket = activeSocket;
        if (!socket || socket.readyState !== 1) {
            diag('info', '卡死轻推：当前没有活动 socket，等页面自己重建');
            return false;
        }
        lastForcedReconnectAt = Date.now();
        diag('warn', '卡死轻推：关闭 relay socket 触发页面自愈（' + reason + '）');
        try {
            socket.close();
        } catch (e) {
            diag('warn', '卡死轻推关闭失败: ' + e);
            return false;
        }
        return true;
    }

    function stallReloadIfAllowed() {
        var stamp = parseInt(storeGet(FALLBACK_STORE_AT), 10);
        var last = Math.max(stallState.lastReloadAt, isNaN(stamp) ? 0 : stamp);
        var wait = last + FALLBACK_RELOAD_GAP_MS - Date.now();
        if (wait > 0) {
            diag('info', '卡死看门狗：处于刷新间隔内，' + Math.round(wait / 1000) + 's 后复查');
            stallState.timer = setTimeout(stallFire, wait);
            return;
        }
        var count = stallState.reloadCount;
        if (count >= STALL_RELOAD_CAP) {
            stallState.gaveUp = true;
            stallState.armed = false;
            diag('error', '卡死看门狗：连续刷新 ' + count + ' 次未恢复，停止自动干预' +
                '（恢复信号到达后自动复位）');
            postPageVitals('giveup');
            return;
        }
        count += 1;
        stallState.reloadCount = count;
        storeSet(STALL_STORE_RELOADS, String(count));
        stallState.lastReloadAt = Date.now();
        storeSet(FALLBACK_STORE_AT, String(stallState.lastReloadAt));
        diag('warn', '卡死看门狗：轻推后仍无内容，第 ' + count + '/' + STALL_RELOAD_CAP +
            ' 次刷新页面');
        try {
            G.location.reload();
        } catch (e) {
            diag('warn', '自动刷新失败: ' + e);
        }
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

    /** 页面日志事件 → 看门狗布防/撤防。只有当前界面真的卡着才布防。 */
    function notePageLogEvent(name) {
        if (PAGE_LOG_STALL_EVENTS[name]) {
            if (vitalsStalled(readVitals())) {
                stallArm('页面日志 ' + name);
            } else {
                diag('debug', '页面日志失败事件（当前界面无卡态，不布防）: ' + name);
            }
        } else if (PAGE_LOG_RECOVER_EVENTS[name]) {
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

    G.__zcodeShellDiag = function (cmd) {
        try {
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
            forceReconnect(silence < 0 ?
                '回前台且从未收到帧' :
                '回前台时已静默 ' + Math.round(silence / 1000) + 's');
            reportPageState();
            return;
        }
        lastPairAckAt = Date.now();
        staleTicks = 0;
        injectPayload({
            type: 'pair_status_query',
            device_sid: deviceSid,
            client_ts: Date.now()
        }, true);
        reportPageState();
    };

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
                    ' scale=' + (vv ? Math.round(vv.scale * 100) / 100 : 'n/a')
            });
        } catch (e) {
            // page torn down; nothing to report
        }
    }

    /** Lets the native side flip the subscribe-all switch without a reload. */
    G.__zcodeShellSetSubscribeAll = function (enabled) {
        if (!client) {
            return;
        }
        client.subscribeAll = enabled !== false;
        if (client.subscribeAll) {
            client.retryStart();
        } else {
            client.dispose();
        }
    };

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
    var SCROLLBAR_CSS = '::-webkit-scrollbar{width:0!important;height:0!important}' +
        '[data-v4-timeline-scroll]{scrollbar-gutter:auto!important}';

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

    // The page's own thumb numbers, reused so the overlay is indistinguishable
    // from it: a 14px rail whose thumb is inset 3px per side (border:3px solid
    // transparent + background-clip:padding-box), rounded, and at least 32px.
    var BAR_INSET = 3;
    var BAR_WIDTH = 8;
    var BAR_MIN = 32;
    var BAR_FADE_MS = 700;
    var BAR_COLOR_FALLBACK = 'rgba(128,128,128,0.5)';
    var BAR_Z = 2147483647;

    var barEl = null;
    var barTarget = null;
    var barRect = null;
    var barFrame = 0;
    var barHideTimer = 0;
    var barLive = false;
    // Scrollers the page deliberately keeps bar-less; verdicts are cached per
    // element so the check runs at most once per scroller.
    var barSkipped = typeof WeakSet === 'function' ? new WeakSet() : null;

    function scheduleFrame(fn) {
        if (typeof G.requestAnimationFrame === 'function') {
            return G.requestAnimationFrame(fn);
        }
        return setTimeout(fn, 16);
    }

    function barElement() {
        if (barEl) {
            return barEl;
        }
        var el = document.createElement('div');
        el.setAttribute('data-zcode-shell', 'scrollbar');
        var s = el.style;
        // Out of flow and never a hit target: the page's own interaction and
        // layout must not be able to tell it is there.
        s.position = 'fixed';
        s.left = '0px';
        s.top = '0px';
        s.width = BAR_WIDTH + 'px';
        s.borderRadius = '9999px';
        s.opacity = '0';
        s.pointerEvents = 'none';
        s.zIndex = String(BAR_Z);
        s.transition = 'opacity 160ms linear';
        s.willChange = 'transform, opacity';
        // The page's colour token, read from the scroller at paint time: it is
        // defined on the page's theme wrapper, so it follows light/dark for
        // free and nothing here has to know either value.
        s.background = BAR_COLOR_FALLBACK;
        (document.body || document.documentElement).appendChild(el);
        barEl = el;
        return el;
    }

    function barPaint(scroller) {
        var el = barElement();
        if (!barRect) {
            barRect = scroller.getBoundingClientRect();
            try {
                var token = G.getComputedStyle(scroller).getPropertyValue('--color-border');
                if (token) {
                    el.style.background = token.trim();
                }
            } catch (e) {
                // keep the fallback; the bar is still visible and correct
            }
        }
        var overflow = scroller.scrollHeight - scroller.clientHeight;
        var track = barRect.height - BAR_INSET * 2;
        if (overflow <= 0 || track <= BAR_MIN) {
            el.style.opacity = '0';
            return;
        }
        var size = Math.max(BAR_MIN, Math.round(track * scroller.clientHeight / scroller.scrollHeight));
        var y = barRect.top + BAR_INSET + (track - size) * (scroller.scrollTop / overflow);
        var x = barRect.right - BAR_INSET - BAR_WIDTH;
        el.style.height = size + 'px';
        el.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(y) + 'px,0)';
        el.style.opacity = '1';
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
    startHeartbeat();
    installLongTaskObserver();
    post('ready', {href: location.href, subscribeAll: config().subscribeAll !== false});
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
