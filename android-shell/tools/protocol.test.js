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

// `makeClient` wires a RemoteClient to the shared fake desktop.
//
// 4c（2026-09-18）：`deliver` 原来是 `client.acceptPayload(payload)`——把桌面端
// 回给"壳自己那座桥"的业务载荷回灌进 RemoteClient。`acceptPayload` 已随桥路由
// 子系统删净（只读壳下壳一个字节都不往页面 socket 上写，故永远没有回灌对象），
// 于是这里落成**显式 no-op**。保留这个参数是因为 DesktopCore 需要它，同时它也是
// "同一个 mock 服务两个 seam"的说明点：将来若恢复开桥，这里要接回入站分派。
// ⚠️ 别再假设"每个协议测试都从一个入站总入口进去"——被动组一律直呼
// `acceptObservedPayload`（见 pageFollows / pagePushes）。
function makeClient(options) {
    const client = new P.RemoteClient({
        send: () => {},
        log: () => {},
        ...options
    });
    const desktop = new DesktopCore({deliver: () => {}});
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

// ---------------------------------------------------------------------------
// 被动路径的「序号缺口 ⇒ 该工作区陈旧」检出（4c 工程前置，2026-09-18）
//
// 背景：增量帧带 `fromSeq`，`fromSeq !== seq` 就是**漏掉了一个增量**。漏增量的后果是壳对
// "谁在跑"的印象静默漂移——`TaskStore` 有一条真机定案：运行态被会话索引覆盖成"全部完成"
// ⟹ `runningTaskRefs()` 变空 ⟹ 活进展被丢、流体云卡片冻死；而"提前接管"的第一道闸门问的
// 正是"有没有在跑"（`ShellRuntime` `earlyTakeoverSince` 那一段）。
//
// 检出那一步在活路上本来就可达，但它的消费者（`_resyncSessionsIndex`）只写在**已死的**
// 主动开桥路径里，所以这个事件在真机上从没留下痕迹。这几条钉的就是新加的那一半：
//   ① 缺口**按工作区**置位（别的工作区不许被牵连）；
//   ② **只有整窗快照**能解除（缺口期间 `seq` 有意不动 ⇒ 后续每一帧增量都还会判失败）；
//   ③ 检出与自愈各记**一行**日志（工作区键 + 缺口事实），且不许逐帧刷屏。
// ---------------------------------------------------------------------------

/** 让客户端"看见"页面自己对某工作区发起的 sessions-index 订阅（真实入口：出站帧）。 */
function pageFollows(client, bridgeSessionId, messageSeq, listenId, scope) {
    const body = encodeBody(
        [P.REQ_EVENT_LISTEN, listenId, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        scope
    );
    for (const payload of fragment(body, bridgeSessionId, messageSeq)) {
        client.acceptObservedPayload(payload, true);
    }
}

/** 页面那条订阅上推一帧（真实入口：入站 RES_EVENT_FIRE）。 */
function pagePushes(client, bridgeSessionId, messageSeq, listenId, wire) {
    const body = encodeBody([P.RES_EVENT_FIRE, listenId], wire);
    for (const payload of fragment(body, bridgeSessionId, messageSeq)) {
        client.acceptObservedPayload(payload, false);
    }
}

/** 一条跳号的增量帧：本机锚点在 [seq]，来的却是 [fromSeq]→[toSeq]。 */
function gapDelta(topic, fromSeq, toSeq) {
    return {
        topic: topic,
        kind: 'complete',
        frame: {
            fromSeq: fromSeq,
            toSeq: toSeq,
            payload: {kind: 'deltas', deltas: [{op: 'session.removed', sessionId: 's1'}]}
        }
    };
}

test('缺口状态机：增量跳号置 stale，且只有整窗快照能清它', () => {
    const state = new P.SessionsIndexState();
    assert.strictEqual(state.stale, false, '初始不陈旧');
    state.applyWireFrame(snapshotWire([{sessionId: 's1', phase: 'running'}], 5));
    assert.strictEqual(state.stale, false, '成功应用整窗快照后仍然不陈旧');

    assert.strictEqual(state.applyLogicalFrame({
        fromSeq: 4, toSeq: 6,
        payload: {kind: 'deltas', deltas: [{op: 'session.removed', sessionId: 's1'}]}
    }), false);
    assert.strictEqual(state.stale, true, '跳号 ⇒ 该工作区陈旧');
    assert.strictEqual(state.gapFromSeq, 4, '缺口事实记下的是那一帧的 fromSeq');
    assert.strictEqual(state.seq, 5, '锚点不动：缺口期间后续帧还会再判失败');
    assert.strictEqual(state.list().length, 1, '丢掉的增量不许被应用（既有断言）');

    assert.strictEqual(state.applyLogicalFrame({
        fromSeq: 9, toSeq: 10,
        payload: {kind: 'deltas', deltas: [{op: 'session.removed', sessionId: 's1'}]}
    }), false);
    assert.strictEqual(state.stale, true, '连续跳号不改变结论（仍陈旧）');
    assert.strictEqual(state.list().length, 1, '仍然不许应用');

    assert.strictEqual(state.applyWireFrame(snapshotWire([
        {sessionId: 's1', phase: 'completed'}
    ], 11)), true);
    assert.strictEqual(state.stale, false, '整窗快照把它拉回 ⇒ 解除');
    assert.strictEqual(state.gapFromSeq, null);
});

test('被动：缺口把该工作区标为陈旧，别的工作区不受牵连，日志只记一行', () => {
    const logs = [];
    const {client} = makeClient({log: (message) => logs.push(message)});
    const updates = [];
    client.onSessions = (update) => updates.push(update);
    pageFollows(client, 'page-bridge-a', 11, 3, {workspacePath: '/repo/a', workspaceIdentity: 'ws-a'});
    pageFollows(client, 'page-bridge-b', 12, 4, {workspacePath: '/repo/b', workspaceIdentity: 'ws-b'});

    pagePushes(client, 'page-bridge-a', 21, 3,
        snapshotWire([{sessionId: 's1', phase: 'running'}], 5, 'sessions-index/ws-a'));
    pagePushes(client, 'page-bridge-b', 22, 4,
        snapshotWire([{sessionId: 's9', phase: 'running'}], 1, 'sessions-index/ws-b'));
    assert.deepStrictEqual(updates.map((u) => u.key), ['ws-a', 'ws-b']);
    assert.strictEqual(client._passive['ws-a'].state.stale, false);

    pagePushes(client, 'page-bridge-a', 23, 3, gapDelta('sessions-index/ws-a', 4, 6));
    assert.strictEqual(client._passive['ws-a'].state.stale, true, '缺口 ⇒ 该工作区陈旧');
    assert.strictEqual(client._passive['ws-b'].state.stale, false, '别的工作区不许被牵连');
    // 4c：检出那一拍**额外**发一帧「仅标记」（见下面那条专测）。原来这里断的是
    // `updates.length === 2`（"判失败的那一帧没有新读数，不该上报"）——那句断言在
    // 检出那一拍仍然成立，只是现在多了**一帧不携带读数的标记**。所以拆成两半，比原来更严：
    //   ① 可落地的整表读数仍然只有 2 条（判失败的那一帧一条都没产生）；
    //   ② 多出来的那一条必须是 `staleOnly === true` 的标记帧。
    assert.strictEqual(updates.length, 3, '检出那一拍多出一帧仅标记');
    assert.strictEqual(updates.filter((u) => u.staleOnly !== true).length, 2,
        '判失败的那一帧没有产生任何可落地的读数');
    assert.strictEqual(updates[2].staleOnly, true, '多出来的那一条只能是仅标记帧');
    assert.strictEqual(updates[2].stale, true);

    const gapLogs = logs.filter((m) => m.includes('会话清单序号缺口'));
    assert.strictEqual(gapLogs.length, 1, '跃迁那一拍记一行');
    assert.ok(gapLogs[0].includes('ws-a'), '日志必须写清**哪个工作区**');
    assert.ok(gapLogs[0].includes('fromSeq=4') && gapLogs[0].includes('seq=5'),
        '也要写清**为什么**（缺口事实）');

    pagePushes(client, 'page-bridge-a', 24, 3, gapDelta('sessions-index/ws-a', 7, 8));
    assert.strictEqual(logs.filter((m) => m.includes('会话清单序号缺口')).length, 1,
        '缺口没解除前只记一次，不许逐帧刷屏');
    assert.strictEqual(updates.length, 3, '缺口期间不再有读数（只剩检出那一拍的仅标记帧）');
    assert.strictEqual(updates.filter((u) => u.staleOnly !== true).length, 2);
});

test('被动：下一次整窗快照解除陈旧，上报随之恢复', () => {
    const logs = [];
    const {client} = makeClient({log: (message) => logs.push(message)});
    const updates = [];
    client.onSessions = (update) => updates.push(update);
    pageFollows(client, 'page-bridge-a', 11, 3, {workspacePath: '/repo/a', workspaceIdentity: 'ws-a'});
    pagePushes(client, 'page-bridge-a', 21, 3,
        snapshotWire([{sessionId: 's1', phase: 'running'}], 5, 'sessions-index/ws-a'));
    pagePushes(client, 'page-bridge-a', 22, 3, gapDelta('sessions-index/ws-a', 4, 6));
    assert.strictEqual(client._passive['ws-a'].state.stale, true);

    pagePushes(client, 'page-bridge-a', 23, 3,
        snapshotWire([{sessionId: 's1', phase: 'completed'}], 11, 'sessions-index/ws-a'));
    assert.strictEqual(client._passive['ws-a'].state.stale, false, '快照 ⇒ 陈旧解除');
    assert.strictEqual(updates.length, 3, '解除之后照常上报（快照那一帧）');
    assert.strictEqual(updates[2].staleOnly, false, '自愈那一帧是普通整表帧（列表可落地）');
    assert.strictEqual(updates[2].stale, false);
    assert.deepStrictEqual(updates[2].sessions.map((s) => s.phase), ['completed']);
    assert.ok(logs.some((m) => m.includes('会话清单已自愈') && m.includes('ws-a')),
        '自愈也要留一行，否则真机上分不清"自愈了"与"一直陈旧"');
    assert.strictEqual(logs.filter((m) => m.includes('会话清单序号缺口')).length, 1);
});

test('被动：跃迁语义——应用成功但 stale 未解除不许记「已自愈」，且两个方向各恰好一次', () => {
    const logs = [];
    const {client} = makeClient({log: (message) => logs.push(message)});
    const updates = [];
    client.onSessions = (update) => updates.push(update);
    const healed = () => logs.filter((m) => m.includes('会话清单已自愈')).length;
    const gap = () => logs.filter((m) => m.includes('会话清单序号缺口')).length;

    pageFollows(client, 'page-bridge-a', 11, 3, {workspacePath: '/repo/a', workspaceIdentity: 'ws-a'});
    pagePushes(client, 'page-bridge-a', 21, 3,
        snapshotWire([{sessionId: 's1', phase: 'running'}], 5, 'sessions-index/ws-a'));

    // ── (3) 缺口本身 ⟹ 恰好一次（对称的那条判据）─────────────────────────────
    pagePushes(client, 'page-bridge-a', 22, 3, gapDelta('sessions-index/ws-a', 4, 6));
    assert.strictEqual(client._passive['ws-a'].state.stale, true);
    assert.strictEqual(gap(), 1, '检出那一拍记一行');
    pagePushes(client, 'page-bridge-a', 23, 3, gapDelta('sessions-index/ws-a', 7, 8));
    assert.strictEqual(gap(), 1, '缺口没解除前不再补记（逐帧刷屏的对称面）');

    // ── (1) 应用成功、但 stale 仍为真 ⟹ **不**记日志 ──────────────────────────
    // 构造：缺口期间 state.seq 有意冻结在 5（见 SessionsIndexState.applyLogicalFrame：
    // 判失败那一支"注意 seq 有意不动"），喂一帧 fromSeq === state.seq 的**增量** ⟹
    // 序号对得上 ⟹ 应用成功（返回 true）、锚点推进；而 stale 只有整窗快照能清 ⟹ 仍为 true。
    // 这正是 2026-09-18 20:37–20:41 真机上 4 分钟刷出数百条日志的那类帧。
    pagePushes(client, 'page-bridge-a', 24, 3, {
        topic: 'sessions-index/ws-a', kind: 'complete',
        frame: {fromSeq: 5, toSeq: 6, payload: {kind: 'deltas', deltas: [
            {op: 'session.upserted', session: {sessionId: 's2', phase: 'running'}}]}}
    });
    assert.strictEqual(client._passive['ws-a'].state.stale, true,
        'stale 未被解除（整窗快照才是唯一解除条件）');
    assert.strictEqual(client._passive['ws-a'].state.seq, 6,
        '这一帧确实应用成功了（锚点推进）——"应用成功"与"陈旧解除"是两件事');
    assert.strictEqual(healed(), 0,
        '⚠️ 缺陷 B 的钉子：判据必须是**真跃迁**（改前为真 **且** 改后为假）。' +
        '只看改前的旧写法在这里会记 1 条，并逐帧刷屏');
    // 这一帧走的是**整表帧**分支（_emitSessions）⟹ 顺带钉住第三种帧形状。
    assert.strictEqual(updates.length, 3, '缺口那一拍 +1（仅标记），本帧 +1（整表）');
    assert.deepStrictEqual(
        {stale: updates[2].stale, staleOnly: updates[2].staleOnly},
        {stale: true, staleOnly: false},
        '「stale:true 且 staleOnly:false」的整表帧：列表可落地但读数陈旧——' +
        '今天没有任何测试钉过这一格');

    // ── (2) 整窗快照 ⟹ **恰好**记一次 ─────────────────────────────────────────
    pagePushes(client, 'page-bridge-a', 25, 3,
        snapshotWire([{sessionId: 's1', phase: 'completed'}], 11, 'sessions-index/ws-a'));
    assert.strictEqual(healed(), 1, '跃迁那一拍恰好一次');
    assert.strictEqual(client._passive['ws-a'].state.stale, false);
    pagePushes(client, 'page-bridge-a', 26, 3,
        snapshotWire([{sessionId: 's1', phase: 'completed'}], 12, 'sessions-index/ws-a'));
    assert.strictEqual(healed(), 1, '已不陈旧的快照不产生跃迁，不许再记');
});

// ---------------------------------------------------------------------------
// 4c：检出那一拍的「仅标记帧」（Q13/Q15 的原生入口）
//
// 断的是**上报形状**，不是原生行为——原生那一半（`TaskStore.staleSince` + 可信窗口）
// 由 `NotifyStateTest` 的 4c 组钉住。这里只回答三个问题：
//   ① 缺口检出那一拍**恰好**多一帧（跃迁一次，不逐帧、不重复）；
//   ② 那一帧**显式**区分于整表帧（`staleOnly`），原生不必猜；
//   ③ 它带的 `sessions` 是**冻结的旧读数**（与上一帧逐字段相同）——正因为它不是新读数，
//      原生才必须"只取标记、不落地列表"，否则会重盖 SI 时刻戳、自己制造一次降级。
// ---------------------------------------------------------------------------

test('被动：检出缺口那一拍额外发一帧「仅标记」，列表是冻结旧读数', () => {
    const {client} = makeClient();
    const updates = [];
    client.onSessions = (update) => updates.push(update);
    pageFollows(client, 'page-bridge-a', 11, 3, {workspacePath: '/repo/a', workspaceIdentity: 'ws-a'});
    pagePushes(client, 'page-bridge-a', 21, 3,
        snapshotWire([{sessionId: 's1', phase: 'running'}], 5, 'sessions-index/ws-a'));

    // 整表帧：老字段一个都不少，新字段显式说"不陈旧、可落地"。
    assert.strictEqual(updates.length, 1);
    assert.strictEqual(updates[0].stale, false, '整表帧显式声明不陈旧');
    assert.strictEqual(updates[0].staleOnly, false, '整表帧的列表可以落地');
    assert.strictEqual(updates[0].source, 'passive');
    assert.strictEqual(updates[0].workspaceIdentity, 'ws-a');
    assert.ok(Array.isArray(updates[0].sessions), 'sessions 仍在（老字段不改名、不删）');
    const frozen = JSON.parse(JSON.stringify(updates[0]));
    delete frozen.stale;
    delete frozen.staleOnly;

    pagePushes(client, 'page-bridge-a', 22, 3, gapDelta('sessions-index/ws-a', 4, 6));
    assert.strictEqual(updates.length, 2, '检出那一拍恰好补一帧');
    const marker = updates[1];
    assert.strictEqual(marker.key, 'ws-a');
    assert.strictEqual(marker.stale, true, '标记该工作区的读数从此不可信');
    assert.strictEqual(marker.staleOnly, true, '显式区分"仅标记帧"与"普通整表帧"，别让原生猜');
    const withoutFlags = JSON.parse(JSON.stringify(marker));
    delete withoutFlags.stale;
    delete withoutFlags.staleOnly;
    assert.deepStrictEqual(withoutFlags, frozen,
        '仅标记帧的形状与整表帧逐字段相同（只有两个新标记不同），且列表就是冻结的旧读数');

    // 跃迁一次：缺口没解除之前不再补发。
    pagePushes(client, 'page-bridge-a', 23, 3, gapDelta('sessions-index/ws-a', 7, 8));
    pagePushes(client, 'page-bridge-a', 24, 3, gapDelta('sessions-index/ws-a', 9, 10));
    assert.strictEqual(updates.length, 2, '检出跃迁恰好一次');
    assert.strictEqual(updates.filter((u) => u.staleOnly === true).length, 1);

    // 自愈之后：又是一帧普通整表帧，且带回真正的新读数。
    pagePushes(client, 'page-bridge-a', 25, 3,
        snapshotWire([{sessionId: 's1', phase: 'completed'}], 11, 'sessions-index/ws-a'));
    assert.strictEqual(updates.length, 3);
    assert.strictEqual(updates[2].stale, false);
    assert.strictEqual(updates[2].staleOnly, false);
    assert.deepStrictEqual(updates[2].sessions.map((s) => s.phase), ['completed']);
});

// ---------------------------------------------------------------------------
// RemoteClient — relay reconnect / page-RPC tracing
//
// These pin the two things the first field log could not answer: why the shell
// kept re-opening a bridge for the workspace the page was already showing (a
// relay reconnect used to wipe that knowledge), and what the page itself asks
// the desktop for when a task is opened.
// ---------------------------------------------------------------------------

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

test('pageBridgeSessionIds 反查页面桥 id：两条活入口都能建表，且不静默丢项', () => {
    // 4c（2026-09-18）改写：原用例用 `start()` 造一座"壳自己的桥"，来验证
    // "不许把自己的桥交出去"这一半。开桥路已删 ⟹ 只读壳下**不存在**壳自己的桥，
    // 那条断言的**构造手段**没有了（不是断言错了）。改由**两条活入口**建表
    // （这正是 inject.js 的 degrade_test 真正依赖的性质），并把
    // "交出去的东西一项不漏"继续钉住。
    const {client} = makeClient();

    // 活入口 ①：入站 `workspace-bridge-ready`（acceptObservedPayload 直接写 `_bridgeWorkspace`）。
    client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-9',
        bridge: {bridgeSessionId: 'page-bridge-9', workspaceKey: '/repo/theirs'}
    }, false);
    assert.deepStrictEqual(client.pageBridgeSessionIds(), {'/repo/theirs': 'page-bridge-9'},
        '① 只喂 workspace-bridge-ready，反查表就要建起来');

    // 活入口 ②：页面自己的出站 REQ_EVENT_LISTEN（走 _observeOutboundRpc，
    // 同样写 _bridgeWorkspace）。原用例没覆盖它，而 degrade_test 两条路都吃。
    const {client: isolated} = makeClient();
    const listen = encodeBody(
        [P.REQ_EVENT_LISTEN, 7, 'zcode-agent', P.EVENT_SESSIONS_INDEX],
        {workspacePath: '/repo/pageonly', workspaceIdentity: 'ws-pageonly'}
    );
    for (const payload of fragment(listen, 'page-bridge-listen', 1)) {
        isolated.acceptObservedPayload(payload, true);
    }
    assert.deepStrictEqual(isolated.pageBridgeSessionIds(), {'ws-pageonly': 'page-bridge-listen'},
        '② 只喂一条出站 listen，反查表也要建起来');

    // 页面桥**换 id**（同一工作区又来一条 ready）⟹ 反查表跟到"最后一次被观察到"的那个。
    client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-10',
        bridge: {bridgeSessionId: 'page-bridge-10', workspaceKey: '/repo/theirs'}
    }, false);
    assert.deepStrictEqual(client.pageBridgeSessionIds(), {'/repo/theirs': 'page-bridge-10'},
        '同一工作区换桥 ⟹ 反查表跟到最新那个 id（degrade_test 打的就是它）');

    // 替代守卫（原②的位置）：反查表必须覆盖 `_bridgeWorkspace` 里的每一个工作区，
    // 不再因为"要跳过壳自己的桥"而静默丢项。
    assert.deepStrictEqual(
        Object.keys(client.pageBridgeSessionIds()).sort(),
        Array.from(new Set(Object.values(client._bridgeWorkspace))).sort(),
        '反查表是 _bridgeWorkspace 的满射：一项都不许静默丢掉'
    );
});

