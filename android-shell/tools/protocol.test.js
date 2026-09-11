/**
 * Protocol layer tests — run with:  node --test tools/
 *
 * This is the only part of the project that can be verified without Android,
 * and it is also the most fragile part (it depends on someone else's deployed
 * protocol), so the tests deliberately pin the WIRE FORMAT rather than just
 * self-consistency:
 *
 *   * golden byte sequences hand-derived from the spec for the value codec
 *   * the standard CRC-32 check value
 *   * an independently written fragmenter, so the sender is not validated
 *     against its own output
 *   * a fake desktop that drives RemoteClient end to end (active + passive)
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const P = require('../app/src/main/assets/zcode-protocol.js');
const {DesktopCore, fragment, encodeBody, ascii, snapshotWire} = require('./fake-desktop.js');

const FRAGMENT_BYTES = 512 * 1024;

// `makeClient` wires a RemoteClient to the shared fake desktop through the
// direct seam: replies land in RemoteClient.acceptPayload (no WebSocket).
function makeClient(options) {
    const client = new P.RemoteClient({
        send: () => {},
        log: () => {},
        ...options
    });
    const desktop = new DesktopCore({deliver: (payload) => client.acceptPayload(payload)});
    client._send = (payload) => desktop.accepts(payload);
    return {client, desktop};
}

// ---------------------------------------------------------------------------
// value codec
// ---------------------------------------------------------------------------

test('value codec produces the exact documented byte layout', () => {
    const writer = new P.ByteWriter();
    P.encodeValue(writer, [102, 1048576, 'zcode-agent', 'onDynamicSessionsIndexFrame']);
    const got = Array.from(writer.toBytes());

    const expected = [
        0x04, 0x04,                     // array tag, 4 items
        0x06, 0x66,                     // int tag, 102
        0x06, 0x80, 0x80, 0x40,         // int tag, 1048576 as 7-bit LE varint
        0x01, 0x0B, ...ascii('zcode-agent'),
        0x01, 0x1B, ...ascii('onDynamicSessionsIndexFrame')
    ];
    assert.deepStrictEqual(got, expected);
});

test('value codec round-trips every tag', () => {
    const cases = [
        null,
        '',
        '中文与 emoji 🚀',
        new Uint8Array([0, 1, 255, 128]),
        [],
        [1, 'two', [3]],
        {},
        {workspacePath: '/tmp/a', workspaceIdentity: 'ws-1'},
        {nested: {list: [1, 2, {deep: true}]}},
        0,
        127,
        128,
        16383,
        16384,
        0x7FFFFFFF,
        true,
        false,
        -1,
        1.5
    ];
    for (const value of cases) {
        const writer = new P.ByteWriter();
        P.encodeValue(writer, value);
        const back = P.decodeValue(new P.ByteReader(writer.toBytes()));
        if (value instanceof Uint8Array) {
            assert.deepStrictEqual(Array.from(back), Array.from(value));
        } else {
            assert.deepStrictEqual(back, value, 'round-trip failed for ' + JSON.stringify(value));
        }
    }
});

test('non-int scalars travel as JSON (tag 5), matching the reference encoder', () => {
    for (const value of [true, -1, 1.5]) {
        const writer = new P.ByteWriter();
        P.encodeValue(writer, value);
        assert.strictEqual(writer.toBytes()[0], 0x05, 'tag for ' + JSON.stringify(value));
    }
});

test('varint rejects overflow instead of silently wrapping', () => {
    // Six continuation bytes exceed the 32-bit range the protocol allows.
    const bad = new Uint8Array([0x80, 0x80, 0x80, 0x80, 0xFF, 0x01]);
    assert.throws(() => new P.ByteReader(bad).varint(), /overflow/);
});

test('crc32 matches the standard check value', () => {
    assert.strictEqual(P.crc32Hex(ascii('123456789')), 'cbf43926');
    assert.strictEqual(P.crc32Hex(new Uint8Array(0)), '00000000');
});

// ---------------------------------------------------------------------------
// rpc-frame transport
// ---------------------------------------------------------------------------

test('fragmented messages survive a round trip, in order and out of order', () => {
    const bridgeSessionId = 'bridge-1';
    const original = new Uint8Array(FRAGMENT_BYTES * 2 + 12345);
    for (let i = 0; i < original.length; i++) {
        original[i] = (i * 31) & 0xff;
    }
    const payloads = fragment(original, bridgeSessionId, 7);
    assert.strictEqual(payloads.length, 3, 'expected three fragments');
    assert.strictEqual(payloads[1].dataBase64.length > 0, true);

    const received = [];
    const assembl = new P.RpcFrameAssembler({
        bridgeSessionId: bridgeSessionId,
        onAck: () => {},
        onMessage: (bytes) => received.push(bytes)
    });
    // Reverse order: reassembly must not depend on arrival order.
    for (const payload of [...payloads].reverse()) {
        assembl.acceptPayload(payload);
    }
    assert.strictEqual(received.length, 1);
    assert.deepStrictEqual(Array.from(received[0]), Array.from(original));
});

test('a corrupted fragment is dropped, not delivered', () => {
    const original = new Uint8Array([1, 2, 3, 4, 5]);
    const payloads = fragment(original, 'bridge-x', 1);
    payloads[0].dataBase64 = P.base64Encode(new Uint8Array([9, 9, 9, 9, 8]));

    let delivered = 0;
    const assembl = new P.RpcFrameAssembler({
        bridgeSessionId: 'bridge-x',
        onAck: () => {},
        onMessage: () => delivered++
    });
    for (const payload of payloads) {
        assembl.acceptPayload(payload);
    }
    assert.strictEqual(delivered, 0);
});

test('an assembler ignores frames addressed to other bridges', () => {
    const payloads = fragment(new Uint8Array([1, 2, 3]), 'bridge-other', 1);
    let delivered = 0;
    const assembl = new P.RpcFrameAssembler({
        bridgeSessionId: 'bridge-mine',
        onAck: () => {},
        onMessage: () => delivered++
    });
    for (const payload of payloads) {
        assert.strictEqual(assembl.acceptPayload(payload), false);
    }
    assert.strictEqual(delivered, 0);
});

test('the sender acknowledges exactly what the assembler receives', () => {
    const sent = [];
    const sender = new P.RpcFrameSender({
        bridgeSessionId: 'b1',
        sendPayload: (p) => sent.push(p)
    });
    const body = ascii('hello');
    sender.sendMessage(body);
    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].messageBytes, 5);
    assert.strictEqual(sent[0].checksum.value, P.crc32Hex(body));
    assert.strictEqual(sent[0].bridgeSessionId, 'b1');
});

// ---------------------------------------------------------------------------
// ChannelClient
// ---------------------------------------------------------------------------

function makeChannelPair() {
    // Client -> (collect bodies) ; and a handle to answer as the desktop.
    const outbound = [];
    const client = new P.ChannelClient({
        sendBody: (bytes) => outbound.push(bytes),
        idBase: 0x100000,
        onLog: () => {}
    });
    const desktop = {
        last() {
            const bytes = outbound[outbound.length - 1];
            const reader = new P.ByteReader(bytes);
            const header = P.decodeValue(reader);
            const args = reader.remaining > 0 ? P.decodeValue(reader) : null;
            return {header, args};
        },
        deliver(header, value) {
            client.handleMessage(encodeBody(header, value));
        }
    };
    return {client, desktop, outbound};
}

/** Requests are gated on a resolved promise, so the send lands one microtask later. */
const flush = () => new Promise((r) => setTimeout(r, 0));

