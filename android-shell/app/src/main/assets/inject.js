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
 *   6. Stand in for the page's scrollbar: zero its space-taking rail and draw an
 *      overlay bar, so the page's content is centred and the position indicator
 *      survives (Android WebView will not render overlay scrollbars itself).
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

    function trackSocket(socket, url) {
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
     * These exist to answer one question the field log could not: while the app is
     * backgrounded, is the desktop still sending pair acks, and are OUR probes
     * actually going out? The page's own ack watchdog (30s, re-armed by any
     * pair_status_ack) is re-armed by our probe's ack too, so if both numbers are
     * non-zero and the page still closes its socket every ~120s, the watchdog is
     * not the explanation and the search moves on.
     */
    var linkWindow = {acks: 0, probes: 0};

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
        linkWindow.acks = 0;
        linkWindow.probes = 0;
        if (frames === 0 && longTasks === 0 && acks === 0 && probes === 0) {
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
            '链路 ack ' + acks + ' · 探针 ' + probes + ' · paired ' + relayPaired +
            ' socket ' + (socket ? socket.readyState : -1));
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
        next.onSessions = function (update) {
            post('sessions', update);
        };
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

    /** Sends a business payload on the socket the page has open. */
    function injectPayload(payload) {
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
                diag('warn', '注入失败: 没有可用的 relay socket');
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
        if (frame.type !== 'data' || !frame.payload ||
            typeof frame.payload !== 'object') {
            return;
        }
        if (outbound) {
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
        if (!appForeground) {
            liveness.backgroundTicks += 1;
            if (liveness.backgroundFirstTickDelayMs < 0 && backgroundStartedWallMs > 0) {
                liveness.backgroundFirstTickDelayMs = nowMs - backgroundStartedWallMs;
            }
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
        });
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
            return;
        }
        lastPairAckAt = Date.now();
        staleTicks = 0;
        injectPayload({
            type: 'pair_status_query',
            device_sid: deviceSid,
            client_ts: Date.now()
        });
    };

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
    // 6. overlay scrollbar
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
    var SCROLLBAR_CSS = '::-webkit-scrollbar{width:0!important;height:0!important}';

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
