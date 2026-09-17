/**
 * inject.js tests — the injected layer, exercised against a simulated page.
 *
 * inject.js is the other half of the risk: it patches Window/Document/WebSocket
 * inside someone else's production SPA at document-start, and a bug there breaks
 * the user's page rather than merely losing a notification. None of that can be
 * checked on a device without Android, so the page environment is faked here:
 * a minimal EventTarget/Document/Window tree plus a WebSocket that records what
 * was sent, with the shared fake desktop on the other end.
 *
 * Covered: idempotency, the read-only contract (the visibility hijack stays
 * off and the shell writes nothing to the page's socket while in the
 * foreground), hook transparency (statics, instanceof, no feedback loop), the
 * full active-subscribe handshake over the socket, passive reading of the
 * page's own stream, the title-matching task locator, and the promise that
 * credentials never reach the diagnostic log.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const {DesktopCore, encodeBody, fragment, snapshotWire} = require('./fake-desktop.js');

const PROTOCOL_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'zcode-protocol.js');
const INJECT_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'inject.js');

// ---------------------------------------------------------------------------
// simulated page environment
//
// The classes are module-level, so prototype patches applied by one test would
// leak into the next. PRISTINE records the unpatched implementations and
// setupPage() restores them, which keeps each test isolated.
// ---------------------------------------------------------------------------

function FakeEventTarget() {
    this._listeners = Object.create(null);
}

FakeEventTarget.prototype.addEventListener = function (type, listener) {
    (this._listeners[type] || (this._listeners[type] = [])).push(listener);
};

FakeEventTarget.prototype.removeEventListener = function (type, listener) {
    const list = this._listeners[type];
    if (list) {
        this._listeners[type] = list.filter((fn) => fn !== listener);
    }
};

FakeEventTarget.prototype.dispatchEvent = function (event) {
    const list = this._listeners[event.type];
    if (list) {
        for (const listener of list.slice()) {
            listener.call(this, event);
        }
    }
    return true;
};

class FakeElement extends FakeEventTarget {
    constructor(tagName) {
        super();
        this.nodeType = 1;
        this.tagName = String(tagName).toUpperCase();
        this.children = [];
        this.parentElement = null;
        this._text = '';
        this._attributes = Object.create(null);
        this.dispatched = [];
        this.cursor = 'auto';
        this.isConnected = true;
    }

    get childElementCount() {
        return this.children.length;
    }

    get textContent() {
        if (this.children.length === 0) {
            return this._text;
        }
        return this._text + this.children.map((child) => child.textContent).join('');
    }

    set textContent(value) {
        this._text = value;
        this.children = [];
    }

    setAttribute(name, value) {
        this._attributes[name] = value;
    }

    getAttribute(name) {
        return name in this._attributes ? this._attributes[name] : null;
    }

    hasAttribute(name) {
        return name in this._attributes;
    }

    appendChild(child) {
        child.parentElement = this;
        this.children.push(child);
        return child;
    }

    scrollIntoView() {}

    dispatchEvent(event) {
        this.dispatched.push(event.type);
        return super.dispatchEvent(event);
    }
}

class FakeDocument extends FakeEventTarget {
    constructor() {
        super();
        this.body = new FakeElement('body');
    }

    hasFocus() {
        return false;
    }

    querySelectorAll(selector) {
        if (selector !== 'body *') {
            return [];
        }
        const out = [];
        const walk = (node) => {
            for (const child of node.children) {
                out.push(child);
                walk(child);
            }
        };
        walk(this.body);
        return out;
    }
}

/**
 * Minimal MutationObserver: records its targets and lets a test deliver one
 * batch by hand. Only installed for tests that opt into the page-state harness —
 * inject.js must also survive a page where the API is missing, and that is the
 * default (no-option) shape of setupPage().
 */
class FakeMutationObserver {
    constructor(callback) {
        this.callback = callback;
        this.targets = [];
        FakeMutationObserver.instances.push(this);
    }

    observe(target, options) {
        this.targets.push({target, options});
    }

    disconnect() {
        this.targets = [];
    }

    /** Test helper: run the callback once for every live observer. */
    static fire() {
        for (const observer of FakeMutationObserver.instances.slice()) {
            observer.callback([], observer);
        }
    }
}

FakeMutationObserver.instances = [];

/**
 * matchMedia stand-in: one instance per query, so a test can flip `matches` and
 * have the change listeners fire — which is how rotation / a system theme switch
 * reach the script.
 */
class FakeMediaQueryList {
    constructor(query, matches) {
        this.query = query;
        this.matches = matches;
        this.listeners = [];
    }

    addEventListener(type, listener) {
        if (type === 'change') {
            this.listeners.push(listener);
        }
    }

    removeEventListener(type, listener) {
        this.listeners = this.listeners.filter((fn) => fn !== listener);
    }

    setMatches(matches) {
        this.matches = matches;
        for (const listener of this.listeners.slice()) {
            listener({matches});
        }
    }
}

class FakeWindow extends FakeEventTarget {}

class FakeWebSocket extends FakeEventTarget {
    constructor(url, protocols) {
        super();
        this.url = url;
        this.protocols = protocols;
        this.readyState = FakeWebSocket.OPEN;
        this.sent = [];
        FakeWebSocket.instances.push(this);
    }

    send(data) {
        this.sent.push(data);
    }

    close() {
        this.readyState = FakeWebSocket.CLOSED;
        this.dispatchEvent({type: 'close'});
    }

    /** Test helper: deliver a relay frame to the page. */
    receive(frame) {
        this.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
    }
}

FakeWebSocket.CONNECTING = 0;
FakeWebSocket.OPEN = 1;
FakeWebSocket.CLOSING = 2;
FakeWebSocket.CLOSED = 3;
FakeWebSocket.instances = [];

const PRISTINE = {
    eventTargetAdd: FakeEventTarget.prototype.addEventListener,
    webSocketSend: FakeWebSocket.prototype.send
};