test('channel calls resolve on a matching response and reject on an error', async () => {
    const {client, desktop} = makeChannelPair();
    desktop.deliver([P.RES_INITIALIZE]);

    const okPromise = client.call('zcode-agent', 'helloConversationV4', [], 5000);
    await flush();
    const {header} = desktop.last();
    assert.strictEqual(header[0], P.REQ_PROMISE);
    assert.strictEqual(header[2], 'zcode-agent');
    assert.strictEqual(header[3], 'helloConversationV4');
    assert.ok(header[1] >= 0x100000, 'request ids must start far above the page counter');
    desktop.deliver([201, header[1]], {connectionId: 'c1'});
    assert.deepStrictEqual(await okPromise, {connectionId: 'c1'});

    const badPromise = client.call('zcode-agent', 'nope', [], 5000);
    await flush();
    const second = desktop.last();
    desktop.deliver([202, second.header[1]], {message: 'boom'});
    await assert.rejects(badPromise, /boom/);
});

test('a call made before Initialize waits for it instead of failing', async () => {
    const {client, desktop, outbound} = makeChannelPair();
    const pending = client.call('zcode-agent', 'helloConversationV4', [], 5000);
    assert.strictEqual(outbound.length, 0, 'nothing may be sent before Initialize');
    desktop.deliver([P.RES_INITIALIZE]);
    await flush();
    assert.strictEqual(outbound.length, 1, 'the call flushes after Initialize');
    const header = desktop.last().header;
    desktop.deliver([201, header[1]], {ok: true});
    await pending;
});

