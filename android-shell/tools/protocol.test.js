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

test('active mode respects the workspace cap（只剩单测 seam：壳永不调用 start）', async () => {
    const capped = makeClient({maxWorkspaces: 1});
    capped.desktop.workspaces = [
        {workspacePath: '/a'}, {workspacePath: '/b'}, {workspacePath: '/c'}
    ];
    await capped.client.start();
    assert.strictEqual(capped.desktop.subscriptions.length, 1);
});

test('只读壳：没有「订阅所有工作区」开关，客户端自己不开桥（D7 已删，2026-09-17）', () => {
    // 这条原来是 `makeClient({subscribeAll: false})` → "no writes when subscribe-all
    // is off"。开关整个删掉后，等价断言分两半，都要能**独立失败**：
    //   ① 字段本身不存在——有人把开关加回来这条就红（删掉的开关不许复活）；
    //   ② 默认构造之后一帧都不写页面 socket——主动覆盖只能由 start() 显式发起，
    //      而注入层永不调用它（正面证据在 inject.test.js「默认配置下不主动开桥」）。
    // 不写 `subscriptions.length === 0`：它被"零写入"严格蕴含，只是同一件事的复述。
    const {client, desktop} = makeClient();
    assert.strictEqual(client.subscribeAll, undefined,
        'D7 开关已删除：客户端不再有"我该主动开桥"这个状态');
    assert.strictEqual(desktop.sent.length, 0, '构造本身不得写页面 socket');
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

test('page coverage must be re-proven on a new relay connection (zombie rule)', async () => {
    const pageScope = {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'};
    const first = makeClient();
    const listenBody = encodeBody(
        [P.REQ_EVENT_LISTEN, 1, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        pageScope
    );
    for (const payload of fragment(listenBody, 'page-bridge-1', 1)) {
        first.client.acceptObservedPayload(payload, true);
    }

    // A relay disconnect rebuilds the client. 2026-09-13 真机实证：socket 重建后
    // 页面 runtime 不会重建（配对恢复但零业务帧、零自愈）——旧连接上的覆盖
    // 证据是僵尸，跨连接沿用 = 该工作区通知/实况窗全盲。新连接上壳必须接管；
    // 页面若真恢复了（reload 后重新开桥），观察到的 listen 会再次盖上本连接
    // 的证据，_dropRedundantBridge 再把壳的桥让出去。
    const second = makeClient({sharedState: first.client.sharedState()});
    second.desktop.workspaces = [
        pageScope,
        {workspacePath: '/repo/other', workspaceIdentity: 'ws-other'}
    ];
    await second.client.start();

    assert.deepStrictEqual(
        second.desktop.subscriptions.map((s) => s.scope.workspaceIdentity).sort(),
        ['ws-other', 'ws-page'],
        'the rebuilt client must take over the workspace the page no longer streams'
    );

    // 页面在同一连接上恢复流之后，壳的重复桥要让位（回到既有语义）。
    // 注意只让出 ws-page：ws-other 页面从未覆盖，壳的桥必须留下。
    for (const payload of fragment(listenBody, 'page-bridge-2', 1)) {
        second.client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(Object.keys(second.client._bridges).length, 1,
        'only the re-proven workspace is handed back');
    assert.ok(!second.client._bridges['ws-page'],
        'the page bridge replaces ours for the workspace it re-proved');
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

test('pageBridgeSessionIds exposes the page bridge id per workspace and skips our own', async () => {
    const {client, desktop} = makeClient({log: () => {}});
    desktop.workspaces = [{workspacePath: '/repo/ours'}, {workspacePath: '/repo/theirs'}];
    await client.start();
    assert.ok(client._bridges['/repo/ours'], 'our own bridge exists');

    // The page opens its own bridge for a workspace: a ready frame for an id we
    // never requested. This id is what a forged bridge-degraded frame must carry
    // (the page matches it against its own bridge object).
    client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-9',
        bridge: {bridgeSessionId: 'page-bridge-9', workspaceKey: '/repo/theirs'}
    }, false);

    const ids = client.pageBridgeSessionIds();
    assert.strictEqual(ids['/repo/theirs'], 'page-bridge-9',
        'the page bridge id is exposed for the degrade path');
    assert.strictEqual(ids['/repo/ours'], undefined,
        'our own bridges must never be mistaken for the page bridge');
});

test('the first fault on a workspace is not reopened (the shipped default)', async () => {
    const logs = [];
    // No maxReopensPerBridge override: the default is 0, i.e. one strike per
    // relay connection. Field evidence (2026-09-12) is that reopening does not
    // help a refused workspace — it "recovers" and faults again 40–75 s later, at
    // 4 RPCs per attempt, on the socket the page is using. The retry belongs to
    // the next relay connection, not to this one.
    const {client, desktop} = makeClient({log: (message) => logs.push(message)});
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

test('a workspace the desktop refuses on every connection goes on cooldown', async () => {
    const logs = [];
    // The real shape: a relay rebuild replaces the client but keeps the state
    // object, so the fault history has to live there. Otherwise every rebuild
    // hands the refused workspace a clean slate and the loop never ends — the
    // field log showed exactly that on `default`, faulting every ~45 s.
    const shared = {};
    const refuse = (client) => {
        const ours = client._bridges['/repo/refused'];
        assert.ok(ours, 'the bridge is open before the desktop refuses it');
        client.acceptPayload({
            zcode_type: 'bridge-degraded',
            bridgeSessionId: ours.bridgeSessionId,
            reason: 'rpc-transport-fault'
        });
    };
    for (let i = 0; i < 3; i += 1) {
        const made = makeClient({
            log: (message) => logs.push(message),
            maxReopensPerBridge: 0,
            sharedState: shared
        });
        made.desktop.workspaces = [{workspacePath: '/repo/refused'}];
        await made.client.start();
        refuse(made.client);
    }
    assert.ok(
        logs.some((m) => m.includes('冷却 /repo/refused')),
        'three consecutive connections must back the workspace off'
    );

    // The next connection skips it, but still covers everything else.
    const fourth = makeClient({log: (m) => logs.push(m), sharedState: shared});
    fourth.desktop.workspaces = [
        {workspacePath: '/repo/refused'},
        {workspacePath: '/repo/healthy', workspaceIdentity: 'ws-ok'}
    ];
    await fourth.client.start();
    assert.deepStrictEqual(
        fourth.desktop.subscriptions.map((s) => s.scope.workspaceIdentity),
        ['ws-ok'],
        'the cooled workspace is skipped, healthy coverage is untouched'
    );
});

test('a cooled-down workspace is retried once the cooldown expires', async () => {
    // A back-off, not a permanent drop: a desktop that was unreachable for a
    // while must not cost notification coverage forever.
    const shared = {cooldownUntil: {'/repo/refused': Date.now() - 1}};
    const {client, desktop} = makeClient({sharedState: shared});
    desktop.workspaces = [{workspacePath: '/repo/refused'}];
    await client.start();
    assert.strictEqual(desktop.subscriptions.length, 1, 'an expired cooldown means try again');
    assert.ok(client.sharedState().cooldownUntil, 'and the map keeps travelling with the state');
});

test('page-held evidence survives a relay rebuild (attached, not read)', async () => {
    // Regression for a silent one: the constructor read the page-owned maps with
    // `shared.pageOwned || {}`, so a caller object that did not carry the key yet
    // left the client with a PRIVATE map — the knowledge died with the client in
    // the real app, while the explicit sharedState() juggling in the tests hid it.
    const shared = {};
    const first = makeClient({sharedState: shared});
    first.desktop.workspaces = [{workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}];
    await first.client.start();
    const ours = first.client._bridges['ws-page'];

    first.client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-1',
        bridge: {bridgeSessionId: 'page-bridge-1', workspaceKey: 'ws-page'}
    }, false);
    first.client.acceptPayload({
        zcode_type: 'bridge-degraded',
        bridgeSessionId: ours.bridgeSessionId,
        reason: 'rpc-transport-fault'
    });
    assert.strictEqual(first.client._pageOwned['ws-page'], true, 'marked page-owned');

    const second = makeClient({sharedState: shared});
    second.desktop.workspaces = [{workspacePath: '/repo/page', workspaceIdentity: 'ws-page'}];
    await second.client.start();
    // 2026-09-13 语义更新：覆盖证据按连接代次（_pageCoveredKeys，per-client），
    // 共享的 pageOwned 不再让新连接无限让位——socket 重建后页面 runtime 不重建
    // （僵尸订阅），壳必须接管。本断言保留的回归点是：共享 map 必须被"附着"
    // 引用而非读取复制（attached, not read），_pageOwned 的知识要跨客户端可见。
    assert.strictEqual(second.client._pageOwned['ws-page'], true,
        'the shared page-owned map is attached, not copied');
    assert.strictEqual(
        second.desktop.subscriptions.length,
        1,
        'without same-connection page evidence the shell takes the workspace over'
    );
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

test('the burst yields while a page request is in flight, but not past its budget', async () => {
    const {client} = makeClient();
    const bridge = 'page-bridge-y';
    const request = encodeBody([P.REQ_PROMISE, 5, 'zcode-agent', 'openConversationV4'], {});
    for (const payload of fragment(request, bridge, 1)) {
        client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(client.inFlightPageRpcs(), 1);

    // A deadline already in the past resolves at once: the budget is what stops
    // a page that is never idle from stretching the burst.
    await client.awaitPageIdle(Date.now() - 1);

    // Otherwise it resumes as soon as the reply lands, rather than waiting the
    // budget out.
    const started = Date.now();
    const waiting = client.awaitPageIdle(Date.now() + 5000);
    await new Promise((r) => setTimeout(r, 30));
    const ok = encodeBody([P.RES_PROMISE_SUCCESS, 5], {ok: true});
    for (const payload of fragment(ok, bridge, 2)) {
        client.acceptObservedPayload(payload, false);
    }
    await waiting;
    assert.ok(Date.now() - started < 3000, 'resumed as soon as the page was idle');
});

test('onPageRpcCall hands the page\'s outbound call name and args to the shell', () => {
    const calls = [];
    const {client} = makeClient({});
    client.onPageRpcCall = (call) => calls.push(call);
    const body = encodeBody(
        [P.REQ_PROMISE, 7, 'zcode-agent', 'subscribeConversationV4'],
        {sessionId: 'sess_beacon'}
    );
    for (const payload of fragment(body, 'page-bridge-9', 1)) {
        client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(calls.length, 1, 'one promise call, one event');
    assert.strictEqual(calls[0].name, 'zcode-agent.subscribeConversationV4');
    assert.strictEqual(calls[0].args && calls[0].args.sessionId, 'sess_beacon',
        'the sessionId rides along for the fallback watcher');
});

test('onPageRpcResult hands the page call outcome to the shell', () => {
    const results = [];
    const {client} = makeClient({});
    client.onPageRpcResult = (r) => results.push(r);
    const bridge = 'page-bridge-10';
    const call = encodeBody([P.REQ_PROMISE, 8, 'zcode-file', 'uploadArtifact'], {a: 1});
    for (const payload of fragment(call, bridge, 1)) {
        client.acceptObservedPayload(payload, true);
    }
    const ok = encodeBody([P.RES_PROMISE_SUCCESS, 8], {done: true});
    for (const payload of fragment(ok, bridge, 2)) {
        client.acceptObservedPayload(payload, false);
    }
    const call2 = encodeBody([P.REQ_PROMISE, 9, 'zcode-file', 'uploadArtifact'], {a: 1});
    for (const payload of fragment(call2, bridge, 3)) {
        client.acceptObservedPayload(payload, true);
    }
    const err = encodeBody([P.RES_PROMISE_ERROR, 9], {message: 'disk full'});
    for (const payload of fragment(err, bridge, 4)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.strictEqual(results.length, 2);
    assert.deepStrictEqual([results[0].ok, results[0].name],
        [true, 'zcode-file.uploadArtifact']);
    assert.ok(results[0].cost >= 0);
    assert.deepStrictEqual([results[1].ok, results[1].message], [false, 'disk full']);
});

test('lastPageBridgeTrafficAt：页面桥入站 rpc-frame 盖章，我方桥与出站不盖', () => {
    const {client} = makeClient();
    assert.strictEqual(client.lastPageBridgeTrafficAt(), 0, 'nothing seen yet');

    // 我方桥（已登记在 _bridgesById）的入站帧：在盖章点之前就 return。
    client._bridgesById['shell-bridge-own'] = {workspaceKey: 'ws-own'};
    const ownBody = encodeBody([P.RES_PROMISE_SUCCESS, 41], {ok: true});
    for (const payload of fragment(ownBody, 'shell-bridge-own', 1)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.strictEqual(client.lastPageBridgeTrafficAt(), 0, 'our own bridges must not stamp');

    // 页面桥的入站 rpc-frame：盖章。
    const okBody = encodeBody([P.RES_PROMISE_SUCCESS, 42], {rows: []});
    for (const payload of fragment(okBody, 'page-bridge-5', 1)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.ok(client.lastPageBridgeTrafficAt() > 0, 'page-bridge inbound frames stamp the clock');

    // 页面桥的出站帧（页面→桌面）不盖章：这是请求不是下发。
    const reqBody = encodeBody([P.REQ_PROMISE, 43, 'zcode-agent', 'conversationRowsRangeV4'],
        {sessionId: 'sess_x'});
    const stamped = client.lastPageBridgeTrafficAt();
    for (const payload of fragment(reqBody, 'page-bridge-5', 1)) {
        client.acceptObservedPayload(payload, true);
    }
    assert.strictEqual(client.lastPageBridgeTrafficAt(), stamped,
        'outbound page frames are not desktop deliveries');
});

// ---------------------------------------------------------------------------
// 对话流 → 卡片正文（流体云"流式跟手"的数据源）
//
// 帧契约（与 Kotlin 侧 RelayWire 的解码注释同一份）：
//   {topic, subscriptionId, fromSeq, toSeq, payload:{kind:'snapshot'|'deltas'}}
//   snapshot → payload.rows.window = [row…]
//   deltas   → payload.ops = [{op:'row.appended'|'row.upserted'|'row.delta', …}]
//   row.delta = {rowId, path:'text', append}
// 这几条钉的就是"哪些行能上卡片、增量怎么拼"——真机上唯一验不了的是"桌面端到底发不发
// 帧"，而那不是这段代码能决定的。
// ---------------------------------------------------------------------------

function convSnapshot(topic, rows) {
    return {topic: topic, payload: {kind: 'snapshot', rows: {window: rows}}};
}

function convDeltas(topic, ops) {
    return {topic: topic, payload: {kind: 'deltas', ops: ops}};
}

test('conversation text: a snapshot yields the last assistantText row', () => {
    const {client} = makeClient();
    client._trackConversationText(convSnapshot('conversation/sess_1', [
        {kind: 'userInput', rowId: 1, text: '问题'},
        {kind: 'assistantText', rowId: 2, text: '第一段'},
        {kind: 'toolCall', rowId: 3, toolName: 'Bash', status: 'running'},
        {kind: 'assistantText', rowId: 4, text: '第二段正文'}
    ]));
    const got = client.latestConversationText();
    assert.ok(got, 'a snapshot with an assistantText row must be reportable');
    assert.strictEqual(got.text, '第二段正文');
    assert.strictEqual(got.topic, 'conversation/sess_1');
    assert.strictEqual(got.rowId, 4);
});

test('conversation text: deltas append to the tracked row, and only to it', () => {
    const {client} = makeClient();
    client._trackConversationText(convSnapshot('conversation/sess_2', [
        {kind: 'assistantText', rowId: 9, text: '开头'}
    ]));
    client._trackConversationText(convDeltas('conversation/sess_2', [
        {op: 'row.delta', rowId: 9, path: 'text', append: '，继续'},
        // 别的行（我们没在跟）的增量必须丢掉，否则会把两段正文拼在一起。
        {op: 'row.delta', rowId: 99, path: 'text', append: '别人的增量'},
        // 同行的别的路径（工具的输入）不上卡片。
        {op: 'row.delta', rowId: 9, path: 'inputText', append: '工具输入'}
    ]));
    assert.strictEqual(client.latestConversationText().text, '开头，继续');
});

test('conversation text: only assistantText rows feed the card', () => {
    const {client} = makeClient();
    client._trackConversationText(convSnapshot('conversation/sess_3', [
        {kind: 'assistantText', rowId: 1, text: '正文'}
    ]));
    client._trackConversationText(convDeltas('conversation/sess_3', [
        {op: 'row.appended', row: {kind: 'toolCall', rowId: 2, toolName: 'Bash', status: 'running'}},
        {op: 'row.upserted', row: {kind: 'subagent', rowId: 3, summaryText: '正在挖协议'}},
        {op: 'row.upserted', row: {kind: 'reasoning', rowId: 4, text: '在心里想'}}
    ]));
    assert.strictEqual(client.latestConversationText().text, '正文',
        '工具调用 / 子代理 / reasoning 都不许覆盖正文（2026-09-14 真机反馈）');
});

test('conversation text: the most recently updated topic wins', () => {
    const {client} = makeClient();
    client._trackConversationText(convSnapshot('conversation/sess_a', [
        {kind: 'assistantText', rowId: 1, text: 'A 的正文'}
    ]));
    client._trackConversationText(convSnapshot('conversation/sess_b', [
        {kind: 'assistantText', rowId: 1, text: 'B 的正文'}
    ]));
    assert.strictEqual(client.latestConversationText().topic, 'conversation/sess_b');
    assert.strictEqual(client.latestConversationText().text, 'B 的正文');
    // 没有正文的 topic 不给东西（调用方要保留原 preview，不要拿空串覆盖）。
    const {client: empty} = makeClient();
    empty._trackConversationText(convSnapshot('conversation/sess_c', [
        {kind: 'turnHeader', rowId: 1, state: 'running'}
    ]));
    assert.strictEqual(empty.latestConversationText(), null);
});

// ---------------------------------------------------------------------------
// **真实的线格式**（2026-09-15 真机打出来的三层嵌套，别再照文档猜）
//
//   data  = { wireVersion, kind:'complete', deliveryKind, logicalFrameId,
//             logicalFrameOrdinal, topic, subscriptionId, frame }
//   frame = { topic, subscriptionId, sentAt, fromSeq, toSeq, payload }
//   frame.payload = { kind:'snapshot'|'deltas', rows:{window:[…]} / ops:[…] }
//
// 两个曾经踩空的点：① 内容体在 frame.payload（不是 data.payload，也不是 data.frame）；
// ② `data.kind` 是**投递**类别（'complete'），拿它判 snapshot/deltas 会永远判错。
// ---------------------------------------------------------------------------

function realConversationFrame(topic, subscriptionId, payload, seq) {
    return {
        wireVersion: 1,
        kind: 'complete',
        deliveryKind: 'stream',
        logicalFrameId: 7,
        logicalFrameOrdinal: 3,
        topic: topic,
        subscriptionId: subscriptionId,
        frame: {
            topic: topic,
            subscriptionId: subscriptionId,
            sentAt: 1,
            fromSeq: seq,
            toSeq: seq,
            payload: payload
        }
    };
}

test('conversation text: the real three-level wire shape is parsed', () => {
    const {client} = makeClient();
    client._trackConversationText(realConversationFrame('conversation/sess_real', 'sub-real', {
        kind: 'snapshot',
        rows: {
            window: [
                {kind: 'userInput', rowId: 1, text: '问题'},
                {kind: 'assistantText', rowId: 2, text: '真实形状的正文'}
            ]
        }
    }, 11));
    const got = client.latestConversationText();
    assert.ok(got, '三层嵌套的快照必须能解出正文');
    assert.strictEqual(got.text, '真实形状的正文');
    assert.strictEqual(got.topic, 'conversation/sess_real');
    assert.strictEqual(got.rowId, 2);
});

test('conversation text: real-shape deltas append to the tracked row', () => {
    const {client} = makeClient();
    client._trackConversationText(realConversationFrame('conversation/sess_d', 'sub-d', {
        kind: 'snapshot',
        rows: {window: [{kind: 'assistantText', rowId: 9, text: '开头'}]}
    }, 1));
    client._trackConversationText(realConversationFrame('conversation/sess_d', 'sub-d', {
        kind: 'deltas',
        ops: [{op: 'row.delta', rowId: 9, path: 'text', append: '，接着写'}]
    }, 2));
    assert.strictEqual(client.latestConversationText().text, '开头，接着写');
});

test('conversation candidates: the live native seed wins over the snapshot', () => {
    const {client} = makeClient();
    client.nativeRunningSessions = {'ws-live': ['sess_stale']};
    client.nativeRunningSessionsProvider = () => ({'ws-live': ['sess_live']});
    assert.deepStrictEqual(client._conversationCandidates('ws-live'), ['sess_live']);
    assert.deepStrictEqual(client._conversationCandidates('ws-unknown'), []);
});

// ---------------------------------------------------------------------------
// 对话流的看门狗与主动重锚
//
// 这一组照抄 `E:\zemote\lib\protocol\conversation.dart:1048-1068 / 943-968` 的契约：
// 网页端**不做**周期性 resync，所以桌面端一安静页面就停住（前台也一样）；zemote 靠
// "10s 一跳、静默 20s、且会话确实在跑 → resyncConversationV4{forceSnapshot:true, base}"
// 主动要快照，才做到连续稳定。这里把那条契约钉住，免得以后又被改回被动等推送。
// ---------------------------------------------------------------------------

/** 假 bridge：记录 channels.call 的参数，便于断言重锚请求的内容。 */
function fakeConversationBridge(key) {
    const calls = [];
    return {
        key: key,
        scope: {workspacePath: 'E:\\fake-' + key, workspaceIdentity: 'fake-' + key},
        calls: calls,
        channels: {
            call: (channel, method, args) => {
                calls.push({channel: channel, method: method, args: args});
                return Promise.resolve({});
            },
            addEventListener: () => ({dispose() {}})
        }
    };
}

function fakeConversationSub(bridge, overrides) {
    return Object.assign({
        key: bridge.key,
        sessionId: 'sess_1',
        bridge: bridge,
        subscriptionId: 'sub-A',
        logEpoch: null,
        seq: 0,
        lastFrameAt: Date.now(),
        lastTextAt: 0,
        lastResyncAt: 0,
        resyncCount: 0,
        resyncing: false
    }, overrides || {});
}

test('conversation frames are filtered by subscriptionId', () => {
    const {client} = makeClient();
    const sub = fakeConversationSub(fakeConversationBridge('ws-filter'));
    // 别的订阅的帧（两条会话同时订阅时会落在同一个事件通道上）必须丢掉。
    client._acceptConversationFrame(sub, {
        topic: 'conversation/sess_1', subscriptionId: 'sub-B', payload: {}
    });
    assert.strictEqual(client._convFrames || 0, 0, '不是自己订阅的帧不能算');
    client._acceptConversationFrame(sub, {
        topic: 'conversation/sess_1', subscriptionId: 'sub-A', payload: {}
    });
    assert.strictEqual(client._convFrames, 1, '自己订阅的帧要收下');
});

test('conversationFrameStats 报出"最后一帧距今多久"（承载提前接管的判据）', () => {
    const {client} = makeClient();
    // 一帧都没见过 ⇒ −1（原生据此判"页面没在跟任何会话"）。
    assert.strictEqual(
        client.conversationFrameStats().lastFrameAgoMs,
        -1,
        '没见过会话帧时必须报 -1，不能报 0（0 会被误读成"刚刚还在收"）',
    );
    const sub = fakeConversationSub(fakeConversationBridge('ws-ago'));
    client._acceptConversationFrame(sub, {
        topic: 'conversation/sess_1', subscriptionId: 'sub-A', payload: {}
    });
    const ago = client.conversationFrameStats().lastFrameAgoMs;
    assert.ok(typeof ago === 'number' && ago >= 0 && ago < 5_000, `刚收到的帧年龄应接近 0，实际 ${ago}`);
});

test('a sequence gap triggers an immediate conversation resync with its位点', () => {
    const {client} = makeClient();
    const bridge = fakeConversationBridge('ws-gap');
    const sub = fakeConversationSub(bridge, {logEpoch: 'ep-1', seq: 5});
    client._acceptConversationFrame(sub, {
        topic: 'conversation/sess_1', subscriptionId: 'sub-A',
        fromSeq: 9, toSeq: 12, payload: {}
    });
    assert.strictEqual(bridge.calls.length, 1, '跳号必须立刻重锚（不等看门狗）');
    assert.strictEqual(bridge.calls[0].method, 'resyncConversationV4');
    const args = bridge.calls[0].args[0];
    assert.strictEqual(args.forceSnapshot, true, '要的是完整快照');
    assert.deepStrictEqual(args.base, {logEpoch: 'ep-1', seq: 5}, '要带上自己的位点');
    assert.strictEqual(sub.seq, 12, '位点随后推进到 toSeq');
});

test('a resync without a baseline sends base: null (never an omitted field)', () => {
    const {client} = makeClient();
    const bridge = fakeConversationBridge('ws-base');
    const sub = fakeConversationSub(bridge, {logEpoch: null});
    client._resyncConversation(sub, 'test');
    const args = bridge.calls[0].args[0];
    // 桌面端的 zod schema 是 `.nullable()` 而不是 `.optional()`：省略会被拒收
    // （`expected object, received undefined`）——README 记过的真机定案。
    assert.ok('base' in args, 'base 字段必须存在');
    assert.strictEqual(args.base, null, '无基线要显式传 null');
});

test('the conversation watchdog resyncs a quiet but active conversation', () => {
    const {client} = makeClient();
    const bridge = fakeConversationBridge('ws-quiet-active');
    const sub = fakeConversationSub(bridge, {
        lastFrameAt: Date.now() - 25000,        // 静默 25s > 20s
        lastTextAt: Date.now() - 1000           // 正文刚还在涨 → 视作在跑
    });
    client._convSubs = {sess_1: sub};
    client._conversationWatchdogTick();
    assert.strictEqual(bridge.calls.length, 1, '静默且在跑 → 主动要一份快照');
    assert.strictEqual(sub.resyncCount, 1);
});

test('the conversation watchdog leaves an idle conversation alone', () => {
    const {client} = makeClient();
    const bridge = fakeConversationBridge('ws-idle');
    // 静默很久，但正文早就停了、会话索引里也没有它 → 不该折腾。
    const sub = fakeConversationSub(bridge, {
        lastFrameAt: Date.now() - 60000,
        lastTextAt: 0
    });
    client._convSubs = {sess_1: sub};
    client._conversationWatchdogTick();
    assert.strictEqual(bridge.calls.length, 0, '没在跑就别重锚');
});

test('the conversation watchdog is throttled and circuit-broken', () => {
    const {client} = makeClient();
    const bridge = fakeConversationBridge('ws-throttle');
    const sub = fakeConversationSub(bridge, {
        lastFrameAt: Date.now() - 25000,
        lastTextAt: Date.now() - 1000,
        lastResyncAt: Date.now()            // 刚重锚过 → 节流窗口内
    });
    client._convSubs = {sess_1: sub};
    client._conversationWatchdogTick();
    assert.strictEqual(bridge.calls.length, 0, '节流窗口内不重复重锚');

    // 熔断：连续多次重锚都没换来帧就停手，等帧自己回来（帧到了会把计数清零）。
    sub.lastResyncAt = 0;
    sub.resyncCount = 5;
    client._conversationWatchdogTick();
    assert.strictEqual(bridge.calls.length, 0, '到上限就熔断');

    // 并发幂等：正在重锚时不叠加第二次。
    sub.resyncCount = 0;
    sub.resyncing = true;
    client._conversationWatchdogTick();
    assert.strictEqual(bridge.calls.length, 0, 'resyncing 期间不叠加');
});

test('conversation candidates: a failing provider falls back, and the cap holds', () => {
    const {client} = makeClient();
    // 桥没了（config() 抛了）→ 退回创建时的快照，不能让异常冒出去。
    client.nativeRunningSessions = {'ws-fb': ['sess_fb']};
    client.nativeRunningSessionsProvider = () => {
        throw new Error('bridge gone');
    };
    assert.deepStrictEqual(client._conversationCandidates('ws-fb'), ['sess_fb']);
    // 一次最多订 CONVERSATION_MAX 条：卡片只显示得下少数几张，多订只是白烧桌面端。
    client.nativeRunningSessionsProvider = () => ({'ws-cap': ['s1', 's2', 's3']});
    assert.deepStrictEqual(client._conversationCandidates('ws-cap'), ['s1', 's2']);
});