function setupPage(options) {
    const previous = new Map();
    const install = (name, value) => {
        if (!previous.has(name)) {
            previous.set(name, name in globalThis ? globalThis[name] : undefined);
        }
        globalThis[name] = value;
    };

    // Undo prototype patches left behind by a previous test.
    FakeEventTarget.prototype.addEventListener = PRISTINE.eventTargetAdd;
    FakeWebSocket.prototype.send = PRISTINE.webSocketSend;
    FakeWebSocket.instances = [];
    delete globalThis.__zcodeShellInstalled;

    const document = new FakeDocument();
    const window = new FakeWindow();
    const posts = [];
    // 原生 config() 的真实形状：`passiveObserve` + `runningSessions`。
    // 2026-09-17：D7「订阅所有工作区」（`subscribeAll`）删除后这里不再有这个字段，
    // 所以每个测试跑的都是**只读壳**的默认配置——注入层不许再主动开桥。
    const configValue = {passiveObserve: true};

    install('EventTarget', FakeEventTarget);
    install('Document', FakeDocument);
    install('Window', FakeWindow);
    install('document', document);
    install('window', window);
    install('WebSocket', FakeWebSocket);
    install('location', {href: 'https://zcode.z.ai/remote/v4?sid=redacted'});
    install('getComputedStyle', (el) => ({cursor: el.cursor}));
    install('MouseEvent', function (type) {
        return {type, bubbles: true};
    });
    install('PointerEvent', function (type) {
        return {type, bubbles: true};
    });
    install('ZCodeShell', {
        postMessage(json) {
            posts.push(JSON.parse(json));
        },
        config() {
            return JSON.stringify(configValue);
        }
    });

    // ---------------------------------------------------------------------
    // Opt-in page-state harness.
    //
    // The page-state reporter needs a DOM the default fake does not have
    // (documentElement, getElementsByClassName, MutationObserver, matchMedia).
    // It is opt-in so the other tests keep exercising the *bare* page they were
    // written for — inject.js has to degrade quietly when those APIs are absent.
    // ---------------------------------------------------------------------
    const pageState = (options && options.pageState) || null;
    let mediaQueries = [];
    const dom = {classes: Object.create(null)};

    if (pageState) {
        const buildRoot = () => {
            const html = new FakeElement('html');
            html.appendChild(document.body);
            document.documentElement = html;
            return html;
        };
        // `noDocumentElement` reproduces the document-start race: the script runs
        // before <html> exists and must retry rather than give up.
        let root = (options && options.noDocumentElement) ? null : buildRoot();
        document.createElement = (tag) => {
            const el = new FakeElement(tag);
            el.style = {};
            return el;
        };
        document.getElementsByClassName = (name) => {
            const out = [];
            if (!root) {
                return out;
            }
            const walk = (node) => {
                for (const child of node.children) {
                    if (String(child.getAttribute('class') || '').split(/\s+/).indexOf(name) >= 0) {
                        out.push(child);
                    }
                    walk(child);
                }
            };
            walk(root);
            return out;
        };
        // Every element the page would have for a class, added/removed by name.
        dom.classes = Object.create(null);
        dom.materialiseRoot = () => {
            if (!root) {
                root = buildRoot();
            }
        };
        dom.set = (name, present) => {
            if (!root) {
                return;
            }
            const existing = document.getElementsByClassName(name);
            if (present && existing.length === 0) {
                const el = new FakeElement('div');
                el.setAttribute('class', name);
                root.appendChild(el);
            } else if (!present) {
                for (const el of existing) {
                    root.children = root.children.filter((child) => child !== el);
                }
            }
        };
        dom.setTheme = (theme) => {
            if (!root) {
                return;
            }
            if (theme === null) {
                delete root._attributes['data-zcode-browser-theme-surface'];
            } else {
                root.setAttribute('data-zcode-browser-theme-surface', theme);
            }
        };
        const media = (options && options.media) || {};
        mediaQueries = [];
        install('matchMedia', (query) => {
            let mql = mediaQueries.find((candidate) => candidate.query === query);
            if (!mql) {
                mql = new FakeMediaQueryList(query, media[query] === true);
                mediaQueries.push(mql);
            }
            return mql;
        });
        FakeMutationObserver.instances = [];
        // `noObserver` is the degraded engine: present document, absent API.
        if (!(options && options.noObserver)) {
            install('MutationObserver', FakeMutationObserver);
        }
    }

    // Fresh protocol module: inject.js reads it off the global.
    delete require.cache[require.resolve(PROTOCOL_PATH)];
    const protocol = require(PROTOCOL_PATH);

    // Heartbeats use setInterval and would otherwise keep Node alive.
    const realSetInterval = globalThis.setInterval;
    const intervals = [];
    globalThis.setInterval = function (fn, ms) {
        const handle = realSetInterval(fn, ms);
        intervals.push(handle);
        return handle;
    };

    delete require.cache[require.resolve(INJECT_PATH)];
    require(INJECT_PATH);

    const teardown = () => {
        for (const handle of intervals) {
            clearInterval(handle);
        }
        globalThis.setInterval = realSetInterval;
        for (const [name, value] of previous) {
            if (value === undefined) {
                delete globalThis[name];
            } else {
                globalThis[name] = value;
            }
        }
        delete globalThis.__zcodeShellInstalled;
        delete globalThis.__zcodeShellLocateTask;
        delete globalThis.__zcodeShellSetAppForeground;
        delete globalThis.__zcodeShellHeartbeat;
        delete globalThis.__zcodeShellReportPageState;
        delete globalThis.__zcodeShellPageStateHooked;
        delete globalThis.__zcodeShellFallback;
    };

    return {document, window, posts, protocol, configValue, dom, mediaQueries, teardown};
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function findPost(posts, event, predicate) {
    return posts.filter((p) => p.event === event && (!predicate || predicate(p.data)));
}

function allText(posts) {
    return posts.map((p) => JSON.stringify(p.data)).join('\n');
}

/**
 * Wires a fake desktop to the page socket. The pump drains what the client
 * sends; sentLog keeps a copy for assertions.
 */
function connectDesktop(socket) {
    const desktop = new DesktopCore({
        deliver: (payload) => socket.receive({type: 'data', payload})
    });
    const sentLog = [];
    const originalSend = socket.send.bind(socket);
    socket.send = (data) => {
        sentLog.push(data);
        originalSend(data);
    };
    const pump = setInterval(() => {
        while (socket.sent.length) {
            const text = socket.sent.shift();
            let frame;
            try {
                frame = JSON.parse(text);
            } catch (e) {
                continue;
            }
            if (frame.type === 'data' && frame.payload) {
                desktop.accepts(frame.payload);
            }
        }
    }, 5);
    return {desktop, sentLog, stop: () => clearInterval(pump)};
}

// ---------------------------------------------------------------------------
// hook safety
// ---------------------------------------------------------------------------

test('the injected script is idempotent', () => {
    const page = setupPage();
    try {
        delete require.cache[require.resolve(INJECT_PATH)];
        require(INJECT_PATH);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.send(JSON.stringify({type: 'data', payload: {zcode_type: 'x'}}));
        assert.strictEqual(socket.sent.length, 1, 'send must still work exactly once');
        assert.strictEqual(findPost(page.posts, 'ready').length, 1,
            'the second install must bail out immediately');
    } finally {
        page.teardown();
    }
});

test('the WebSocket wrapper keeps statics, prototype and instanceof intact', () => {
    const page = setupPage();
    try {
        assert.strictEqual(globalThis.WebSocket.OPEN, 1);
        assert.strictEqual(globalThis.WebSocket.CONNECTING, 0);
        assert.strictEqual(globalThis.WebSocket.CLOSED, 3);
        assert.strictEqual(globalThis.WebSocket.name, 'WebSocket');
        const socket = new globalThis.WebSocket('wss://relay.example');
        assert.ok(socket instanceof globalThis.WebSocket, 'instanceof must hold');
        assert.ok(socket instanceof FakeWebSocket);
        assert.strictEqual(socket.url, 'wss://relay.example');
    } finally {
        page.teardown();
    }
});

test('the close log names the real caller, not the wrapper itself', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        await flush();

        // Regression pin. The wrapper builds its Error inside itself, so the naive
        // `stack.split('\n')[1]` names the WRAPPER on every close — and a round of
        // field work read that as "the page closed its own socket". The caller has
        // to survive the wrapper frames.
        const closeFromThePage = () => socket.close(1000, 'bye');
        closeFromThePage();
        await flush();

        const line = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('socket.close() 被调用') === 0)
            .pop();
        assert.ok(line, 'the close must be logged');
        assert.ok(line.includes('closeFromThePage'), line);
        assert.ok(!line.includes('wrappedClose'), 'the wrapper must not name itself: ' + line);
        assert.ok(line.includes('(1000)'), 'the close code must be reported: ' + line);
    } finally {
        page.teardown();
    }
});