test('event listeners receive fired events on their own id', async () => {
    const {client, desktop} = makeChannelPair();
    desktop.deliver([P.RES_INITIALIZE]);
    const seen = [];
    const listener = client.addEventListener('zcode-agent', P.EVENT_SESSIONS_INDEX,
        {workspacePath: '/w'}, (data) => seen.push(data));
    await flush();
    const listenHeader = desktop.last().header;
    assert.strictEqual(listenHeader[0], P.REQ_EVENT_LISTEN);
    assert.strictEqual(listenHeader[3], P.EVENT_SESSIONS_INDEX);

    desktop.deliver([P.RES_EVENT_FIRE, listenHeader[1]], {topic: 'sessions-index/w'});
    desktop.deliver([P.RES_EVENT_FIRE, listenHeader[1] + 99], {topic: 'other'});
    assert.strictEqual(seen.length, 1);
    assert.strictEqual(seen[0].topic, 'sessions-index/w');

    listener.dispose();
    const disposeHeader = desktop.last().header;
    assert.strictEqual(disposeHeader[0], 103);
});

// ---------------------------------------------------------------------------
// sessions-index state
// ---------------------------------------------------------------------------

test('snapshot then deltas build the task list, removals included', () => {
    const state = new P.SessionsIndexState();
    assert.strictEqual(state.applyWireFrame(snapshotWire([
        {sessionId: 's1', title: 'A', phase: 'running', lastActivityAt: 10,
            lastAssistantPreview: 'p1'},
        {sessionId: 's2', title: 'B', phase: 'completed', lastActivityAt: 20}
    ])), true);
    assert.strictEqual(state.seq, 1);
    assert.strictEqual(state.list().length, 2);
    assert.strictEqual(state.list()[0].sessionId, 's2', 'sorted by lastActivityAt desc');

    assert.strictEqual(state.applyLogicalFrame({
        fromSeq: 1, toSeq: 2,
        payload: {
            kind: 'deltas',
            deltas: [
                {op: 'session.upserted', session: {sessionId: 's1', title: 'A', phase: 'completed'}},
                {op: 'session.removed', sessionId: 's2'}
            ]
        }
    }), true);
    assert.deepStrictEqual(state.list().map((s) => s.sessionId), ['s1']);
    assert.strictEqual(state.list()[0].phase, 'completed');
});

test('a sequence gap flags a resync instead of applying stale deltas', () => {
    const state = new P.SessionsIndexState();
    state.applyWireFrame(snapshotWire([{sessionId: 's1', phase: 'running'}], 5));
    const applied = state.applyLogicalFrame({
        fromSeq: 4, toSeq: 6,
        payload: {kind: 'deltas', deltas: [{op: 'session.removed', sessionId: 's1'}]}
    });
    assert.strictEqual(applied, false);
    assert.strictEqual(state.needsResync, true);
    assert.strictEqual(state.list().length, 1, 'the delta must not be applied');
});

test('a large logical frame split into fragments is reassembled', () => {
    const state = new P.SessionsIndexState();
    const frame = {
        toSeq: 1,
        payload: {
            kind: 'snapshot',
            snapshot: {
                workspaceId: 'ws-a',
                logEpoch: 'e',
                sessions: [{sessionId: 's1', title: 'x'.repeat(200), phase: 'running'}]
            }
        }
    };
    const bytes = P.utf8Encode(JSON.stringify(frame));
    const parts = [bytes.subarray(0, 50), bytes.subarray(50)];
    assert.strictEqual(state.applyWireFrame({
        topic: 'sessions-index/ws-a',
        kind: 'fragment',
        logicalFrameId: 'lf-1',
        fragmentCount: parts.length,
        fragmentIndex: 0,
        dataBase64: P.base64Encode(parts[0])
    }), false, 'incomplete until every fragment arrives');
    assert.strictEqual(state.applyWireFrame({
        topic: 'sessions-index/ws-a',
        kind: 'fragment',
        logicalFrameId: 'lf-1',
        fragmentCount: parts.length,
        fragmentIndex: 1,
        dataBase64: P.base64Encode(parts[1])
    }), true);
    assert.strictEqual(state.list().length, 1);
});

