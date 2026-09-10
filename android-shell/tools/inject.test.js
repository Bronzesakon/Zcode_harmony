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
    };

    return {document, window, posts, protocol, configValue, teardown};
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

test('a throwing DOM query is contained, never propagated', async () => {
    const page = setupPage();
    try {
        page.document.querySelectorAll = () => {
            throw new Error('detached');
        };
        globalThis.__zcodeShellLocateTask('s1', 'x');
        await wait(400);
        assert.strictEqual(
            findPost(page.posts, 'diag', (data) => data.level === 'warn').length, 1,
            'the failure must be downgraded to a warning');
    } finally {
        page.teardown();
    }
});