// 2026-09-15 晚：可见性劫持停用。它挡不住 Chromium 的定时器节流（真机后台 7 分钟里
// 页面的 10s 心跳只跳了 6 次），却把页面自救用的生命周期事件全吞了——于是页面既不
// 知道自己去过后台，也不知道自己回来了（回前台后重订阅收不到 ack，对话 0 行，
// 只有重启应用才恢复）。现在：不再对页面说假话。
test('可见性劫持已停用：页面拿到真实可见性，生命周期事件照常送达', () => {
    const page = setupPage();
    try {
        assert.ok(!Object.getOwnPropertyDescriptor(globalThis.document, 'hidden'),
            'document.hidden must not be replaced by a spoofed getter');
        assert.ok(!Object.getOwnPropertyDescriptor(FakeDocument.prototype, 'visibilityState'),
            'visibilityState must not be replaced by a spoofed getter');
        assert.strictEqual(globalThis.document.hasFocus(), false,
            'hasFocus must keep the page\'s own answer (the fake document says false)');

        let fired = 0;
        globalThis.document.addEventListener('visibilitychange', () => fired++);
        globalThis.window.addEventListener('pagehide', () => fired++);
        globalThis.window.addEventListener('blur', () => fired++);
        const dispatch = (target, type) => {
            const list = target._listeners[type] || [];
            for (const listener of list.slice()) {
                listener.call(target, {type});
            }
        };
        dispatch(globalThis.document, 'visibilitychange');
        dispatch(globalThis.window, 'pagehide');
        dispatch(globalThis.window, 'blur');
        assert.strictEqual(fired, 3,
            'the page\'s own suspend/recover listeners must receive their events again');

        // Ordinary element events still work.
        const input = new FakeElement('input');
        let typed = 0;
        input.addEventListener('input', () => typed++);
        PRISTINE.eventTargetAdd.call(input, 'input', () => typed++);
        dispatch(input, 'input');
        assert.strictEqual(typed, 2, 'non-lifecycle events must pass through');
    } finally {
        page.teardown();
    }
});

test('不再给 document.onvisibilitychange 装空处理器（页面可以自己挂）', () => {
    const page = setupPage();
    try {
        let called = 0;
        const handler = () => {
            called += 1;
        };
        globalThis.document.onvisibilitychange = handler;
        assert.strictEqual(globalThis.document.onvisibilitychange, handler,
            'the page must keep the property it set');
        assert.strictEqual(called, 0, 'assignment alone must not call it');
    } finally {
        page.teardown();
    }
});

// 回前台"死链兜底"（healDeadLinkOnResume）：只有三条判据同时成立才重载页面。
// 这是"长后台回来重订阅收不到 ack、对话 0 行、只能重启应用"那个现场的对策。
test('回前台死链兜底：有帧进来＝页面自己恢复了，一帧都不许重载', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        // 退后台再回前台：此刻 lastInboundAt 还是刚才那帧 → 静默 <60s，兜底根本不启动。
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        // 观察窗内页面自己恢复了（来一帧）。
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(120);

        assert.strictEqual(reloads.length, 0, 'a live link must never be reloaded');
    } finally {
        restore();
        page.teardown();
    }
});

test('回前台死链兜底：静默＋观察窗零帧＋对话 0 行 → 重载一次', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        // 配对要先成立：回前台判定在"链路未就绪"时会提前返回（那时该由页面自己重连）。
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        // 体征要能读出"在对话视图里但 0 行"：这正是"重订阅没 ack"的形状。
        const timeline = new FakeElement('div');
        timeline.setAttribute('data-row-count', '0');
        page.document.querySelector = (selector) => {
            if (selector === '[data-mobile-page="chat"]') {
                return page.document.body;
            }
            if (selector === '[data-v4-timeline-scroll]') {
                return timeline;
            }
            return null;
        };

        // 真机没法为这一条去后台待 7 分钟，所以用诊断指令伪造"观测静默 120s"。
        assert.strictEqual(globalThis.__zcodeShellDiag('deadlink_test'), true);
        await wait(5400);

        assert.strictEqual(reloads.length, 1, 'a dead link with an empty conversation must be rebuilt');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('回前台兜底：静默')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('credentials never reach the diagnostic log', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.send(JSON.stringify({
            type: 'auth_init',
            role: 'terminal',
            device_sid: 'SECRET-DEVICE-SID',
            meta: {version: '1.0'}
        }));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);
        assert.ok(!allText(page.posts).includes('SECRET-DEVICE-SID'),
            'device_sid is a credential and must not be logged');
        assert.strictEqual(findPost(page.posts, 'ready').length, 1);
    } finally {
        page.teardown();
    }
});