test('pendingInteraction id is surfaced for the attention notification', () => {
    const entry = P.normalizeSession({
        sessionId: 's1',
        phase: 'running',
        pendingInteraction: {interactionId: 42}
    });
    assert.strictEqual(entry.pendingInteractionId, '42');
    assert.strictEqual(P.normalizeSession({sessionId: 's2'}).pendingInteractionId, '');
});

// ---------------------------------------------------------------------------
// RemoteClient — active coverage
// ---------------------------------------------------------------------------

test('active mode opens one bridge per workspace and streams sessions', async () => {
    const {client, desktop} = makeClient();
    desktop.workspaces = [
        {workspacePath: '/repo/a', workspaceIdentity: 'ws-a'},
        {workspacePath: '/repo/b', workspaceIdentity: 'ws-b'}
    ];
    const updates = [];
    client.onSessions = (update) => updates.push(update);

    await client.start();
    assert.strictEqual(desktop.subscriptions.length, 2, 'one subscription per workspace');

    desktop.pushSessionsWire('ws-a', snapshotWire([
        {sessionId: 's1', title: '重构登录', phase: 'running', lastActivityAt: 1,
            lastAssistantPreview: '已改 auth_service'}
    ]));
    desktop.pushSessionsWire('ws-b', snapshotWire([
        {sessionId: 's9', title: '写测试', phase: 'prewarming', lastActivityAt: 2}
    ]));

    const byKey = new Map(updates.map((u) => [u.key, u]));
    assert.strictEqual(byKey.size, 2);
    assert.strictEqual(byKey.get('ws-a').sessions[0].title, '重构登录');
    assert.strictEqual(byKey.get('ws-a').source, 'active');
    assert.strictEqual(byKey.get('ws-a').title, 'a', 'workspace title falls back to the last path segment');
    assert.strictEqual(byKey.get('ws-b').sessions[0].phase, 'prewarming');
});

test('active mode respects the workspace cap and the subscribe-all switch', async () => {
    const capped = makeClient({maxWorkspaces: 1});
    capped.desktop.workspaces = [
        {workspacePath: '/a'}, {workspacePath: '/b'}, {workspacePath: '/c'}
    ];
    await capped.client.start();
    assert.strictEqual(capped.desktop.subscriptions.length, 1);

    const off = makeClient({subscribeAll: false});
    off.desktop.workspaces = [{workspacePath: '/a'}];
    await off.client.start();
    assert.strictEqual(off.desktop.subscriptions.length, 0, 'no writes when subscribe-all is off');
});

test('a failing workspace does not abort the others', async () => {
    const {client, desktop} = makeClient();
    desktop.workspaces = [
        {workspacePath: '/ok-1'}, {workspacePath: '/bad'}, {workspacePath: '/ok-2'}
    ];
    desktop.failingWorkspaces.add('/bad');
    await client.start();
    const opened = desktop.subscriptions.map((s) => s.scope.workspacePath).sort();
    assert.deepStrictEqual(opened, ['/ok-1', '/ok-2']);
});

test('a sequence gap triggers a resync call', async () => {
    const {client, desktop} = makeClient();
    desktop.workspaces = [{workspacePath: '/repo/a', workspaceIdentity: 'ws-a'}];
    await client.start();
    desktop.pushSessionsWire('ws-a', snapshotWire([{sessionId: 's1', phase: 'running'}], 5));
    const before = desktop.sent.length;
    desktop.pushSessionsWire('ws-a', {
        topic: 'sessions-index/ws-a',
        kind: 'complete',
        frame: {
            fromSeq: 4, toSeq: 6,
            payload: {kind: 'deltas', deltas: [{op: 'session.removed', sessionId: 's1'}]}
        }
    });
    await new Promise((r) => setTimeout(r, 0));
    const resyncs = desktop.sent.slice(before).filter((p) => p.zcode_type === 'rpc-frame');
    assert.ok(resyncs.length > 0, 'a resync request must be sent after a gap');
});

// ---------------------------------------------------------------------------
// RemoteClient — passive coverage
// ---------------------------------------------------------------------------

