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
 * Covered: idempotency, the visibility spoof, hook transparency (statics,
 * instanceof, no feedback loop), the full active-subscribe handshake over the
 * socket, passive reading of the page's own stream, the title-matching task
 * locator, and the promise that credentials never reach the diagnostic log.
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
    const configValue = {subscribeAll: !options || options.subscribeAll !== false};

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
        delete globalThis.__zcodeShellSetSubscribeAll;
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

test('the page is told it is visible and its lifecycle listeners are suppressed', () => {
    const page = setupPage();
    try {
        assert.strictEqual(globalThis.document.hidden, false);
        assert.strictEqual(globalThis.document.visibilityState, 'visible');
        assert.strictEqual(globalThis.document.hasFocus(), true);

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
        assert.strictEqual(fired, 0, 'lifecycle events must not reach page listeners');

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

test('assigning document.onvisibilitychange does not crash the page', () => {
    const page = setupPage();
    try {
        globalThis.document.onvisibilitychange = () => {
            throw new Error('must never run');
        };
        assert.strictEqual(globalThis.document.onvisibilitychange, null);
    } finally {
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
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);
        const countRequests = () =>
            socket.sent.filter((text) => text.includes('workspace-list-request')).length;
        assert.strictEqual(countRequests(), 1, 'exactly one workspace list request');
        // A feedback loop (our own send observed as page traffic) would keep
        // the client re-triggering itself; the count must stay put.
        await wait(250);
        assert.strictEqual(countRequests(), 1);
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// end-to-end through the page socket
// ---------------------------------------------------------------------------

test('active subscription works end to end through the page socket', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);
        link.desktop.workspaces = [{workspacePath: '/repo/a', workspaceIdentity: 'ws-a'}];

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1900);

        assert.ok(link.sentLog.some((text) => text.includes('workspace-list-request')),
            'the client should ask for the workspace list once paired');
        assert.strictEqual(link.desktop.subscriptions.length, 1,
            'the workspace should be subscribed');

        link.desktop.pushSessionsWire('ws-a', snapshotWire([
            {sessionId: 's1', title: '重构登录模块', phase: 'running', lastActivityAt: 1,
                lastAssistantPreview: '已修改 auth_service'}
        ]));
        await wait(60);

        const updates = findPost(page.posts, 'sessions');
        assert.strictEqual(updates.length, 1, 'one sessions update should reach the bridge');
        assert.strictEqual(updates[0].data.key, 'ws-a');
        assert.strictEqual(updates[0].data.source, 'active');
        assert.strictEqual(updates[0].data.sessions[0].title, '重构登录模块');
        assert.strictEqual(updates[0].data.sessions[0].phase, 'running');
        assert.ok(link.desktop.acked.length > 0, 'the desktop must be acked');
        link.stop();
    } finally {
        page.teardown();
    }
});

test('passive mode follows the page stream without writing anything itself', async () => {
    const page = setupPage({subscribeAll: false});
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
            'subscribe-all off must mean zero protocol writes');
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

        assert.strictEqual(
            typeof globalThis.__zcodeShellHeartbeat, 'function',
            'the native pump needs an entry point inside the page'
        );

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
            .filter((frame) => frame && frame.payload && frame.payload.type === 'pair_status_query');
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

// The resume posture (borrowed from the reference client): the decision is made
// on the frames that were still arriving, not on the ack clock. Closing the
// socket is the heaviest thing this layer can do — the page's own recovery is
// what follows it, and that costs a full re-open of workspaces and tasks — so it
// must never happen to a link that is demonstrably alive.
test('a foreground return with frames still arriving leaves the socket alone', async () => {
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
        const probes = socket.sent.slice(before)
            .map((raw) => {
                try {
                    return JSON.parse(raw);
                } catch (e) {
                    return null;
                }
            })
            .filter((frame) => frame && frame.payload && frame.payload.type === 'pair_status_query');
        assert.strictEqual(probes.length, 1, 'the resume must probe the link once');
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
// 快速刷新（§5b，3s 双态直刷）
//
// 用户拍板的粗暴版：进对话 3s 后查一次——头部停在「新建任务」（状态 A）或
// 输入框灰/禁用（状态 B：有标题但内容没加载时页面自己的信号）→ 直接刷新。
// 除「15s 最小刷新间隔」（防死循环的终止性保证）外无任何守卫。
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

test('快速刷新：热打开（真标题 + 输入框可用）不刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, '继续输入以排队后续修改', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_hot'}});
        assert.ok(FB().timer() !== null, 'the beacon arms the 3s check');
        FB().check();
        assert.strictEqual(reloads.length, 0, 'a healthy open never reloads');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('3s 检查：标题与输入框均就绪')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('快速刷新：状态 A（头部回退「新建任务」）直刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_a'}});
        FB().check();
        assert.strictEqual(reloads.length, 1, 'state A reloads at once');
        assert.ok(FB().state().lastReloadAt > 0, 'the gap stamp is taken');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('进对话 3s 未就绪（标题回退）')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('快速刷新：状态 B（真标题但输入框灰/禁用）直刷，占位符为空也识别', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, null, true);
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_b'}});
        FB().check();
        assert.strictEqual(reloads.length, 1, 'a greyed composer reloads');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('进对话 3s 未就绪（输入框未就绪）')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('快速刷新：问候屏（无头 + 输入框可用）不刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '上午好呀，有什么想让我帮忙的吗');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_new'}});
        FB().check();
        assert.strictEqual(reloads.length, 0,
            'the genuine new-task greeting screen must not be reloaded');
    } finally {
        restore();
        page.teardown();
    }
});

test('快速刷新：15s 间隔内顺延复查，到期才再刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 1, 'first hit reloads');

        // 刷新后的新页面里信标重新武装，仍旧卡着：间隔内 → 顺延而不是再刷
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 1, 'within the gap there must be no second reload');
        assert.ok(FB().timer() !== null, 'a deferred re-check must be scheduled');
        clearTimeout(FB().timer());

        // 间隔到期（把间隔戳拨回 16s 前）：再查 → 再刷
        FB().state().lastReloadAt = Date.now() - 16000;
        FB().check();
        assert.strictEqual(reloads.length, 2, 'after the gap a stuck page reloads again');
    } finally {
        restore();
        page.teardown();
    }
});

test('快速刷新：页面真实发出的信标会武装 3s 检查', () => {
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
            'the observed rows-range beacon must arm the 3s check');
    } finally {
        page.teardown();
    }
});

test('自愈：滚动条置零样式被移除后，心跳内重装并留痕', () => {
    const page = setupPage({
        pageState: true,
        media: {'(max-width: 767px)': true},
    });
    try {
        const html = page.document.documentElement;
        const styles = () => html.children.filter(
            (c) => c.getAttribute('data-zcode-shell') === 'scrollbar-width');
        assert.strictEqual(styles().length, 1, 'boot installs the zero-width style');
        styles()[0].isConnected = false;   // 模拟页面运行期把节点移除
        globalThis.__zcodeShellHeartbeat();
        assert.strictEqual(styles().length, 2, 'the heartbeat must reinstall it');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('样式丢失')).length === 1,
            'the self-heal must leave a warn line for forensics');
    } finally {
        page.teardown();
    }
});