test('our own injected frames are not re-observed as page traffic', async () => {
    // 这条测试原来用"壳自己发的 workspace-list-request"当"我们自己的帧"——
    // 那是 D7 主动开桥链的第一步，2026-09-17 已删。现在壳唯一会写页面 socket 的
    // 帧是后台补心跳的探针（前台一帧都不写，见"只读壳：前台心跳一帧都不写页面 socket"），
    // 所以改用它：探针发出去之后不许再有任何连带写入——一旦观察层把"我们自己的帧"
    // 当成页面流量回灌，客户端就会自激（这正是当年那条 feedback loop 的形状）。
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, '后台补心跳要真的发出这一帧');
        await wait(250);
        const ours = socket.sent.slice(before)
            .map((raw) => {
                try {
                    return JSON.parse(raw);
                } catch (e) {
                    return null;
                }
            })
            .filter((frame) => frame && frame.type === 'pair_status_query');
        assert.strictEqual(ours.length, 1, 'exactly one probe frame of our own');
        assert.strictEqual(socket.sent.length, before + 1,
            'no feedback loop: our own frame must not make the shell write again');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// end-to-end through the page socket
// ---------------------------------------------------------------------------

test('只读壳：默认配置下配对之后注入层不主动开桥（D7 已删，2026-09-17）', async () => {
    // 这条测试原来是 "active subscription works end to end through the page socket"：
    // 它钉住的是 D7「订阅所有工作区」打开时壳会**在页面那条 socket 上**给每个工作区
    // 开桥 + 订索引。那条链已在 2026-09-17 删除（与页面自己的订阅争用 → 页面卡
    // "工作中"+转圈），等价断言因此反过来：**默认配置也必须和"关"完全一样**——
    // 配对之后零协议写入、零订阅，只跟随页面（跟随的正面证据见下一条 passive 测试）。
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);
        // 旧实现（D7 打开时）就是拿这份工作区列表去开桥的；留着它是为了证明
        // "网还张在那里，只是没人来撞"——fake desktop 只在收到 workspace-list-request
        // 时才会读它，所以它本身不会诱发任何流量。
        link.desktop.workspaces = [{workspacePath: '/repo/a', workspaceIdentity: 'ws-a'}];

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        // 等过旧实现的起步窗（当年的 ACTIVE_START_DELAY_MS = 1.5s，已随 D7 删除）：
        // 这不是在等某个现存常量，而是给"万一有人把主动开桥调度器加回来"留出它当年
        // 最早会动手的那一刻——否则这条断言在时间上就没有对手。
        await wait(1900);

        assert.strictEqual(link.desktop.subscriptions.length, 0,
            '壳永不给自己开桥：默认配置与旧「订阅所有工作区＝关」必须完全一样');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-list-request')).length, 0,
            '配对之后不得再有 workspace-list-request（主动开桥链的第一步）');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-bridge-open')).length, 0,
            '不得有任何开桥请求');
        link.stop();
    } finally {
        page.teardown();
    }
});