test('passive mode reads the page sessions-index without writing anything', () => {
    const {client, desktop} = makeClient();
    const updates = [];
    client.onSessions = (update) => updates.push(update);

    const pageBridge = 'page-bridge-1';
    // The page listens for the event: this is how we learn the id/scope map.
    const listenBody = encodeBody(
        [P.REQ_EVENT_LISTEN, 3, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}
    );
    for (const payload of fragment(listenBody, pageBridge, 1)) {
        client.acceptObservedPayload(payload, true);
    }

    const wire = snapshotWire([{sessionId: 'sp', title: '页面里的任务', phase: 'running',
        lastAssistantActivity: 1, lastActivityAt: 3}]);
    const fireBody = encodeBody([P.RES_EVENT_FIRE, 3], wire);
    const writesBefore = desktop.sent.length;
    for (const payload of fragment(fireBody, pageBridge, 2)) {
        client.acceptObservedPayload(payload, false);
    }

    assert.strictEqual(updates.length, 1);
    assert.strictEqual(updates[0].key, 'ws-page');
    assert.strictEqual(updates[0].source, 'passive');
    assert.strictEqual(updates[0].sessions[0].title, '页面里的任务');
    assert.strictEqual(desktop.sent.length, writesBefore, 'passive mode must never write');
});