test('共享态必须被「附着」而非「读取复制」（attached, not read）', () => {
    // 4c（2026-09-18）改写：原用例拿 `_pageOwned` 当载体，那台机器已随桥路由子系统
    // 删除。但**要保住的回归点一个字没变**：构造函数必须"附着"调用方给的共享 map，
    // 而不是 `x = shared.x || {}` ——后者会在调用方对象暂时没有该键时**悄悄给出一个
    // 私有 map**，于是知识随客户端一起死（真机上发生过；单测因为总是显式建好
    // state 对象而完全看不出来）。载体换成**仍活着**的两张表：
    // `_bridgeWorkspace` 与 `_passive`（两者都在 sharedState() 的返回里）。

    // ① 传入**不带任何键**的空对象：这两张表必须与调用方对象是**同一个引用**。
    //    （这正是原事故的精确形态，原用例没有直接钉。）
    const bare = {};
    const a = makeClient({sharedState: bare});
    assert.strictEqual(a.client._bridgeWorkspace, bare.bridgeWorkspace,
        '空对象传入时也必须附着：client._bridgeWorkspace === shared.bridgeWorkspace');
    assert.strictEqual(a.client._passive, bare.passive,
        '空对象传入时也必须附着：client._passive === shared.passive');

    // ② 用**活入口**往这两张表里写东西，再让第二个客户端接同一份共享态。
    const shared = {};
    const first = makeClient({sharedState: shared});
    first.client.acceptObservedPayload({
        zcode_type: 'workspace-bridge-ready',
        bridgeSessionId: 'page-bridge-1',
        bridge: {bridgeSessionId: 'page-bridge-1', workspaceKey: 'ws-page'}
    }, false);
    pageFollows(first.client, 'page-bridge-1', 11, 3,
        {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'});
    assert.strictEqual(first.client._bridgeWorkspace['page-bridge-1'], 'ws-page');
    assert.ok(first.client._passive['ws-page'], '活入口②建起了 _passive 项');

    const second = makeClient({sharedState: first.client.sharedState()});
    assert.strictEqual(second.client._bridgeWorkspace['page-bridge-1'], 'ws-page',
        '共享的 _bridgeWorkspace 跨客户端可见');
    assert.ok(second.client._passive['ws-page'], '共享的 _passive 跨客户端可见');
    // 比原用例更严：不只是"值相等"，而是**同一个 map 对象**。
    assert.strictEqual(second.client._bridgeWorkspace, first.client._bridgeWorkspace,
        'attached, not read —— 两个客户端必须共用同一个 map 对象');
    assert.strictEqual(second.client._passive, first.client._passive,
        'attached, not read —— _passive 同理');
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

test('lastPageBridgeTrafficAt：页面桥入站 rpc-frame 盖章，出站不盖', () => {
    const {client} = makeClient();
    assert.strictEqual(client.lastPageBridgeTrafficAt(), 0, 'nothing seen yet');

    // 4c（2026-09-18）改写：这里原来先往 `_bridgesById` 里塞一条假桥，验证
    // "我方桥的入站帧不盖章"。那张表已随桥路由子系统删除 ⟹ 只读壳下**不存在**
    // "壳自己的桥"，那个守卫**无可守对象**（与 CHANGE-PLAN §七「4b 退役
    // liveTaskSink 那一臂」同型：闸刀与它守护的机器一并删除）。
    // 原②的语义因此**不是丢了，是没有对象了**；替代断言是它的可观察后果——
    // 入站帧一律盖章，不再按 bridgeSessionId 区分是谁的桥。
    const unseenBody = encodeBody([P.RES_PROMISE_SUCCESS, 41], {ok: true});
    for (const payload of fragment(unseenBody, 'never-observed-bridge', 1)) {
        client.acceptObservedPayload(payload, false);
    }
    assert.ok(client.lastPageBridgeTrafficAt() > 0,
        '任何 bridgeSessionId 的入站 rpc-frame 都盖章（不再有"我方桥"这一格）');

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

// ---------------------------------------------------------------------------
// 4c（2026-09-18）：这里原来还有一组「对话流的看门狗与主动重锚」测试（8 项）
// 与它们的两个夹具（fakeConversationBridge / fakeConversationSub）。它们钉的是
// **壳自己订阅对话流**那一簇（_subscribeConversationsBeforeIndex →
// _subscribeOneConversation → _acceptConversationFrame → _startConversationWatchdog
// → _conversationWatchdogTick → _resyncConversation）：该簇自 2026-09-15 起被
// `CONVERSATION_SUBSCRIBE_ENABLED = false` 封死，2026-09-18 连同它被删。
// 「页面的流照旧、我们从入站帧里读正文」这一半仍然活着，覆盖在下面的
// 「对话流 → 卡片正文」组里（_trackConversationText / latestConversationText）。
// ---------------------------------------------------------------------------

test('conversationFrameStats 报出"最后一帧距今多久"（承载提前接管的判据）', () => {
    const {client} = makeClient();
    // 一帧都没见过 ⇒ −1（原生据此判"页面没在跟任何会话"）。
    assert.strictEqual(
        client.conversationFrameStats().lastFrameAgoMs,
        -1,
        '没见过会话帧时必须报 -1，不能报 0（0 会被误读成"刚刚还在收"）',
    );
    // 4c（2026-09-18）改写：这条测试**守的是活函数**（`inject.js` 的 reportLiveness
    // → ShellRuntime 的"提前接管"判据），所以只能换**构造手段**、不能删。
    // 原来借 `_acceptConversationFrame` 喂一帧——那个函数随对话自订阅簇删了。
    // 换成同样活着的入站入口 `_trackConversationText`（`_observeInboundRpc` 在被动路
    // 上就是这么调它的），它才是 `_convFrames`/`_convLastFrameAt` 的**唯一活写点**。
    // 连带：`subs`/`resyncs` 随订阅簇恒为 0，但**字段必须还在**——
    // inject.js 的「页面开销」行会读它们，字段缺失会在真机日志里打印 undefined。
    const stats = client.conversationFrameStats();
    assert.strictEqual(stats.subs, 0, '订阅簇已删 ⟹ subs 恒 0（字段仍在）');
    assert.strictEqual(stats.resyncs, 0, '订阅簇已删 ⟹ resyncs 恒 0（字段仍在）');
    client._trackConversationText(convSnapshot('conversation/sess_1', [
        {kind: 'assistantText', rowId: 1, text: '正文'}
    ]));
    const after = client.conversationFrameStats();
    const ago = after.lastFrameAgoMs;
    assert.ok(typeof ago === 'number' && ago >= 0 && ago < 5_000, `刚收到的帧年龄应接近 0，实际 ${ago}`);
    assert.strictEqual(after.frames, 1, '帧计数独立于"有没有解出正文"取');
});