test('passive mode follows the page stream without writing anything itself', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(600);

        // The page's own bridge: a ready notification, its event listen, then
        // the sessions-index event. None of this is ours.
        const pageBridge = 'page-bridge-1';
        socket.receive({type: 'data', payload: {
            zcode_type: 'workspace-bridge-ready',
            bridgeSessionId: pageBridge,
            bridge: {bridgeSessionId: pageBridge, workspaceKey: 'ws-page'}
        }});
        const listenBody = encodeBody(
            [page.protocol.REQ_EVENT_LISTEN, 5, 'zcode-agent', page.protocol.EVENT_SESSIONS_INDEX],
            {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'});
        for (const payload of fragment(listenBody, pageBridge, 2)) {
            socket.send(JSON.stringify({type: 'data', payload}));
        }
        const fireBody = encodeBody([page.protocol.RES_EVENT_FIRE, 5], snapshotWire([
            {sessionId: 'sp', title: '页面里的任务', phase: 'prewarming', lastActivityAt: 3}
        ], 1, 'sessions-index/ws-page'));
        for (const payload of fragment(fireBody, pageBridge, 3)) {
            socket.receive({type: 'data', payload});
        }
        await wait(60);

        const updates = findPost(page.posts, 'sessions');
        assert.strictEqual(updates.length, 1);
        assert.strictEqual(updates[0].data.key, 'ws-page');
        assert.strictEqual(updates[0].data.source, 'passive');
        assert.strictEqual(updates[0].data.sessions[0].title, '页面里的任务');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-list-request')).length, 0,
            '只读壳必须零协议写入（D7 删除后这也包括默认配置）');
        link.stop();
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// task locator
// ---------------------------------------------------------------------------

test('the locator clicks the element carrying the task title', async () => {
    const page = setupPage();
    try {
        const row = new FakeElement('div');
        row.cursor = 'pointer';
        const label = new FakeElement('span');
        label.textContent = '重构登录模块';
        row.appendChild(label);
        page.document.body.appendChild(row);

        globalThis.__zcodeShellLocateTask('s1', '重构登录模块');
        await wait(400);
        assert.ok(row.dispatched.includes('click'),
            'the row must be clicked; posts=' + allText(page.posts) + ' dispatched=' + JSON.stringify(row.dispatched));
        assert.strictEqual(
            findPost(page.posts, 'diag', (data) => data.message.startsWith('已定位任务')).length, 1);
    } finally {
        page.teardown();
    }
});

test('the locator gives up quietly when the title is gone', async () => {
    const page = setupPage();
    try {
        const el = new FakeElement('span');
        el.textContent = '完全不同的内容';
        page.document.body.appendChild(el);

        globalThis.__zcodeShellLocateTask('s1', '找不到的任务标题');
        await wait(3600);
        assert.strictEqual(findPost(page.posts, 'sessions').length, 0);
        assert.strictEqual(
            findPost(page.posts, 'diag', (data) => data.level === 'error').length, 0,
            'a locator miss is not an error');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// background-survival instrument
//
// The migration doc's step 4 (§8) is "background the app for 30 minutes and
// confirm frames are still arriving" — the milestone that decides whether the
// whole thin-shell route stands. It cannot be run in CI, so the counters that
// make it decidable are pinned here instead: if `pairAcks` stopped counting, the
// survival verdict the app reports would be meaningless.
// ---------------------------------------------------------------------------

test('liveness counters report inbound frames, pairing acks and socket churn', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // The desktop answers the client's heartbeat; that ack is the proof of a
        // live link even when no task happens to be running.
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellReportLiveness();
        await flush();

        const reports = findPost(page.posts, 'liveness');
        assert.ok(reports.length >= 1, 'a liveness report must be posted');
        const last = reports[reports.length - 1].data;
        assert.ok(last.inboundFrames >= 2, 'inbound frames must be counted');
        assert.ok(last.pairAcks >= 2, 'pair_status_ack frames must be counted separately');
        assert.strictEqual(last.socketsOpened, 1);
        assert.strictEqual(last.socketsClosed, 0);
        assert.strictEqual(last.paired, true);
        assert.strictEqual(last.deviceSidKnown, true);
        assert.strictEqual(last.socketState, 1, 'the socket must report itself OPEN');
        assert.ok(last.lastInboundAgoMs >= 0, 'the age of the last inbound frame must be reported');

        // After a close the app can distinguish "link died" from "nothing happened".
        socket.close();
        globalThis.__zcodeShellReportLiveness();
        await flush();
        const afterClose = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(afterClose.socketsClosed, 1);
        assert.strictEqual(afterClose.paired, false);
    } finally {
        page.teardown();
    }
});

test('the perf line carries the link counters that judge background reconnects', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // One pump-driven tick while backgrounded. Both numbers have to end up on
        // the line: the probe is what should keep the page's own ack watchdog
        // quiet, and the ack is what proves the desktop still answers — a field
        // log without these counters could not tell "we stopped probing" from
        // "the page reconnected anyway".
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellHeartbeat();
        // The counters are reported BEFORE the tick sends its probe, so the window
        // that contains both a probe and an ack is the next one. Waiting past the
        // rate gate (5s) is what lets a second tick through at all.
        await wait(5100);
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellHeartbeat();
        await flush();

        const lines = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('页面开销') === 0);
        assert.ok(lines.length >= 2, 'a perf line per tick, even when only the link was active');
        const line = lines.filter(
            (m) => m.includes('链路 ack 1') && m.includes('探针 1')
        )[0];
        assert.ok(line, 'one window must show both the probe and the ack: ' + lines.join(' | '));
        assert.ok(line.includes('paired true'), line);
        assert.ok(line.includes('socket 1'), line);
        assert.ok(line.includes('页面心跳 '), line);
    } finally {
        page.teardown();
    }
});

test('the page-heartbeat counter sees the page and not our own probe', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // The page's own heartbeat, then ours from the native pump. Ours is sent
        // while `injecting` is set, so it must not inflate the page's count — the
        // whole point is to tell whether the PAGE's timer is still running while
        // hidden.
        socket.send(JSON.stringify({type: 'pair_status_query', device_sid: 'sid-1', client_ts: 1}));
        globalThis.__zcodeShellHeartbeat();
        await flush();

        const line = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('页面开销') === 0)
            .pop();
        assert.ok(line.includes('页面心跳 1'), line);
        assert.ok(line.includes('探针 0'), 'our probe is counted only after the report: ' + line);
    } finally {
        page.teardown();
    }
});

test('liveness reporting is safe before any socket exists', () => {
    const page = setupPage();
    try {
        globalThis.__zcodeShellReportLiveness();
        const last = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(last.socketState, -1);
        assert.strictEqual(last.lastInboundAgoMs, -1);
        assert.strictEqual(last.paired, false);
    } finally {
        page.teardown();
    }
});

// The foreground service drives this while the app is backgrounded, because the
// page's own setInterval is suspended there (measured on device: zero ticks in
// 10+ minute background windows). Two drivers now exist, so the gap guard — not
// the interval — is what keeps the heartbeat from being doubled.
test('the native pump can drive a heartbeat, and the tick is rate limited', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, 'the first pumped tick must be accepted');
        const queries = socket.sent.slice(before)
            .map((raw) => {
                try {
                    return JSON.parse(raw);
                } catch (e) {
                    return null;
                }
            })
            // 控制帧是**顶层 `type`**（页面自己的客户端就是这么发的——本文件伪造页面
            // 心跳那行用的也是顶层形状）。这里原先按 `frame.payload.type` 计数，等于
            // 把壳的封装 bug 钉死在测试里：真机上那种帧从来没被服务端 ack 过。
            .filter((frame) => frame && frame.type === 'pair_status_query');
        assert.strictEqual(queries.length, 1, 'a pumped tick must put exactly one pair_status_query on the wire');
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), false, 'a tick inside the gap must be dropped');

        globalThis.__zcodeShellReportLiveness();
        await flush();
        const last = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(last.backgroundTicks, 1, 'the survival verdict reads this counter');
        assert.ok(
            last.backgroundFirstTickDelayMs >= 0,
            'the delay of the first background tick is what separates a real background heartbeat from a resume burst'
        );
    } finally {
        page.teardown();
    }
});

// 只读壳的核心承诺：**前台一个字节都不往页面的连接里写**。页面自己的 10s 心跳
// 在前台是准的（真机日志里 `页面心跳` 就是 ~1/10s），壳再插一帧只是往它正用着的
// 连接上加噪声；而"壳不碰页面链路"正是这一版政策的全部意义。
test('只读壳：前台心跳一帧都不写页面 socket', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        // 返回 true ＝ 这一跳被接受（没被 5s 间隔门吃掉），所以"没有新帧"这件事
        // 只可能是只读门挡下的，而不是限流挡下的。
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, 'the tick itself is accepted');
        assert.strictEqual(
            socket.sent.length, before,
            'a foreground tick must not write anything into the page socket'
        );
    } finally {
        page.teardown();
    }
});