test('active mode skips a workspace the page already streams', async () => {
    const {client, desktop} = makeClient();
    desktop.workspaces = [
        {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'},
        {workspacePath: '/repo/other', workspaceIdentity: 'ws-other'}
    ];
    // The page is already on ws-page before we start.
    const listenBody = encodeBody(
        [P.REQ_EVENT_LISTEN, 1, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}
    );
    for (const payload of fragment(listenBody, 'page-bridge-9', 1)) {
        client.acceptObservedPayload(payload, true);
    }

    await client.start();
    assert.deepStrictEqual(
        desktop.subscriptions.map((s) => s.scope.workspaceIdentity),
        ['ws-other'],
        'the page-covered workspace must not be duplicated'
    );
});

// ---------------------------------------------------------------------------
// RemoteClient — relay reconnect / page-RPC tracing
//
// These pin the two things the first field log could not answer: why the shell
// kept re-opening a bridge for the workspace the page was already showing (a
// relay reconnect used to wipe that knowledge), and what the page itself asks
// the desktop for when a task is opened.
// ---------------------------------------------------------------------------

test('page coverage survives a relay reconnect (shared state)', async () => {
    const pageScope = {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'};
    const first = makeClient();
    const listenBody = encodeBody(
        [P.REQ_EVENT_LISTEN, 1, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        pageScope
    );
    for (const payload of fragment(listenBody, 'page-bridge-1', 1)) {
        first.client.acceptObservedPayload(payload, true);
    }

    // A relay disconnect rebuilds the client, exactly like inject.js does. The
    // learned coverage has to come along, or the page's own workspace is
    // duplicated and the desktop answers rpc-transport-fault in a loop.
    const second = makeClient({sharedState: first.client.sharedState()});
    second.desktop.workspaces = [
        pageScope,
        {workspacePath: '/repo/other', workspaceIdentity: 'ws-other'}
    ];
    await second.client.start();

    assert.deepStrictEqual(
        second.desktop.subscriptions.map((s) => s.scope.workspaceIdentity),
        ['ws-other'],
        'the rebuilt client must not duplicate the workspace the page still owns'
    );
});

test('a bridge is dropped once the page proves it streams that workspace', async () => {
    const logs = [];
    const {client, desktop} = makeClient({log: (message) => logs.push(message)});
    desktop.workspaces = [{workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}];
    await client.start();
    assert.strictEqual(Object.keys(client._bridges).length, 1, 'opened before the page was seen');

    const listenBody = encodeBody(
        [P.REQ_EVENT_LISTEN, 1, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}
    );
    for (const payload of fragment(listenBody, 'page-bridge-77', 1)) {
        client.acceptObservedPayload(payload, true);
    }

    assert.strictEqual(Object.keys(client._bridges).length, 0,
        'the duplicate bridge must be closed, not left to fault');
    assert.ok(logs.some((m) => m.includes('页面已接管')), 'and the reason must be logged');
});

test('page RPCs are traced: slow calls, errors and a per-window summary', () => {
    const logs = [];
    // pageRpcSlowMs: 0 makes every completed call "slow" without sleeping.
    const {client} = makeClient({log: (message) => logs.push(message), pageRpcSlowMs: 0});
    const bridge = 'page-bridge-5';

    const request = encodeBody(
        [P.REQ_PROMISE, 42, 'zcode-agent', 'openConversationV4'],
        {sessionId: 's1'}
    );
    for (const payload of fragment(request, bridge, 1)) {
        client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(client._pageRpc.calls, 1, 'the page call must be recorded when sent');

    const ok = encodeBody([P.RES_PROMISE_SUCCESS, 42], {ok: true});
    for (const payload of fragment(ok, bridge, 2)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.ok(
        logs.some((m) => m.includes('页面调用慢') && m.includes('openConversationV4')),
        'a slow page call must name the method'
    );

    // A failing call is the other half of "content never loads": it has to be
    // surfaced with the desktop's own message, not swallowed as a non-event.
    const failing = encodeBody(
        [P.REQ_PROMISE, 43, 'zcode-agent', 'getConversationV4'],
        {}
    );
    for (const payload of fragment(failing, bridge, 3)) {
        client.acceptObservedPayload(payload, true);
    }
    const error = encodeBody([P.RES_PROMISE_ERROR, 43], {message: 'workspace not ready'});
    for (const payload of fragment(error, bridge, 4)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.ok(
        logs.some((m) => m.includes('页面调用失败') && m.includes('workspace not ready')),
        'a failed page call must carry the error message'
    );

    client.reportPageRpcWindow();
    assert.ok(
        logs.some((m) => m.includes('页面 RPC 10s') && m.includes('2 个') && m.includes('失败 1')),
        'the window summary must count calls and failures'
    );
});

test('a page-held workspace is dropped, not reopened, when the desktop refuses our bridge', async () => {
    const logs = [];
    const {client, desktop} = makeClient({log: (message) => logs.push(message)});
    desktop.workspaces = [{workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}];
    await client.start();
    const ours = client._bridges['ws-page'];
    assert.ok(ours, 'we opened a bridge for it first');

    // The page opens its own bridge for the same workspace: a ready frame for an
    // id we never requested is the only way to tell the two apart.
    client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-1',
        bridge: {bridgeSessionId: 'page-bridge-1', workspaceKey: 'ws-page'}
    }, false);

    // Then the desktop refuses ours. Page-held AND refused is the evidence pair;
    // a bare fault must not be treated as proof (that would lose coverage).
    client.acceptPayload({
        zcode_type: 'bridge-degraded',
        bridgeSessionId: ours.bridgeSessionId,
        reason: 'rpc-transport-fault'
    });

    assert.strictEqual(client._pageOwned['ws-page'], true, 'marked page-owned');
    assert.strictEqual(Object.keys(client._bridges).length, 0, 'our duplicate was dropped');
    assert.ok(logs.some((m) => m.includes('桌面端拒绝我们的重复 bridge')));
    assert.ok(!logs.some((m) => m.indexOf('reopening') === 0), 'no reopen loop');
});

test('a workspace that keeps faulting is dropped instead of reopened forever', async () => {
    const logs = [];
    // maxReopensPerBridge: 0 makes the first fault give up, without waiting out
    // the reopen delay.
    const {client, desktop} = makeClient({
        log: (message) => logs.push(message),
        maxReopensPerBridge: 0
    });
    desktop.workspaces = [{workspacePath: '/repo/flaky'}];
    await client.start();
    const ours = client._bridges['/repo/flaky'];

    client.acceptPayload({
        zcode_type: 'bridge-degraded',
        bridgeSessionId: ours.bridgeSessionId,
        reason: 'rpc-transport-fault'
    });

    assert.strictEqual(Object.keys(client._bridges).length, 0, 'forgotten, not retried');
    assert.ok(logs.some((m) => m.includes('本次连接放弃重开')));
    assert.ok(!logs.some((m) => m.indexOf('reopening') === 0), 'and it did not reopen');
});

test('in-flight page RPCs are visible, so the burst can wait for them', () => {
    const {client} = makeClient();
    const bridge = 'page-bridge-9';

    const request = encodeBody([P.REQ_PROMISE, 1, 'zcode-agent', 'openConversationV4'], {});
    for (const payload of fragment(request, bridge, 1)) {
        client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(client.inFlightPageRpcs(), 1, 'the open request is in flight');

    const ok = encodeBody([P.RES_PROMISE_SUCCESS, 1], {ok: true});
    for (const payload of fragment(ok, bridge, 2)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.strictEqual(client.inFlightPageRpcs(), 0, 'and it clears when answered');
});