// The resume posture (borrowed from the reference client): the decision is made
// on the frames that were still arriving, not on the ack clock. Closing the
// socket is the heaviest thing this layer can do — the page's own recovery is
// what follows it, and that costs a full re-open of workspaces and tasks — so it
// must never happen to a link that is demonstrably alive.
test('只读壳：回前台对活的链路连一帧都不写（更不许拆）', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        await flush();

        assert.strictEqual(socket.readyState, FakeWebSocket.OPEN, 'a live link must not be torn down');
        assert.strictEqual(
            socket.sent.length, before,
            'the resume must not write into the live page socket either'
        );
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳不介入')).length === 1);
    } finally {
        page.teardown();
    }
});

test('a throwing DOM query is contained, never propagated', async () => {
    const page = setupPage();
    try {
        page.document.querySelectorAll = () => {
            throw new Error('detached');
        };
        globalThis.__zcodeShellLocateTask('s1', 'x');
        await wait(400);
        // Matched on the message, not just the level: the assertion is about the
        // locator containing its own failure, and counting every warning made it
        // fail the moment an unrelated one was added.
        const warned = findPost(page.posts, 'diag', (data) =>
            data.level === 'warn' && String(data.message).indexOf('定位脚本出错') === 0);
        assert.strictEqual(warned.length, 1, 'the failure must be downgraded to a warning');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// page visual state -> native status bar surface
//
// The strip behind the status bar is painted natively from a fixed table, so the
// only thing this layer owes it is the state *name* (and the theme the page
// resolved), kept in step with the DOM as the page boots, navigates, rotates and
// switches theme.
// ---------------------------------------------------------------------------

/** The `pagestate` reports the shell received, in order. */
function pageStates(posts) {
    return findPost(posts, 'pagestate').map((p) => p.data.state + '/' + p.data.theme);
}

test('the page reports its visual state, and only when it changes', async () => {
    const page = setupPage({pageState: true, media: {'(max-width: 767px)': true}});
    try {
        // While the boot shell is up there is no control view: 'boot'.
        page.dom.set('zcode-boot-loading', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['boot/light'],
            'the boot shell is the first state');

        // React mounts: the boot shell goes away and the control view appears.
        page.dom.set('zcode-boot-loading', false);
        page.dom.set('bg-background-win-alt', true);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['boot/light', 'main-header/light'],
            'a narrow page puts its own title bar under the status bar');

        // A second mutation with nothing changed must not report again.
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 2, 'unchanged state is not re-reported');

        // The page's theme is the page's own decision, not the system's.
        page.dom.setTheme('dark');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-header/dark']);
    } finally {
        page.teardown();
    }
});

test('crossing the page\'s breakpoint without a DOM change is still reported', async () => {
    // Rotation, split screen and foldables change the layout without mutating the
    // DOM, so the media query has to be watched in its own right.
    const page = setupPage({pageState: true, media: {'(max-width: 767px)': true}});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-header/light']);

        const narrow = page.mediaQueries.find((mql) => mql.query === '(max-width: 767px)');
        assert.ok(narrow, 'the reporter must watch the page\'s own breakpoint');
        narrow.setMatches(false);
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-surface/light'],
            'wide layout means the shell area is the top surface');
    } finally {
        page.teardown();
    }
});

test('the native side can ask for a fresh report after a background stint', async () => {
    const page = setupPage({pageState: true, media: {'(max-width: 767px)': true}});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 1);

        // A system theme switch while the app was away leaves the DOM untouched,
        // so nothing would have been reported: the forced read is the only way.
        page.dom.setTheme('light');
        globalThis.__zcodeShellReportPageState();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 2, 'a forced report bypasses the dedupe');
    } finally {
        page.teardown();
    }
});

test('a page without MutationObserver still boots, and still follows its breakpoint', async () => {
    // Degradation, not failure: without MutationObserver the DOM is no longer
    // re-checked, but the initial read and the media-query watchers still work —
    // so the bar is correct for the layout the page boots into and keeps up with
    // rotation and a system theme switch.
    const page = setupPage({pageState: true, media: {'(max-width: 767px)': true}, noObserver: true});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.setTheme('light');
        await flush();
        assert.deepStrictEqual(
            pageStates(page.posts), ['main-header/light'],
            'the state is read once even without an observer');
        const narrow = page.mediaQueries.find((mql) => mql.query === '(max-width: 767px)');
        narrow.setMatches(false);
        await flush();
        assert.deepStrictEqual(
            pageStates(page.posts).slice(-1), ['main-surface/light'],
            'rotation is still followed');
        assert.ok(
            findPost(page.posts, 'diag', (data) => String(data.message).indexOf('缺少 MutationObserver') >= 0,).length === 1,
            'and the degradation is stated once');
        assert.ok(findPost(page.posts, 'ready').length === 1, 'the rest of the layer still comes up');
    } finally {
        page.teardown();
    }
});

test('a document-start arrival before <html> exists retries instead of giving up', async () => {
    // The race installScrollbarWidth already guards against. Giving up here would
    // be silent and permanent for that document: no status-bar colour at all.
    const page = setupPage({
        pageState: true,
        noDocumentElement: true,
        media: {'(max-width: 767px)': true},
    });
    try {
        assert.deepStrictEqual(pageStates(page.posts), [],
            'nothing can be read before the tree exists');
        // <html> arrives; the page fires DOMContentLoaded.
        page.dom.materialiseRoot();
        page.document.dispatchEvent({type: 'DOMContentLoaded'});
        page.dom.set('bg-background-win-alt', true);
        page.dom.setTheme('dark');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-header/dark'],
            'the reporter must install itself once the tree exists');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// 进对话卡住（§5b）——**只读壳口径：只记账，不刷新**
//
// 这一节的历史：2026-09-12 用户拍板过"进对话 3s 后查一次，状态 A（头部停在
// 新建任务）或状态 B（输入框灰）→ 直接刷新"的粗暴版；2026-09-15 只读壳定案后
// **刷新这条路被废掉**（壳永不自动重载页面），下面几条测试断言的是现在的口径：
// 判定照做、状态照记（`__zcodeShellFallback` 的 stall/ready 记账），但
// `reloads` 恒为 0，终止性不再靠"15s 最小刷新间隔"。
// ---------------------------------------------------------------------------

const FB = () => globalThis.__zcodeShellFallback;

function appendHeader(page, titleText) {
    const title = new FakeElement('div');
    title.textContent = titleText;
    page.document.body.appendChild(title);
}

function appendComposer(page, placeholder, disabled) {
    const composer = new FakeElement('textarea');
    if (placeholder !== null) {
        composer.setAttribute('placeholder', placeholder);
    }
    if (disabled) {
        composer.setAttribute('disabled', '');
    }
    page.document.body.appendChild(composer);
}

function stubReload() {
    const reloads = [];
    const previous = globalThis.location;
    globalThis.location = {href: 'https://zcode.z.ai/remote/v4', reload: () => reloads.push(1)};
    return {reloads, restore: () => { globalThis.location = previous; }};
}

function stubSessionStorage() {
    const store = new Map();
    const previous = globalThis.sessionStorage;
    globalThis.sessionStorage = {
        getItem: (k) => (store.has(k) ? store.get(k) : null),
        setItem: (k, v) => store.set(k, String(v)),
        removeItem: (k) => store.delete(k)
    };
    return {store, restore: () => { globalThis.sessionStorage = previous; }};
}

test('进对话铁判准：信标后页面桥有入站帧 → 判就绪不刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, '继续输入以排队后续修改', false);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_hot'}});
        assert.ok(FB().timer() !== null, 'the beacon arms the 5s deadline');
        // 信标之后桌面端回了真实对话动态事件——内容在路上。
        const conversation = encodeBody([page.protocol.RES_EVENT_FIRE, 77], {
            topic: 'conversation/ws',
            subscriptionId: 'sub-hot',
            kind: 'complete',
            frame: {payload: {kind: 'snapshot', snapshot: {rows: {window: []}}}}
        });
        for (const payload of fragment(conversation, 'page-bridge-13', 1)) {
            socket.receive({type: 'data', payload});
        }
        FB().check();
        assert.strictEqual(reloads.length, 0, 'conversation traffic must not reload');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('对话流有新帧')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面自述 store 已连上 → 不刷（新任务也不会被误刷）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '上午好呀，有什么想让我帮忙的吗');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_new'}});
        // 空会话没有行，但页面自己的 store 一样会连上——这就是"就绪"的权威信号。
        FB().ready('v4.conversation.store.connect.completed');
        FB().check();
        assert.strictEqual(reloads.length, 0,
            'a brand-new empty task reaches store.connect.completed and must not be reloaded');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('已就绪（v4.conversation.store.connect.completed）')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面日志的订阅确认也算就绪', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_p'}});
        FB().ready('v4.conversation.subscribe.acknowledged');
        FB().check();
        assert.strictEqual(reloads.length, 0, 'an acknowledged subscription is readiness');
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：进对话 5s 无详情只记账，不轻推也不刷新', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_a'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the first deadline must not reload anything');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('先尝试最小内推')).length === 1);
        // 真机定罪的那条链（轻推←fallbackCheck，40 秒里拆了 10 次）在只读壳下
        // 只剩这一行日志：说明"我们本来会在这一刻动手"。
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不轻推页面 socket')).length === 1);
        clearTimeout(FB().timer());
        FB().check();
        assert.strictEqual(reloads.length, 0, 'a read-only shell never reloads the page');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不因')).length === 1);
        assert.strictEqual(FB().stall().gaveUp, true, 'the deadline stops instead of escalating');
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：状态 B（输入框未就绪）同样只记账不刷新', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, null, true);
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_b'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'state B must not reload either');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('输入框未就绪')).length === 1);
        clearTimeout(FB().timer());
        FB().check();
        assert.strictEqual(reloads.length, 0, 'state B also stays read-only');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不因')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：进对话卡住不会重载，也不会连刷；就绪信号照样复位', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the first deadline nudges nothing');
        assert.ok(findPost(page.posts, 'diag', (d) => d.message.includes('先尝试最小内推')).length === 1);
        clearTimeout(FB().timer());

        // 第二次判定：旧版在这里进"整体刷新保底"，只读壳到此为止。
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the read-only shell never escalates to a reload');

        // 无论判定多少次，页面都不会被壳重载。
        FB().stall().lastReloadAt = Date.now() - 16000;
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'no ladder, no reload — ever');

        // 就绪信号到达 → 复位，状态干净，下一轮照旧能判。
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        FB().ready('v4.conversation.store.connect.completed');
        FB().check();
        assert.strictEqual(FB().stall().gaveUp, false, 'readiness resets the give-up state');
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：链路换代那一拍不判（页面正按自己的梯子重连）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, '继续输入以排队后续修改', false);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_e'}});
        // 链路重建：pair 状态翻负 → resetClient → client 为 null（新客户端尚未出生）
        socket.receive({type: 'pair_status_ack', pair_status: 'unmatched'});
        FB().check();
        assert.strictEqual(reloads.length, 0,
            'a rebuilt link must not be reloaded mid-recovery');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('期间链路换代')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：窗口内的重复信标不顺延（页面重试不会把 5s 拖长）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        const first = FB().state().beaconAt;
        // 卡住时页面每 ~10s 重发一次同样的请求；真机实测这些重试把判定一路
        // 顺延到 ~9s 才刷，所以窗口起点必须钉在第一次进对话那一刻。
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_1'}});
        assert.strictEqual(FB().state().beaconAt, first,
            'a retry must not slide the deadline');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('落在此前已武装的 5s 窗内')).length === 1);
        assert.strictEqual(reloads.length, 0, 'no reload before the deadline');
    } finally {
        if (FB().timer()) {
            clearTimeout(FB().timer());
        }
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面真实发出的信标会武装 5s 判定', () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const body = encodeBody(
            [page.protocol.REQ_PROMISE, 11, 'zcode-agent', 'conversationRowsRangeV4'],
            {sessionId: 'sess_wire'}
        );
        for (const payload of fragment(body, 'page-bridge-13', 1)) {
            socket.send(JSON.stringify({type: 'data', payload}));
        }
        assert.ok(FB().timer() !== null,
            'the observed rows-range beacon must arm the 5s deadline');
        // 武装出来的判定器必须清掉：否则 5s 后它会落到后续测试的 location 上刷新
        // （整跑时表现为"别的用例多出一次 reload"）。
        clearTimeout(FB().timer());
    } finally {
        page.teardown();
    }
});

test('KICKED 自愈：后台被顶掉 → 回前台 1.5s 后自己重载回来', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellSetAppForeground(false);
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('应用在后台')).length === 1,
            'a background kick must be recorded');
        globalThis.__zcodeShellSetAppForeground(true);
        assert.strictEqual(reloads.length, 0,
            'the reload is deferred so the native side can release the slot first');
        await wait(1700);
        assert.strictEqual(reloads.length, 1,
            'the kicked page must reload itself back onto a live link');
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED：前台被顶掉不自动重载（可能与另一台控制端在抢）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        await wait(1700);
        assert.strictEqual(reloads.length, 0, 'a foreground kick is not ours to fix');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('前台）：不自动干预')).length === 1);
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 自愈：跨 reload 连续两次后停止（避免死循环）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        storage.store.set('zcodeShellKickedHeals', '2');
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellSetAppForeground(false);
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        globalThis.__zcodeShellSetAppForeground(true);
        await wait(1700);
        assert.strictEqual(reloads.length, 0, 'the heal cap stops the reload loop; diag=' +
            JSON.stringify(findPost(page.posts, 'diag').map((p) => p.data.message)));
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('停止自动重载')).length === 1,
            'diag: ' + JSON.stringify(findPost(page.posts, 'diag').map((p) => p.data.message)));
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 自愈：配对成功即清零计数', () => {
    const page = setupPage();
    const storage = stubSessionStorage();
    try {
        storage.store.set('zcodeShellKickedHeals', '1');
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        assert.strictEqual(storage.store.get('zcodeShellKickedHeals'), '0',
            'a matched pair resets the heal counter');
    } finally {
        storage.restore();
        page.teardown();
    }
});

test('hook 安全：晚注入经原型层收编页面已有 socket（零重连）', () => {
    const page = setupPage();
    try {
        // 绕过构造包装直建实例 = 模拟「hook 装上之前页面已建好的连接」
        const orphan = new FakeWebSocket('wss://relay.example');
        // 它的下一次 send 走原型补丁 → 当场收编
        orphan.send(JSON.stringify({type: 'data', payload: {zcode_type: 'x'}}));
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('已从原型层收编现有 socket')).length === 1,
            'adoption must be logged');
        // 收编后：入站帧恢复观测（配对 ack 让 relayPaired 翻真）
        orphan.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellHeartbeat();
        assert.ok(findPost(page.posts, 'liveness', (d) => d.paired === true).length === 1,
            'the adopted socket must deliver inbound frames to the shell');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// 后台原生承载（2026-09-16）：交还兜底与"仅取证"的推动函数
//
// 生产逻辑里"谁来接住连接"是原生侧（ShellRuntime.maybeStartNativeCarrier），
// 注入层只留两件：① 交还后极窄的兜底（页面掉进失败态才重载一次）；
// ② 取证用的推动（合成 online / 关 socket）——真机已证它救不了后台，别再拿它当方案。
// ---------------------------------------------------------------------------

/** 给合成事件测试装上最小 Event / dispatchEvent（注入层里 G === globalThis）。 */
function stubSyntheticEvents() {
    const dispatched = [];
    const previousEvent = globalThis.Event;
    const previousDispatch = globalThis.dispatchEvent;
    const previousWindowDispatch = globalThis.window && globalThis.window.dispatchEvent;
    globalThis.Event = function (type) { return {type}; };
    globalThis.dispatchEvent = (ev) => { dispatched.push(ev && ev.type); return true; };
    if (globalThis.window) {
        globalThis.window.dispatchEvent = globalThis.dispatchEvent;
    }
    return {
        dispatched,
        restore: () => {
            globalThis.Event = previousEvent;
            globalThis.dispatchEvent = previousDispatch;
            if (globalThis.window) {
                globalThis.window.dispatchEvent = previousWindowDispatch;
            }
        }
    };
}

test('后台承载：交还兜底——页面已自己开线就什么都不做（不重载）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        assert.strictEqual(globalThis.__zcodeShellAfterCarrierReturn(50), true);
        await wait(150);
        assert.strictEqual(reloads.length, 0, '有一条 OPEN 的 socket 时绝不许重载');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.includes('交还后页面已自行恢复')).length,
            1,
            '恢复与否必须留一行判据');
    } finally {
        restore();
        page.teardown();
    }
});

test('后台承载：交还兜底——零 OPEN socket 且零入站帧 → 重载一次', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        // 不建任何 socket：等价于页面已经 dispose（真机 00:12:57 的形状 socket=-1 paired=false）
        assert.strictEqual(globalThis.__zcodeShellAfterCarrierReturn(50), true);
        await wait(150);
        assert.strictEqual(reloads.length, 1, '失败态自己回不来，兜底必须重载一次');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.includes('交还兜底')).length,
            1);
    } finally {
        restore();
        page.teardown();
    }
});

test('后台承载：恢复推动 event 档只派发合成 online，并回报推动前现场', async () => {
    const page = setupPage();
    const events = stubSyntheticEvents();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        const before = globalThis.__zcodeShellNudgeRecover('event');
        assert.deepStrictEqual(events.dispatched, ['online'], '只派发一个 online，不要 pageshow');
        assert.ok(String(before).indexOf('socket=1') >= 0,
            '返回值是推动前的链路现场（原生日志与它对齐看时序）');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.indexOf('恢复推动 event') === 0).length,
            1);
    } finally {
        events.restore();
        page.teardown();
    }
});

test('后台承载：恢复推动 close 档关掉页面那条 socket（仅取证）', () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        const before = globalThis.__zcodeShellNudgeRecover('close');
        assert.ok(String(before).indexOf('socket=1') >= 0, '返回值是关线前的现场');
        assert.strictEqual(socket.readyState, FakeWebSocket.CLOSED,
            'close 档必须真的关掉它（生产逻辑不用，仅取证）');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.indexOf('恢复推动 close') === 0).length,
            1);
    } finally {
        page.teardown();
    }
});


