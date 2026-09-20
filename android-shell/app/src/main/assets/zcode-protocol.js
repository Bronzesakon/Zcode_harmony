/**
 * zcode-protocol.js — wire-level client for the ZCode remote relay protocol.
 *
 * WHY THIS EXISTS
 * The shell is a WebView around the real web client at zcode.z.ai/remote/v4.
 * That page already holds an authenticated relay socket; we ride it instead of
 * logging in ourselves. To read task state we must speak the same protocol the
 * page speaks, so this file implements the wire format:
 *
 *   relay frame      {type:'data', payload:<business payload>, client_ts}
 *   business payload  {zcode_type:'workspace-list-request' | 'rpc-frame' | ...}
 *   rpc-frame         base64 chunk of a logical message + crc32 + fragmentation
 *   ChannelClient     value-codec stream: [reqType, reqId, channel, name] + args
 *
 * The essential discovery (from the reference client) is that on the workspace
 * bridge path each reassembled rpc-frame message IS one ChannelClient body —
 * there is no extra IPC header layer. Value encoding is the only codec needed.
 *
 * Two consumers:
 *   * active  — we open our own workspace bridges and subscribe, which is what
 *               lets us notify for workspaces the page is NOT showing (D7).
 *   * passive — we decode the page's own traffic and read its sessions-index,
 *               which covers the workspace the user is looking at at zero cost
 *               and no protocol writes at all.
 *
 * No DOM and no browser API beyond TextEncoder/atob — the file is loaded as a
 * document-start script in the WebView AND required directly by the Node unit
 * tests in tools/, so every byte-level decision here is testable offline.
 */
(function (root, factory) {
    'use strict';
    var api = factory();
    if (typeof module === 'object' && module && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.ZcodeProtocol = api;
    }
})(typeof globalThis !== 'undefined' ? globalThis : null, function () {
    'use strict';

    // -----------------------------------------------------------------------
    // Limits (mirrors the reference transport; exceeding them drops a frame
    // rather than growing memory without bound)
    // -----------------------------------------------------------------------
    var MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    // 物理分片上限 512 KiB → 1 MiB、逻辑分片 64 → 1024：与 Kotlin 侧（RelayWire）**对齐**。
    //
    // 为什么必须抬：对话流的 snapshot/增量是**大帧**，而 controller/* 与 sessions-index
    // 的头是小帧。真机 2026-09-15 的入站 topic 直方图只出现
    // `controller/workspaces` / `controller/tasks-index` / `sessions-index/…`，
    // **从来没有 `conversation/*`**——直方图在分片重组之后，所以大帧被这道上限丢掉时
    // 它是看不见的。Kotlin 侧早就因为同一类问题抬过一次（512 KiB→1 MiB、64→1024），
    // JS 侧一直留着旧值。
    var MAX_FRAGMENT_BYTES = 1024 * 1024;
    var MAX_FRAGMENTS = 1024;
    var MAX_LOGICAL_FRAGMENTS = 1024;
    var MAX_CONTAINER_ITEMS = 100000;
    var MAX_VALUE_BYTES = 16 * 1024 * 1024;

    // ChannelClient request/response type tags.
    var REQ_PROMISE = 100;
    var REQ_PROMISE_CANCEL = 101;
    var REQ_EVENT_LISTEN = 102;
    var REQ_EVENT_DISPOSE = 103;
    var RES_INITIALIZE = 200;
    var RES_PROMISE_SUCCESS = 201;
    var RES_PROMISE_ERROR = 202;
    var RES_PROMISE_ERROR_OBJ = 203;
    var RES_EVENT_FIRE = 204;

    // Well-known channel + method names.
    var CHANNEL_CONVERSATION = 'zcode-agent';
    var EVENT_SESSIONS_INDEX = 'onDynamicSessionsIndexFrame';
    var METHOD_SUBSCRIBE_SI = 'subscribeSessionsIndexV4';
    var METHOD_UNSUBSCRIBE_SI = 'unsubscribeSessionsIndexV4';
    var METHOD_RESYNC_SI = 'resyncSessionsIndexV4';

    // 对话流（流体云卡片跟手的唯一数据源）。
    //
    // **顺序是语义**：`subscribeConversationV4` 必须发在 `subscribeSessionsIndexV4` 之前
    // ——同一座桥上索引先上去之后，对话订阅永远不回包（真机 20:0x：每 36s 重试、连续
    // 12 分钟全超时；18:35 成功那次纯属轮询线程抢到了正确顺序）。Kotlin Tier2 侧也是
    // 同一个结论，所以这里照抄它的做法：握手之后**先订对话，再订索引**。
    /**
     * 运行态整表（页面自己订的那条 controller 流，见 `_forwardControllerTasks`）。
     * 这是"哪些任务在跑"的**全局**正源：`sessions-index` 只跟随页面在听的那一个工作区。
     */
    var CONTROLLER_TASKS_TOPIC = 'controller/tasks-index';


    // Page-RPC tracing budgets (see RemoteClient.prototype._tracePageCall).
    // A page call slower than this is what "opening a task takes forever" looks
    // like from the wire, so it earns its own line; the rest is summarised.
    var PAGE_RPC_SLOW_MS = 1000;
    var PAGE_RPC_SLOW_LOG_MAX = 20;
    var PAGE_RPC_METHODS_MAX = 6;
    // A page call whose reply never comes (abandoned, or a long-lived stream)
    // would otherwise pin `inFlightPageRpcs()` above zero forever — which now
    // also means "the handshake burst always waits out its cap" — and grow the
    // pending map without bound.
    var PAGE_RPC_PENDING_MAX = 200;
    // A pending call older than this is reported once as "无回包"（桌面端从未
    // 回答）——「迟迟不出内容」在日志里唯一的形状：完成/失败都有行，沉默没有。
    // 上限 30s：桌面端 burst 高峰的单调用时延实测可到 10-20s（docs/05 审计），
    // 阈值低于它会误报。报告后从 pending 摘除，不占用下一窗口。
    var PAGE_RPC_SILENCE_MS = 30000;



    // V4 capabilities (notably sessions-index) are gated on the desktop's
    // protocol version negotiation: a 0.x value here silently disables them.
    // The page's own clientHello is observed at runtime and preferred over
    // this fallback, so a desktop-side bump does not need a shell release.
    var DEFAULT_CLIENT_HELLO = {
        protocolVersion: 3,
        appVersion: '3.6.5',
        clientKind: 'mobileApp'
    };

    // -----------------------------------------------------------------------
    // bytes
    // -----------------------------------------------------------------------
    var _encoder = new TextEncoder();
    var _decoder = new TextDecoder('utf-8');

    function utf8Encode(str) {
        return _encoder.encode(str);
    }

    function utf8Decode(bytes) {
        return _decoder.decode(bytes);
    }

    function base64Encode(bytes) {
        var out = '';
        // Chunked so a 512 KiB fragment never overflows the argument limit of
        // String.fromCharCode.apply.
        for (var i = 0; i < bytes.length; i += 0x8000) {
            out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(out);
    }

    function base64Decode(str) {
        var bin = atob(str);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) {
            out[i] = bin.charCodeAt(i);
        }
        return out;
    }

    function concatBytes(list) {
        var total = 0;
        for (var i = 0; i < list.length; i++) {
            total += list[i].length;
        }
        var out = new Uint8Array(total);
        var offset = 0;
        for (var j = 0; j < list.length; j++) {
            out.set(list[j], offset);
            offset += list[j].length;
        }
        return out;
    }

    function randomId(prefix) {
        var rnd = Math.floor(Math.random() * 0x7FFFFFFF).toString(36);
        return prefix + '-' + Date.now().toString(36) + '-' + rnd;
    }

    // -----------------------------------------------------------------------
    // crc32 (IEEE 802.3 — same table semantics as the web client's checksum)
    // -----------------------------------------------------------------------
    var CRC_TABLE = (function () {
        var table = new Int32Array(256);
        for (var i = 0; i < 256; i++) {
            var c = i;
            for (var k = 0; k < 8; k++) {
                c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        return table;
    })();

    function crc32(bytes) {
        var crc = -1;
        for (var i = 0; i < bytes.length; i++) {
            crc = CRC_TABLE[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
        }
        return (crc ^ -1) >>> 0;
    }

    function crc32Hex(bytes) {
        return ('00000000' + crc32(bytes).toString(16)).slice(-8);
    }

    // -----------------------------------------------------------------------
    // value codec — 7-bit little-endian varints, one type tag byte
    //   0 null | 1 string | 2/3 bytes | 4 array | 5 JSON | 6 int
    // -----------------------------------------------------------------------
    function ByteWriter() {
        this._buf = new Uint8Array(256);
        this._len = 0;
    }

    ByteWriter.prototype._ensure = function (extra) {
        if (this._len + extra <= this._buf.length) {
            return;
        }
        var size = this._buf.length * 2;
        while (size < this._len + extra) {
            size *= 2;
        }
        var next = new Uint8Array(size);
        next.set(this._buf.subarray(0, this._len));
        this._buf = next;
    };

    ByteWriter.prototype.byte = function (value) {
        this._ensure(1);
        this._buf[this._len++] = value & 0xFF;
        return this;
    };

    ByteWriter.prototype.varint = function (value) {
        this._ensure(5);
        var v = value >>> 0;
        do {
            var b = v & 0x7F;
            v >>>= 7;
            if (v > 0) {
                b |= 0x80;
            }
            this._buf[this._len++] = b;
        } while (v > 0);
        return this;
    };

    ByteWriter.prototype.bytes = function (arr) {
        this._ensure(arr.length);
        this._buf.set(arr, this._len);
        this._len += arr.length;
        return this;
    };

    ByteWriter.prototype.toBytes = function () {
        return this._buf.slice(0, this._len);
    };

    function ByteReader(data) {
        this.data = data;
        this.pos = 0;
    }

    Object.defineProperty(ByteReader.prototype, 'remaining', {
        get: function () {
            return this.data.length - this.pos;
        }
    });

    ByteReader.prototype.byte = function () {
        if (this.pos >= this.data.length) {
            throw new Error('ByteReader: out of data');
        }
        return this.data[this.pos++];
    };

    ByteReader.prototype.varint = function () {
        var value = 0;
        var shift = 0;
        while (this.pos < this.data.length) {
            var b = this.data[this.pos++];
            if (shift === 28 && (b & 0xF0) !== 0) {
                throw new Error('ByteReader: varint overflow');
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) === 0) {
                return value >>> 0;
            }
            shift += 7;
            if (shift > 28) {
                throw new Error('ByteReader: varint overflow');
            }
        }
        throw new Error('ByteReader: truncated varint');
    };

    ByteReader.prototype.bytes = function (n) {
        if (this.pos + n > this.data.length) {
            throw new Error('ByteReader: cannot read ' + n + ' bytes');
        }
        var out = this.data.subarray(this.pos, this.pos + n);
        this.pos += n;
        return out;
    };

    function encodeValue(writer, value) {
        if (value === null || value === undefined) {
            writer.byte(0);
            return;
        }
        if (typeof value === 'string') {
            var str = utf8Encode(value);
            writer.byte(1).varint(str.length).bytes(str);
            return;
        }
        if (value instanceof Uint8Array) {
            writer.byte(3).varint(value.length).bytes(value);
            return;
        }
        if (Array.isArray(value)) {
            writer.byte(4).varint(value.length);
            for (var i = 0; i < value.length; i++) {
                encodeValue(writer, value[i]);
            }
            return;
        }
        if (typeof value === 'number' && isFinite(value) &&
            Math.floor(value) === value && value >= 0 && value <= 0x7FFFFFFF) {
            writer.byte(6).varint(value);
            return;
        }
        // Everything else (booleans, floats, plain objects) travels as JSON,
        // which is what the reference encoder does for any non-int scalar.
        var json = utf8Encode(JSON.stringify(value));
        writer.byte(5).varint(json.length).bytes(json);
    }

    function decodeValue(reader) {
        var tag = reader.byte();
        switch (tag) {
            case 0:
                return null;
            case 1: {
                var len = reader.varint();
                if (len > MAX_VALUE_BYTES) {
                    throw new Error('value: string too large');
                }
                return utf8Decode(reader.bytes(len));
            }
            case 2:
            case 3: {
                var n = reader.varint();
                if (n > MAX_VALUE_BYTES) {
                    throw new Error('value: bytes too large');
                }
                return reader.bytes(n);
            }
            case 4: {
                var count = reader.varint();
                if (count > MAX_CONTAINER_ITEMS) {
                    throw new Error('value: array too large');
                }
                var arr = new Array(count);
                for (var i = 0; i < count; i++) {
                    arr[i] = decodeValue(reader);
                }
                return arr;
            }
            case 5: {
                var jlen = reader.varint();
                if (jlen > MAX_VALUE_BYTES) {
                    throw new Error('value: object too large');
                }
                return JSON.parse(utf8Decode(reader.bytes(jlen)));
            }
            case 6:
                return reader.varint();
            default:
                throw new Error('value: unknown tag ' + tag);
        }
    }

    // -----------------------------------------------------------------------
    // rpc-frame: outbound fragmentation
    //
    // ⚠️ 4c（2026-09-18）：本类与紧随其后的 `RpcFrameAssembler`（入站重组）、
    // `ChannelClient`（请求-应答配对）已**不在生产路径**上——它们的调用者是"主动开桥"
    // 那条路，而只读壳下壳自己**永不开桥**（写 `_bridges` 的只有 `openBridge`，
    // 其驱动 `start()` 已于 2026-09-17 / D7 删除）。
    //
    // **保留它们是刻意的**（用户 2026-09-18 裁定；"删净 / 保留 / 折中"三选一里选了保留）：
    //   * 它们是**线格式的参照实现**——帧信封、分片、ack 长什么样，以此为准；
    //   * 另有 **7 项协议测试**压在它们身上，那是仓里唯一记录"线上帧长什么样"的东西。
    //
    // ⚠️ **欠账**：生产里真正在跑的重组器是 `RemoteClient._tryAssemble`，
    // 它**今天仍然零单测**。将来若要动重组逻辑，先想到这一点。
    // -----------------------------------------------------------------------
    function RpcFrameSender(options) {
        this.bridgeSessionId = options.bridgeSessionId;
        this.bridgeGeneration = options.bridgeGeneration;
        this.recoveryId = options.recoveryId;
        this.sendPayload = options.sendPayload;
        this._seq = 0;
        this._messageSeq = 0;
    }

    RpcFrameSender.prototype.sendMessage = function (bytes) {
        if (!bytes.length) {
            throw new Error('rpcFrame: empty message');
        }
        if (bytes.length > MAX_MESSAGE_BYTES) {
            throw new Error('rpcFrame: message too large');
        }
        var messageSeq = ++this._messageSeq;
        var checksum = crc32Hex(bytes);
        var fragmentCount = Math.ceil(bytes.length / MAX_FRAGMENT_BYTES);
        if (fragmentCount > MAX_FRAGMENTS) {
            throw new Error('rpcFrame: too many fragments');
        }
        for (var i = 0; i < fragmentCount; i++) {
            var start = i * MAX_FRAGMENT_BYTES;
            var end = Math.min(start + MAX_FRAGMENT_BYTES, bytes.length);
            this._seq += 1;
            var payload = {
                zcode_type: 'rpc-frame',
                bridgeSessionId: this.bridgeSessionId,
                seq: this._seq,
                messageSeq: messageSeq,
                fragmentIndex: i,
                fragmentCount: fragmentCount,
                messageBytes: bytes.length,
                checksum: { algorithm: 'crc32', value: checksum },
                dataBase64: base64Encode(bytes.subarray(start, end))
            };
            if (this.bridgeGeneration !== undefined && this.bridgeGeneration !== null) {
                payload.bridgeGeneration = this.bridgeGeneration;
            }
            if (this.recoveryId) {
                payload.recoveryId = this.recoveryId;
            }
            this.sendPayload(payload);
        }
    };

    // -----------------------------------------------------------------------
    // rpc-frame: inbound reassembly (+ ack)
    // -----------------------------------------------------------------------
    function RpcFrameAssembler(options) {
        this.bridgeSessionId = options.bridgeSessionId;
        this.onMessage = options.onMessage;
        /** Called with the messageSeq of every fully received message. */
        this.onAck = options.onAck || function () {};
        this.onLog = options.onLog || function () {};
        this._assemblies = {};
    }

    RpcFrameAssembler.prototype.acceptPayload = function (payload) {
        if (!payload || payload.bridgeSessionId !== this.bridgeSessionId) {
            return false;
        }
        var type = payload.zcode_type;
        if (type === 'rpc-frame-ack') {
            return true;
        }
        if (type !== 'rpc-frame') {
            return false;
        }
        var messageSeq = payload.messageSeq;
        var fragmentIndex = payload.fragmentIndex;
        var fragmentCount = payload.fragmentCount;
        var messageBytes = payload.messageBytes;
        var dataBase64 = payload.dataBase64;
        if (typeof messageSeq !== 'number' || typeof fragmentIndex !== 'number' ||
            typeof fragmentCount !== 'number' || typeof messageBytes !== 'number' ||
            typeof dataBase64 !== 'string') {
            return true;
        }
        if (fragmentCount < 1 || fragmentCount > MAX_FRAGMENTS ||
            fragmentIndex < 0 || fragmentIndex >= fragmentCount ||
            messageBytes < 1 || messageBytes > MAX_MESSAGE_BYTES) {
            return true;
        }
        var chunk;
        try {
            chunk = base64Decode(dataBase64);
        } catch (e) {
            return true;
        }
        if (chunk.length > MAX_FRAGMENT_BYTES) {
            return true;
        }
        var checksum = payload.checksum && payload.checksum.value;
        var existing = this._assemblies[messageSeq];
        if (existing &&
            (existing.fragmentCount !== fragmentCount ||
                existing.messageBytes !== messageBytes ||
                existing.checksum !== checksum)) {
            delete this._assemblies[messageSeq];
            return true;
        }
        var assembly = existing || (this._assemblies[messageSeq] = {
            fragmentCount: fragmentCount,
            messageBytes: messageBytes,
            checksum: checksum,
            fragments: new Array(fragmentCount),
            received: 0,
            at: Date.now()
        });
        if (assembly.fragments[fragmentIndex] === undefined) {
            assembly.received += 1;
        }
        assembly.fragments[fragmentIndex] = chunk;
        if (assembly.received !== assembly.fragmentCount) {
            return true;
        }
        delete this._assemblies[messageSeq];
        var message = concatBytes(assembly.fragments);
        if (message.length !== assembly.messageBytes) {
            this.onLog('rpc message ' + messageSeq + ' size mismatch');
            return true;
        }
        if (assembly.checksum && crc32Hex(message) !== assembly.checksum) {
            this.onLog('rpc message ' + messageSeq + ' checksum mismatch');
            return true;
        }
        this.onAck(messageSeq);
        try {
            this.onMessage(message);
        } catch (e) {
            this.onLog('rpc message ' + messageSeq + ' handler failed: ' + e);
        }
        return true;
    };

    /** Drops assemblies that never completed (a fragment was lost). */

    // -----------------------------------------------------------------------
    // ChannelClient — request/response + event listen over a bridge
    // -----------------------------------------------------------------------
    /**
     * `idBase` exists for a specific reason: when we ride the page's own
     * bridge we share the endpoint with the page's ChannelClient, which
     * numbers its requests from 0. Starting ours at a high offset makes an id
     * collision (which would deliver our response to the page's handler, or
     * vice versa) effectively impossible.
     */
    function ChannelClient(options) {
        this.sendBody = options.sendBody;
        this.idBase = options.idBase || 0x100000;
        this.onLog = options.onLog || function () {};
        this._nextId = this.idBase;
        this._pending = {};
        this._listeners = {};
        this._initialized = false;
        this._initializeWaiters = [];
    }

    ChannelClient.prototype._handleInitialize = function () {
        this._initialized = true;
        var waiters = this._initializeWaiters;
        this._initializeWaiters = [];
        for (var i = 0; i < waiters.length; i++) {
            waiters[i]();
        }
    };

    ChannelClient.prototype.whenReady = function (timeoutMs) {
        var self = this;
        if (this._initialized) {
            return Promise.resolve();
        }
        return new Promise(function (resolve, reject) {
            var timer = setTimeout(function () {
                reject(new Error('channel init timeout (no Initialize frame)'));
            }, timeoutMs || 30000);
            self._initializeWaiters.push(function () {
                clearTimeout(timer);
                resolve();
            });
        });
    };

    ChannelClient.prototype.handleMessage = function (bytes) {
        var reader;
        var header;
        try {
            reader = new ByteReader(bytes);
            header = decodeValue(reader);
        } catch (e) {
            this.onLog('ipc: undecodable body: ' + e);
            return;
        }
        if (!Array.isArray(header) || typeof header[0] !== 'number') {
            return;
        }
        var type = header[0];
        if (type === RES_INITIALIZE) {
            this._handleInitialize();
            return;
        }
        if (header.length < 2 || typeof header[1] !== 'number') {
            return;
        }
        var id = header[1];
        var data;
        try {
            data = reader.remaining > 0 ? decodeValue(reader) : null;
        } catch (e) {
            this.onLog('ipc: bad payload for id ' + id + ': ' + e);
            return;
        }
        if (type === RES_EVENT_FIRE) {
            var listener = this._listeners[id];
            if (listener) {
                try {
                    listener(data);
                } catch (e) {
                    this.onLog('ipc: event handler failed: ' + e);
                }
            }
            return;
        }
        var pending = this._pending[id];
        if (!pending) {
            return;
        }
        if (type === RES_PROMISE_SUCCESS) {
            delete this._pending[id];
            pending.resolve(data);
        } else if (type === RES_PROMISE_ERROR) {
            delete this._pending[id];
            var message = (data && typeof data === 'object' && data.message) ?
                data.message : String(data);
            pending.reject(new Error(message));
        } else if (type === RES_PROMISE_ERROR_OBJ) {
            delete this._pending[id];
            pending.reject(new Error(typeof data === 'string' ? data : JSON.stringify(data)));
        }
    };

    ChannelClient.prototype._sendRequest = function (reqType, id, channel, name, arg) {
        var writer = new ByteWriter();
        encodeValue(writer, [reqType, id, channel, name]);
        encodeValue(writer, arg === undefined ? null : arg);
        this.sendBody(writer.toBytes());
    };

    ChannelClient.prototype.call = function (channel, method, args, timeoutMs) {
        var self = this;
        var budget = timeoutMs || 30000;
        return this.whenReady(budget).then(function () {
            return new Promise(function (resolve, reject) {
                var id = self._nextId++;
                var timer = setTimeout(function () {
                    delete self._pending[id];
                    reject(new Error(channel + '.' + method + ' timed out'));
                }, budget);
                self._pending[id] = {
                    resolve: function (value) {
                        clearTimeout(timer);
                        resolve(value);
                    },
                    reject: function (err) {
                        clearTimeout(timer);
                        reject(err);
                    }
                };
                self.onLog('call ' + channel + '.' + method + ' id=' + id);
                self._sendRequest(REQ_PROMISE, id, channel, method, args);
            });
        });
    };

    /**
     * Listens for a channel event. `arg` is the scope object the desktop needs
     * to route the event (e.g. {workspacePath, workspaceIdentity}).
     */
    ChannelClient.prototype.addEventListener = function (channel, event, arg, onEvent) {
        var self = this;
        var id = this._nextId++;
        this._listeners[id] = onEvent;
        this.onLog('listen ' + channel + '.' + event + ' id=' + id);
        // The listen must not be sent before the desktop's Initialize frame;
        // sending early silently loses the event stream.
        this.whenReady(30000).then(function () {
            if (self._listeners[id]) {
                self._sendRequest(REQ_EVENT_LISTEN, id, channel, event,
                    arg === undefined ? null : arg);
            }
        }).catch(function () {
            delete self._listeners[id];
        });
        return {
            id: id,
            dispose: function () {
                if (!self._listeners[id]) {
                    return;
                }
                delete self._listeners[id];
                self._sendRequest(REQ_EVENT_DISPOSE, id, channel, event, null);
            }
        };
    };

    // -----------------------------------------------------------------------
    // sessions-index state (snapshot + delta application)
    // -----------------------------------------------------------------------
    function normalizeSession(raw) {
        var pending = raw.pendingInteraction;
        var interactionId = '';
        if (pending && typeof pending === 'object') {
            interactionId = pending.interactionId === undefined ||
                pending.interactionId === null ? '' : String(pending.interactionId);
        }
        return {
            sessionId: raw.sessionId === undefined || raw.sessionId === null ?
                '' : String(raw.sessionId),
            parentSessionId: raw.parentSessionId === undefined || raw.parentSessionId === null ?
                '' : String(raw.parentSessionId),
            title: raw.title === undefined || raw.title === null ? '' : String(raw.title),
            phase: raw.phase === undefined || raw.phase === null ? '' : String(raw.phase),
            preview: raw.lastAssistantPreview === undefined || raw.lastAssistantPreview === null ?
                '' : String(raw.lastAssistantPreview),
            lastActivityAt: typeof raw.lastActivityAt === 'number' ? raw.lastActivityAt : 0,
            hasBackgroundWork: raw.hasBackgroundWork === true,
            pendingInteractionId: interactionId
        };
    }

    function SessionsIndexState() {
        this.workspaceId = null;
        this.logEpoch = null;
        this.seq = 0;
        this.sessions = {};
        this.ready = false;
        this.needsResync = false;
        /**
         * 本工作区的会话清单是否**因序号缺口而陈旧**（4c 工程前置，2026-09-18）。
         *
         * 与 `needsResync` **不是同一件事**：那个说"该去要一份重同步了"，这个说
         * "这一路读数**不可信**了"——原生侧要拿它决定"要不要把这次读数当成『没有在跑
         * 的任务』"（`ShellRuntime` 的提前接管闸门只问"有没有在跑"，见
         * `TaskStore.runningTaskRefs()`；陈旧期间判成空 = 卡片/接管被误关）。
         *
         * 置位：增量帧跳号（见 applyLogicalFrame）。**只有一处清除**：下一次**成功应用
         * 整窗快照**——快照把整窗拉回，缺口自愈（重订阅与重同步都会带来快照）。
         *
         * ⚠️ 这里有个**已经实测钉住**的反直觉处，接手时别按直觉改：缺口之后本状态机对
         * 每一帧增量都判失败（`seq` 不动），**一帧都不上报**，所以"成功应用"与"缺口自愈"
         * 是同一件事。后果是把 `stale` 挂到 `_emitSessions` 的载荷上，它**永远不会有 true
         * 的那一天**；要让原生知道，得在**检出那一拍**另外发一次上报（`tools/protocol.test.js`
         * 的「被动：缺口把该工作区标为陈旧…」把"缺口期间一帧都不上报"一并钉住了）。
         *
         * 4c（2026-09-18）落地情况：上报那一帧就是 [_emitSessionsStale]（**仅标记帧**，
         * `staleOnly: true`，列表不许落地）；原生的落点是 `TaskStore` 的 `staleSince` /
         * 可信窗口（见该文件的注释），载荷字段的对照表写在 `_emitSessionsStale` 上。
         * 本文件的单测钉住"检出跃迁恰好发一帧、且那一帧带的是冻结旧读数"。
         */
        this.stale = false;
        /** 检出缺口的那一帧的 `fromSeq`：**只给日志用**，不是可用位点。 */
        this.gapFromSeq = null;
        this._fragments = {};
    }

    /** Accepts the wire envelope {topic, kind:'complete'|'fragment', ...}. */
    SessionsIndexState.prototype.applyWireFrame = function (wire) {
        if (!wire || typeof wire !== 'object') {
            return false;
        }
        if (wire.kind === 'complete') {
            return this.applyLogicalFrame(wire.frame);
        }
        if (wire.kind !== 'fragment') {
            return false;
        }
        var id = wire.logicalFrameId;
        var index = wire.fragmentIndex;
        var count = wire.fragmentCount;
        var dataBase64 = wire.dataBase64;
        if (typeof id !== 'string' || typeof index !== 'number' ||
            typeof count !== 'number' || typeof dataBase64 !== 'string') {
            return false;
        }
        if (count < 1 || count > MAX_LOGICAL_FRAGMENTS || index < 0 || index >= count) {
            return false;
        }
        var assembly = this._fragments[id];
        if (!assembly || assembly.count !== count) {
            assembly = {
                count: count,
                parts: new Array(count),
                received: 0,
                at: Date.now()
            };
            this._fragments[id] = assembly;
        }
        if (assembly.parts[index] === undefined) {
            assembly.received += 1;
        }
        try {
            assembly.parts[index] = base64Decode(dataBase64);
        } catch (e) {
            delete this._fragments[id];
            return false;
        }
        if (assembly.received !== count) {
            return false;
        }
        delete this._fragments[id];
        var decoded;
        try {
            decoded = JSON.parse(utf8Decode(concatBytes(assembly.parts)));
        } catch (e) {
            return false;
        }
        return this.applyLogicalFrame(decoded);
    };

    SessionsIndexState.prototype.applyLogicalFrame = function (frame) {
        if (!frame || typeof frame !== 'object') {
            return false;
        }
        var payload = frame.payload;
        if (!payload || typeof payload !== 'object') {
            return false;
        }
        var toSeq = typeof frame.toSeq === 'number' ? frame.toSeq : this.seq;
        if (payload.kind === 'snapshot') {
            var snapshot = payload.snapshot;
            if (!snapshot || typeof snapshot !== 'object') {
                return false;
            }
            this.workspaceId = typeof snapshot.workspaceId === 'string' ? snapshot.workspaceId : null;
            this.logEpoch = typeof snapshot.logEpoch === 'string' ? snapshot.logEpoch : null;
            this.sessions = {};
            var list = Array.isArray(snapshot.sessions) ? snapshot.sessions : [];
            for (var i = 0; i < list.length; i++) {
                var entry = normalizeSession(list[i] || {});
                if (entry.sessionId) {
                    this.sessions[entry.sessionId] = entry;
                }
            }
            this.seq = toSeq;
            // 整窗拉回 ⇒ 缺口自愈。**这是 [stale] 唯一的解除条件**（见构造函数里的注释）。
            this.stale = false;
            this.gapFromSeq = null;
        } else if (payload.kind === 'deltas') {
            var fromSeq = typeof frame.fromSeq === 'number' ? frame.fromSeq : this.seq;
            if (fromSeq !== this.seq) {
                // Lost an update: the caller must resync, otherwise the phase
                // table silently drifts and completion events are missed.
                this.needsResync = true;
                // 4c 工程前置：漏了一帧增量 ⇒ **从这一拍起本工作区的读数不可信**。
                // 注意 `seq` 有意不动：缺口期间后续每一帧都会再判失败一次，直到整窗快照
                // 把它拉回来——"卡在缺口上"是这里的设计，不是漏改。
                this.stale = true;
                this.gapFromSeq = fromSeq;
                return false;
            }
            var deltas = Array.isArray(payload.deltas) ? payload.deltas : [];
            for (var d = 0; d < deltas.length; d++) {
                var delta = deltas[d];
                if (!delta || typeof delta !== 'object') {
                    continue;
                }
                if (delta.op === 'session.upserted' && delta.session) {
                    var upserted = normalizeSession(delta.session);
                    if (upserted.sessionId) {
                        this.sessions[upserted.sessionId] = upserted;
                    }
                } else if (delta.op === 'session.removed') {
                    delete this.sessions[String(delta.sessionId)];
                }
            }
            this.seq = toSeq;
        } else {
            return false;
        }
        this.ready = true;
        return true;
    };

    SessionsIndexState.prototype.list = function () {
        var out = [];
        for (var key in this.sessions) {
            out.push(this.sessions[key]);
        }
        out.sort(function (a, b) {
            return b.lastActivityAt - a.lastActivityAt;
        });
        return out;
    };

    // -----------------------------------------------------------------------
    // workspace helpers (same key/title rules as the reference client)
    // -----------------------------------------------------------------------
    function workspaceKeyOf(workspace) {
        if (!workspace || typeof workspace !== 'object') {
            return null;
        }
        var identity = workspace.workspaceIdentity;
        if (typeof identity === 'string' && identity.trim()) {
            return identity.trim();
        }
        var path = workspace.workspacePath;
        if (typeof path === 'string' && path) {
            return path;
        }
        var fallbacks = ['workspaceKey', 'key', 'id'];
        for (var i = 0; i < fallbacks.length; i++) {
            var value = workspace[fallbacks[i]];
            if (typeof value === 'string' && value) {
                return value;
            }
        }
        return null;
    }

    function workspaceTitle(workspace) {
        if (!workspace || typeof workspace !== 'object') {
            return '未知工作区';
        }
        if (typeof workspace.label === 'string' && workspace.label) {
            return workspace.label;
        }
        var path = workspace.workspacePath;
        if (typeof path === 'string' && path) {
            var parts = path.split(/[\\/]/).filter(function (p) {
                return p.length > 0;
            });
            return parts.length ? parts[parts.length - 1] : path;
        }
        var identity = workspace.workspaceIdentity;
        if (typeof identity === 'string' && identity) {
            return identity;
        }
        return workspaceKeyOf(workspace) || '未知工作区';
    }

    // -----------------------------------------------------------------------
    // RemoteClient — the business layer
    // -----------------------------------------------------------------------
    /**
     * options:
     *   send            function(businessPayload)   -> writes to the relay
     *   log             function(message)
     *   idBase          number (default 0x100000)
     *   maxWorkspaces   number (default 12)
     *
     * 这里**没有**"订阅所有工作区"开关：D7 于 2026-09-17 删除（见 `start()` 上的注释）。
     * 客户端恒为被动旁观——只解析页面的流量，不写页面那条 socket。
     *
     * Events (assign callbacks):
     *   onSessions(update)   {key, title, workspacePath, workspaceIdentity,
     *                         source, sessions:[...], stale, staleOnly}
     *                         `stale`/`staleOnly` 是 4c 新增（老字段一个不改名、不删）：
     *                         整表帧 = `stale:false, staleOnly:false`（列表可落地）；
     *                         检出缺口的**仅标记帧** = `true,true`（只取标记，不落地列表）。
     *                         对照表与理由见 `_emitSessionsStale`。
     *   onStatus(status)     {passive, workspaces, reason}
     *                         ⚠️ `active` 于 2026-09-17（D7）移除、`bridges` 于 2026-09-18（4c）
     *                         随桥路由子系统移除——后者统计"壳自己开着的桥数"，只读壳下恒 0，
     *                         是个**永远报 0 的假指标**，故原生侧也一并停读（`ShellRuntime.onStatus`）。
     *   onPageRpcCall(call)  {name, args} — every promise call the page sends,
     *                        observed outbound. subscribeConversationV4 is the
     *                        "the user just opened a task" beacon (inject.js
     *                        §5b); args carry its sessionId.
     *   onPageRpcResult(r)   {name, ok, cost, message} — every completed page
     *                        promise call. The upload chain's completion/failure
     *                        evidence (inject.js logs upload-named calls).
     *   onPageRpcSilence(r)  {name, ageMs} — a page call that never got ANY
     *                        answer within PAGE_RPC_SILENCE_MS; the only log
     *                        shape "the desktop never replied" can take.
     */
    function RemoteClient(options) {
        this._send = options.send;
        this._log = options.log || function () {};
        this._idBase = options.idBase || 0x100000;
        this._maxWorkspaces = options.maxWorkspaces || 12;
        // ⚠️ 这里**曾经**是 `this.subscribeAll = options.subscribeAll !== false;`——
        // D7「订阅所有工作区」的开关位，2026-09-17 删除。构造参数里再给 subscribeAll
        // 也没有任何效果，客户端不接受"我该主动开桥"这个状态了。

        this.onSessions = null;
        /** 运行态整表（页面自己的 controller 流）→ 原生，纯被动转发。 */
        this.onControllerTasks = null;
        this.onStatus = null;
        this.onPageRpcCall = null;
        this.onPageRpcResult = null;
        this.onPageRpcSilence = null;
        // 页面日志汇（window.zcode.log）接通后，页面自己的 warn 已覆盖大多数
        // 调用失败；镜像行降为冗余，由 inject.js 置 true 关掉（窗口汇总与
        // 沉默线不受影响——它们没有重复来源）。
        this.suppressPageRpcMirror = false;

        // 【4c·2026-09-18 删除】这里原来是壳自己开桥的那套机件：`_pending`
        // （请求-应答配对）、`_bridges`/`_bridgesById`（按工作区/按 bridgeSessionId
        // 两张桥表）、`_activeKeys`、`_pendingBridgePayloads`/`_inflightOpens`
        // （开桥在飞窗口的缓冲）、`_bridgeSeq`/`_bridgeGeneration`/`_clientHello`。
        // 它们**全部**只有一个写点：`openBridge`，而它由注入层永不调用的 `start()`
        // 驱动（只读壳契约；见下面 passive 段的注释）。2026-09-18 随桥路由子系统
        // 一并删除。⚠️ 将来若恢复开桥，这一整套必须一起加回来。

        // Passive side. What the page streams is a fact about the PAGE, not about
        // this client instance, so callers that rebuild the client on a relay
        // reconnect can hand in a state object that outlives it. Losing it was
        // expensive: a fresh client re-opened an active bridge for the very
        // workspace the page was already showing, and the desktop answered with
        // bridge-degraded/rpc-transport-fault in a loop.
        this._shared = options.sharedState || null;
        // Attach, don't just read. `x = shared.x || {}` silently hands the client
        // a private map when the caller's object happens not to carry that key
        // yet — which is exactly what happened to pageBridges/pageOwned: the
        // comment above promised they outlived the client, the code did not
        // deliver it in the real app (the tests passed only because they built
        // the state object explicitly). Mutating the shared object here is what
        // makes the promise true.
        // 4c（2026-09-18）：随桥路由子系统删掉了 pageBridges / pageOwned /
        // faultStreak / cooldownUntil 四项（读者全在已删的 active 路径里）。
        var sharedMaps = ['passive', 'outboundListenIds', 'bridgeWorkspace'];
        if (this._shared) {
            for (var mi = 0; mi < sharedMaps.length; mi += 1) {
                if (!this._shared[sharedMaps[mi]]) {
                    this._shared[sharedMaps[mi]] = {};
                }
            }
        }
        this._passive = (this._shared && this._shared.passive) || {};
        this._outboundListenIds = (this._shared && this._shared.outboundListenIds) || {};
        this._bridgeWorkspace = (this._shared && this._shared.bridgeWorkspace) || {};
        // 【4c·2026-09-18 删除】页面覆盖机件（`_pageBridges`/`_pageCoveredKeys`/
        // `_pageOwned`）、跨连接 fault 冷却（`_faultStreak`/`_cooldownUntil`/
        // `_faultedInConnection`/`_maxReopens`/`_faultCounts`）、"这桥是不是我们
        // 自己要的"（`_requestedBridgeIds`）与 `_started`。
        // 判据：它们要么只被已删的 active/开桥路径读写，要么只写不读（见报告
        // 「删前锚点」表）。⚠️ 恢复开桥时必须整套加回。
        this._lastStatus = null;

        // Page-RPC trace: what the page itself asks the desktop for, and how
        // long the desktop takes to answer. The shell's own bridges say nothing
        // about "opening a task hangs", because the conversation request belongs
        // to the page — this is the only place it can be observed.
        // The threshold is injectable so the Node tests do not have to sleep.
        this._pageRpcSlowMs = typeof options.pageRpcSlowMs === 'number' ?
            options.pageRpcSlowMs : PAGE_RPC_SLOW_MS;
        this._pageBridgeTrafficAt = 0;
        this._pageConversationTrafficAt = 0;
        this._pageRpc = {
            pending: {},
            calls: 0,
            errors: 0,
            slow: 0,
            slowLogged: 0,
            windowCalls: 0,
            windowErrors: 0,
            windowSlow: 0,
            windowMethods: {},
            methodsLogged: 0
        };
    }

    /** True once start() has run and will not run again on its own. */

    /** The state a caller must carry across relay reconnects (see constructor). */
    RemoteClient.prototype.sharedState = function () {
        // 4c（2026-09-18）：键集收窄到**仍活着**的三张表。原来还有
        // pageBridges / pageOwned / faultStreak / cooldownUntil——它们的读写点
        // 全在已删的 active 开桥路径里，留着就是"只写不读的摆设"。
        return {
            passive: this._passive,
            outboundListenIds: this._outboundListenIds,
            bridgeWorkspace: this._bridgeWorkspace
        };
    };

    /** Page bridges' most recent inbound rpc-frame time (0 = none seen this client). */
    RemoteClient.prototype.lastPageBridgeTrafficAt = function () {
        return this._pageBridgeTrafficAt || 0;
    };

    RemoteClient.prototype.lastPageConversationTrafficAt = function () {
        return this._pageConversationTrafficAt || 0;
    };


    RemoteClient.prototype.inFlightPageRpcs = function () {        var count = 0;
        for (var slot in this._pageRpc.pending) {
            count += 1;
        }
        return count;
    };



    /**
     * 页面桥的反查表：workspaceKey → 该页面桥的 bridgeSessionId。
     *
     * 用途：伪造 bridge-degraded 喂回页面（degrade_test / 未来的僵尸订阅自愈）
     * 时要携带页面桥自己的 id——页面侧的匹配是
     * `e.bridgeSessionId === currentBridge.getBridgeSessionId()`，带我们的 id
     * 永远不命中。僵尸态下页面桥对象还活着但不再重建，这里记录的正是它
     * 最后一次被观察到的 id，与页面对象内存中的值一致。
     * 排除我们自己的桥（_bridgesById / _requestedBridgeIds）。
     */
    RemoteClient.prototype.pageBridgeSessionIds = function () {
        var out = {};
        for (var id in this._bridgeWorkspace) {
            // 【只读壳下永不命中，恢复开桥时必须加回】这里原来是
            // `if (this._bridgesById[id] || this._requestedBridgeIds[id]) continue;`
            // ——把壳自己开的桥从反查表里排除掉。两张表在生产里恒空（唯一写点
            // `openBridge` 由注入层永不调用的 `start()` 驱动），所以这个过滤
            // 逐位等价于不存在；2026-09-18（4c）随两张表一并删除。
            // ⚠️ 恢复开桥时必须加回，否则这个反查表会把壳自己的桥当成页面桥交出去，
            // `degrade_test` 会往壳的桥上灌伪造帧。
            out[this._bridgeWorkspace[id]] = id;
        }
        return out;
    };

    RemoteClient.prototype._emitStatus = function (reason) {
        if (!this.onStatus) {
            return;
        }
        // 4c（2026-09-18）：这里原来先数 `bridges`（壳自己开着的桥数）。
        // 只读壳下 `_bridges` 恒空 ⟹ 这个字段**结构性恒 0**，是假指标；
        // 2026-09-18 随桥表一并删除（原生 ShellRuntime 的读数同步删掉）。
        var passive = 0;
        for (var p in this._passive) {
            passive += 1;
        }
        var status = {
            passive: passive,
            reason: reason || ''
        };
        var json = JSON.stringify(status);
        if (json !== this._lastStatus) {
            this._lastStatus = json;
            this.onStatus(status);
        }
    };


    /** Every inbound business payload must be fed here. */

    /** True while a workspace the desktop keeps refusing is being backed off. */








    /**
     * 把页面自己那条 `controller/tasks-index` 的逻辑帧转给原生（纯被动，零写入）。
     *
     * 只做转发、不在这里解析：帧形状（snapshot / deltas / 缺口判定）在 Kotlin 侧
     * `ControllerTasksState` 里已经有一份**带单测**的实现，JS 再写一遍就是第二个真相。
     * 原生按 `{kind:'complete', frame:…}` 的信封收（与它自己的 wire 同构）。
     */
    RemoteClient.prototype._forwardControllerTasks = function (data) {
        if (typeof this.onControllerTasks !== 'function') {
            return;
        }
        try {
            this.onControllerTasks({kind: 'complete', frame: data});
        } catch (e) {
            // 观测层绝不打断页面自己的流量
        }
    };

    /**
     * **整表帧**（可落地）：`sessions` 是刚成功应用过的一帧读数。
     *
     * 4c（2026-09-18）：载荷多两个字段，老字段**一个都不改名、不删**；原生侧字段缺失
     * 时按"不陈旧"处理，所以老读数路径不受影响。对照表见 [_emitSessionsStale]。
     *   * `stale` —— 上报这一刻本状态机是否处于序号缺口。**它并不恒 false**
     *     （2026-09-18 真机实测更正了原来那句"恒 false"的断言）：
     *     `stale` 只由**整窗快照**解除，而**序号对得上的增量帧同样会成功应用**
     *     （缺口期间 `seq` 有意冻结，桌面端重发同一段时会重新对上）——那些帧走到这里时
     *     `stale` 仍为 true。原生对此的处理正是想要的：带 `stale:true` 的整表帧**列表不落地**，
     *     `staleSince` 保留到下一次整窗快照（见 `TaskStore.applyWorkspace` 的守卫）。
     *     ⚠️ **不要**据此反推"应用成功 ⟹ 不陈旧"：被动路径的"已自愈"日志曾因这个错假设
     *     逐帧刷屏（成因、真机取证与正确判据见那段代码的注释）。
     *   * `staleOnly` —— 恒 false：本帧是普通整表帧，`sessions` **可以**落地。
     */
    RemoteClient.prototype._emitSessions = function (key, bridge, state, source) {
        var list = state.list();
        // 4c（2026-09-18）：这里原来还往模块级的 `sessionsByKeyCache` 写一份
        // （供"开桥时先订对话"用）。那条对话自订阅路已删，这份缓存只写不读，
        // 随写入点一并删除。
        if (!this.onSessions) {
            return;
        }
        var scope = bridge.scope || {};
        this.onSessions({
            key: key,
            title: workspaceTitle(scope),
            workspacePath: scope.workspacePath || '',
            workspaceIdentity: scope.workspaceIdentity || '',
            source: source,
            sessions: list,
            stale: state.stale === true,
            staleOnly: false
        });
    };

    /**
     * 4c：**仅标记帧**——检出序号缺口那一拍**额外**发一次的上报。
     *
     * 为什么必须单独一帧：缺口之后本状态机对每一帧增量都判失败（`seq` 有意不动），
     * 于是"成功应用"与"缺口自愈"是同一件事——把 `stale` 挂在整表帧上，它**永远不会有
     * true 的那一天**（`tools/protocol.test.js` 的「被动：缺口把该工作区标为陈旧…」把
     * "缺口期间一帧都不上报"钉住了）。要让原生在缺口**刚刚发生**时就列其为不可信，
     * 只能在检出那一拍另发一帧。按**跃迁**发，不逐帧发。
     *
     * 为什么这一帧**不能落地**：它怀里那份 `sessions` 是**冻结的旧读数**（缺口之前最后
     * 一次成功应用的列表）。原生若照常 `applyWorkspace`，会把该工作区 SI 的时刻戳整表
     * 重盖（`TaskStore.applyWorkspace` 里 `siPhasesAt` 那一段），让旧 SI 反过来压过
     * controller / 会话流覆盖层——**这一帧自己就会制造一次它要防的降级**。所以形状上
     * 显式标成 `staleOnly: true`，语义是"只取标记，`sessions` 不是读数"。
     *
     * 与整表帧的对照（原生按这两个字段分流，**字段缺失 = 不陈旧**）：
     *
     *   | 帧         | stale | staleOnly | 原生动作                                  |
     *   |------------|-------|-----------|-------------------------------------------|
     *   | 整表帧     | false | false     | 照常落地；清除该工作区陈旧标记与可信窗口   |
     *   | 仅标记帧   | true  | true      | **只记陈旧，不碰列表**                     |
     *
     * 不写 `sessionsByKeyCache`：那里存的是"上一份可用读数"，本帧不是读数，别拿它顶掉。
     */
    RemoteClient.prototype._emitSessionsStale = function (key, bridge, state) {
        if (!this.onSessions) {
            return;
        }
        var scope = bridge.scope || {};
        this.onSessions({
            key: key,
            title: workspaceTitle(scope),
            workspacePath: scope.workspacePath || '',
            workspaceIdentity: scope.workspaceIdentity || '',
            source: 'passive',
            sessions: state.list(),
            stale: true,
            staleOnly: true
        });
    };







    /** "这条会话确实在跑吗"——JS 侧没有 conversation store，用两个近似判据。 */






    /** Drops both indexes for a workspace; the frame router keys off the id. */

    // ----------------------------------------------------------------- passive

    /**
     * Passive coverage: watch the page's own traffic. No writes, so this
     * cannot disturb the page — it is the fallback when active subscription is
     * switched off and the diagnostics channel when it is on.
     */
    RemoteClient.prototype.acceptObservedPayload = function (payload, outbound) {
        if (!payload || typeof payload !== 'object') {
            return;
        }
        if (payload.zcode_type === 'rpc-frame') {
            if (outbound) {
                this._observeOutboundRpc(payload);
            } else {
                this._observeInboundRpc(payload);
            }
            return;
        }
        if (outbound) {
            return;
        }
        if (payload.zcode_type === 'workspace-bridge-ready' && payload.bridge) {
            var info = payload.bridge;
            if (info.workspaceKey) {
                this._bridgeWorkspace[payload.bridgeSessionId] = info.workspaceKey;
                // 【只读壳下永假，恢复开桥时必须加回】这里原来是
                // `if (!this._requestedBridgeIds[payload.bridgeSessionId]) {
                //     this._notePageBridge(info.workspaceKey); }`——用来区分
                // "页面自己开的桥"与"壳自己要的桥"。`_requestedBridgeIds` 的唯一写点
                // 是 `openBridge`（注入层永不调用的 `start()` 驱动）⟹ 恒空 ⟹ 条件恒真。
                // 2026-09-18（4c）：`_notePageBridge` 与它写的两张页覆盖表（唯一读者
                // 也都在已删的 active 路径里）一并删除，故此处不再调用。
                // ⚠️ 恢复开桥时必须把条件与 `_notePageBridge` 一起加回，否则壳自己开的桥
                // 会被误记为"页面已覆盖"，壳会把该工作区让出去。
            }
        }
    };

    /**
     * 页面自身 RPC 的追踪（见构造函数里的 `_pageRpc`）。
     *
     * 一次页面 promise 请求就是一个重组后的 ChannelClient body：
     * [REQ_PROMISE, id, channel, method] + args；回复是 [201|202|203, id] + value，
     * 用 (bridgeSessionId, id) 配对——这条路径上桌面端不回显我们的 requestId，
     * 且 id 只在单个 bridge 内唯一。
     *
     * 壳自己的 bridge 永远回答不了「点进任务为什么半天不出内容」，因为那个
     * 会话请求属于页面。这是唯一能看到它的地方。
     */
    RemoteClient.prototype._tracePageCall = function (bridgeSessionId, header, args) {
        var id = header[1];
        if (typeof id !== 'number') {
            return;
        }
        var name = String(header[2] === undefined ? '?' : header[2]) + '.' +
            String(header[3] === undefined ? '?' : header[3]);
        var pending = this._pageRpc.pending;
        var size = 0;
        var oldest = null;
        for (var slot in pending) {
            size += 1;
            if (oldest === null || pending[slot].at < pending[oldest].at) {
                oldest = slot;
            }
        }
        if (size >= PAGE_RPC_PENDING_MAX && oldest !== null) {
            // Bound the map: whatever never came back is not going to.
            delete pending[oldest];
        }
        pending[bridgeSessionId + '#' + id] = {name: name, at: Date.now()};
        this._pageRpc.calls += 1;
        this._pageRpc.windowCalls += 1;
        var methods = this._pageRpc.windowMethods;
        methods[name] = (methods[name] || 0) + 1;
        if (typeof this.onPageRpcCall === 'function') {
            try {
                this.onPageRpcCall({name: name, args: args || null});
            } catch (e) {
                // a hook must never break the page's traffic
            }
        }
    };

    RemoteClient.prototype._tracePageResult = function (bridgeSessionId, type, header, data) {
        var id = header[1];
        if (typeof id !== 'number') {
            return;
        }
        var slot = bridgeSessionId + '#' + id;
        var call = this._pageRpc.pending[slot];
        if (!call) {
            return;
        }
        delete this._pageRpc.pending[slot];
        var cost = Date.now() - call.at;
        var ok = type === RES_PROMISE_SUCCESS;
        var message = '';
        if (!ok) {
            this._pageRpc.errors += 1;
            this._pageRpc.windowErrors += 1;
            try {
                message = data && typeof data === 'object' && data.message ?
                    String(data.message) : (typeof data === 'string' ? data : JSON.stringify(data));
            } catch (e) {
                message = '';
            }
            if (!this.suppressPageRpcMirror) {
                this._log('页面调用失败 ' + cost + 'ms：' + call.name +
                    (message ? ' · ' + String(message).substring(0, 160) : ''));
            }
        } else if (cost >= this._pageRpcSlowMs) {
            this._pageRpc.slow += 1;
            this._pageRpc.windowSlow += 1;
            if (this._pageRpc.slowLogged < PAGE_RPC_SLOW_LOG_MAX) {
                this._pageRpc.slowLogged += 1;
                if (!this.suppressPageRpcMirror) {
                    this._log('页面调用慢 ' + cost + 'ms：' + call.name +
                        (this._pageRpc.slowLogged === PAGE_RPC_SLOW_LOG_MAX ?
                            '（后续慢调用只计入窗口汇总）' : ''));
                }
            }
        }
        if (typeof this.onPageRpcResult === 'function') {
            try {
                this.onPageRpcResult({name: call.name, ok: ok, cost: cost, message: message});
            } catch (e) {
                // a hook must never break the page's traffic
            }
        }
    };

    /** 每个心跳窗口一条有上限的汇总；这一窗口没有任何页面调用时保持安静。 */
    RemoteClient.prototype.reportPageRpcWindow = function () {
        var rpc = this._pageRpc;
        // 沉默检测放在汇总早退之前：一个"只有沉默"的窗口 windowCalls 是 0，
        // 但 pending 里的陈年调用正是要在这种窗口里被点名。报告后即摘除，
        // 同一次沉默只说一遍。
        var nowMs = Date.now();
        for (var slot in rpc.pending) {
            var p = rpc.pending[slot];
            var age = nowMs - p.at;
            if (age >= PAGE_RPC_SILENCE_MS) {
                if (typeof this.onPageRpcSilence === 'function') {
                    try {
                        this.onPageRpcSilence({name: p.name, ageMs: age});
                    } catch (e) {}
                }
                delete rpc.pending[slot];
            }
        }
        if (rpc.windowCalls === 0 && rpc.windowErrors === 0 && rpc.windowSlow === 0) {
            return;
        }
        var names = [];
        for (var name in rpc.windowMethods) {
            names.push({name: name, count: rpc.windowMethods[name]});
        }
        names.sort(function (a, b) {
            return b.count - a.count;
        });
        var shown = [];
        for (var i = 0; i < names.length && i < PAGE_RPC_METHODS_MAX; i++) {
            shown.push(names[i].name + ' ' + names[i].count);
        }
        if (names.length > shown.length) {
            shown.push('…共 ' + names.length + ' 种');
        }
        this._log('页面 RPC 10s：' + rpc.windowCalls + ' 个（慢 ' + rpc.windowSlow +
            '，失败 ' + rpc.windowErrors + '）' + (shown.length ? ' · ' + shown.join(' · ') : ''));
        rpc.windowCalls = 0;
        rpc.windowErrors = 0;
        rpc.windowSlow = 0;
        rpc.windowMethods = {};
    };


    RemoteClient.prototype._observeOutboundRpc = function (payload) {
        // 【只读壳下永假，恢复开桥时必须加回】这里原来是"跳过壳自己的桥"——
        // `if (this._bridgesById[payload.bridgeSessionId]) { return; }`。`_bridgesById`
        // 的唯一写点是 `openBridge`，而它由注入层永不调用的 `start()` 驱动
        // （见构造函数里 passive 段的注释），所以这张表在生产里**恒空**、这个判断
        // **恒假**。2026-09-18（4c）随表一并删除。
        // ⚠️ 若将来恢复开桥，这一句必须加回来，否则壳自己的桥发出的帧会被当成
        // "页面桥的流量"计入 `_pageRpc`。
        var bytes = this._tryAssemble(payload, true);
        if (!bytes) {
            return;
        }
        var header;
        var args;
        try {
            var reader = new ByteReader(bytes);
            header = decodeValue(reader);
            args = reader.remaining > 0 ? decodeValue(reader) : null;
        } catch (e) {
            return;
        }
        if (!Array.isArray(header) || typeof header[0] !== 'number') {
            return;
        }
        if (header[0] === REQ_PROMISE) {
            // 4c（2026-09-18）：这里原来学页面的 clientHello 存进 `_clientHello`，
            // 供壳自己握手时对齐协议版本。壳不再握手（`subscribeSessionsIndex` 已删）
            // ⟹ 那个字段只写不读，随写入点一并删除。
            this._tracePageCall(payload.bridgeSessionId, header, args);
            return;
        }
        if (header[0] === REQ_PROMISE_CANCEL) {
            delete this._pageRpc.pending[payload.bridgeSessionId + '#' + header[1]];
            return;
        }
        if (header[0] !== REQ_EVENT_LISTEN) {
            return;
        }
        if (header[3] !== EVENT_SESSIONS_INDEX) {
            return;
        }
        var scope = args && typeof args === 'object' ? args : {};
        var key = workspaceKeyOf(scope);
        if (!key) {
            return;
        }
        this._outboundListenIds[payload.bridgeSessionId + '#' + header[1]] = key;
        this._bridgeWorkspace[payload.bridgeSessionId] = key;
        // 4c（2026-09-18）：这里原来还盖一个 `_pageCoveredKeys[key] = true`
        // （"页面在本连接上流这个工作区"的直接证据）。它唯一的读者是已删的
        // `start()`，"页面覆盖证据按连接代次"那套让位决策随桥路一起没了 ⟹
        // 只写不读，随读者一并删除。
        if (!this._passive[key]) {
            this._passive[key] = {
                key: key,
                scope: scope,
                state: new SessionsIndexState()
            };
            this._log('passive: following sessions-index of ' + key);
            this._emitStatus('passive tracking ' + key);
        }
        // 4c（2026-09-18）：这里原来是 `this._dropRedundantBridge(key);`——
        // "页面已证明它在流这个工作区 ⟹ 撤掉我们自己那座重复桥"。该函数首句是
        // `var bridge = this._bridges[key]; if (!bridge || bridge.closed) return;`，
        // 而 `_bridges` 在只读壳下恒空 ⟹ 它**恒早退 no-op**；2026-09-18 随桥表
        // 一并删除。⚠️ 恢复开桥时必须加回，否则桌面端会用 rpc-transport-fault
        // 拒掉重复桥，而那个 fault 会触发针对"用户此刻正在看的工作区"的重开循环。
        // 注意：随它消失的还有 `页面已接管 <key>` 那行日志（原唯一出口），
        // 真机回归判据里不应再期待这一行。
    };

    RemoteClient.prototype._observeInboundRpc = function (payload) {
        // 【只读壳下永假，恢复开桥时必须加回】这里原来是"跳过壳自己的桥"——
        // `if (this._bridgesById[payload.bridgeSessionId]) { this._obsOurs += 1; return; }`。
        // 理由同 `_observeOutboundRpc` 顶部。连带的两个计数器一并删除：
        // `_obsOurs` 是这个恒假分支的专属计数；`_obsIn` 只写不读（既有孤儿）。
        // ⚠️ 恢复开桥时必须加回，否则壳自己桥的入站帧会给 `_pageBridgeTrafficAt`
        // （inject.js §5b 的"内容还在不在下发"判据）盖假章——那正是守卫要防的事。
        // 页面桥的入站流量戳（任何 rpc-frame 都算）：「对话内容是否真的在下发」
        // 的协议层信号——DOM 层看不出内容缺失（标题/输入框都正常），流量看得出。
        // inject.js 的 10s 内容检查用（见 §5b）。
        this._pageBridgeTrafficAt = Date.now();
        var bytes = this._tryAssemble(payload, false);
        if (!bytes) {
            return;
        }
        var header;
        var data;
        try {
            var reader = new ByteReader(bytes);
            header = decodeValue(reader);
            data = reader.remaining > 0 ? decodeValue(reader) : null;
        } catch (e) {
            return;
        }
        if (!Array.isArray(header) || typeof header[0] !== 'number') {
            return;
        }
        if (header[0] === RES_EVENT_FIRE && data && typeof data === 'object' &&
            typeof data.topic === 'string' && data.topic.indexOf('conversation/') === 0) {
            this._pageConversationTrafficAt = Date.now();
            this._trackConversationText(data);
        }
        // 第三条源（2026-09-18）：**页面自己订的** `controller/tasks-index`。
        // 为什么单列一条：`sessions-index` 那条只能跟随"页面此刻在听的那一个工作区"
        // （见 _observeOutboundListen），而"哪些任务在跑"是全局的——用户在别的
        // 工作区里继续跑任务时，壳里 `runningTaskRefs()` 会是空的，卡片/接管全都
        // 无从谈起（真机 2026-09-18 09:14 就是这个现场）。这条帧页面本来就收，
        // 解析交给原生那份**已被单测钉过**的 ControllerTasksState，这里只做转发。
        if (header[0] === RES_EVENT_FIRE && data && typeof data === 'object' &&
            data.topic === CONTROLLER_TASKS_TOPIC) {
            this._forwardControllerTasks(data);
        }
        // 入站 topic 直方图：每个新 topic 打一行（最多 8 种）。
        // 为什么要有它：真机 2026-09-15 出现"对话订阅 8 次全部 ack 成功、对话帧却是 0"，
        // 而页面自己那条订阅同期**拿到了 snapshot**——所以帧一定在发，问题在"我这条观测
        // 通路有没有见到它"。这行日志就是回答这个问题的唯一手段（topic 长什么样、
        // 页面桥的帧到底进没进来）。
        if (header[0] === RES_EVENT_FIRE && data && typeof data === 'object' &&
            typeof data.topic === 'string') {
            if (!this._obsTopics) {
                this._obsTopics = {};
            }
            if (this._obsTopics[data.topic] === undefined) {
                var kinds = Object.keys(this._obsTopics).length;
                this._obsTopics[data.topic] = 0;
                if (kinds < 8) {
                    this._log('入站 topic 首次出现(' + (kinds + 1) + ')：' + data.topic +
                        ' payload.kind=' + (data.payload && data.payload.kind ? data.payload.kind : '-'));
                }
            }
            this._obsTopics[data.topic] += 1;
        }
        if (header[0] === RES_PROMISE_SUCCESS || header[0] === RES_PROMISE_ERROR ||
            header[0] === RES_PROMISE_ERROR_OBJ) {
            // 页面 bridge 的回复一律配对，哪怕还没学到它属于哪个工作区：
            // 配对键就是 (bridgeSessionId, id)。
            this._tracePageResult(payload.bridgeSessionId, header[0], header, data);
            return;
        }
        if (header[0] !== RES_EVENT_FIRE) {
            return;
        }
        var key = this._bridgeWorkspace[payload.bridgeSessionId];
        if (!key) {
            return;
        }
        if (this._outboundListenIds[payload.bridgeSessionId + '#' + header[1]] !== key) {
            return;
        }
        var entry = this._passive[key];
        if (!entry || !data || typeof data !== 'object') {
            return;
        }
        // ---- 4c 工程前置（2026-09-18）：检出 → 记一行日志 ----
        //
        // 为什么落在这里：**这是活的被动观察路径上唯一能看到"序号缺口"的地方**。检出那一步
        // （状态机判 `fromSeq !== seq`）本来就在活路上可达，但它的消费者——真正去重同步的
        // `_resyncSessionsIndex`——只写在已死的主动开桥路径里（只读壳下桥恒不开），所以
        // 这个事件在真机上**从来没有留下过任何痕迹**。这里补的就是"可观测"：
        // 一行带**工作区键**与**缺口事实**的日志，真机上 grep 得到（走 diag → 原生
        // Diagnostics/ShellLog，tag=ZCodeRemote）。
        //
        // 按**跃迁**记，不按帧记：缺口之后每一帧都会再判失败一次，逐帧记会把日志刷爆。
        var wasStale = entry.state.stale === true;
        if (entry.state.applyWireFrame(data)) {
            // ⚠️ 判据必须是**真跃迁**：改前为真 **且** 改后为假。
            //
            // 只看改前（`wasStale`）会漏掉一类帧——"**应用成功、但 `stale` 仍未解除**"的帧：
            //   * `stale` 只由**整窗快照**解除（见 applyLogicalFrame）；
            //   * 而**序号对得上的增量帧同样返回 true**（缺口期间 `seq` 有意冻结，桌面端重发
            //     同一段时会重新对上）。
            // 于是那类帧**每一帧**都命中 `wasStale` ⟹ 每帧记一行"已自愈"。
            // 真机实测（2026-09-18 20:37–20:41）：4 分钟内数百条，有时 0.5s 内 7 条，
            // 把 512 KB 的日志**反复刷到轮转**，等于摧毁这个壳赖以排障的历史记录。
            // **别再退回只看改前的写法。**
            if (wasStale && entry.state.stale !== true) {
                this._log('会话清单已自愈：' + key + ' 收到整窗快照，陈旧解除');
            }
            this._emitSessions(key, {
                scope: entry.scope
            }, entry.state, 'passive');
        } else if (!wasStale && entry.state.stale === true) {
            this._log('会话清单序号缺口：' + key + ' 的增量帧 fromSeq=' +
                entry.state.gapFromSeq + ' 与本机锚点 seq=' + entry.state.seq +
                ' 对不上，这一路读数标为陈旧（等下一次整窗快照自愈）');
            // 检出那一拍**额外一次「仅标记」上报**（4c §4.1）：日志只解决"真机上看得见"，
            // 原生要据此把该工作区的读数列为不可信（防降级规则的入口 = TaskStore.staleSince）。
            // 只在这一拍发（与上面那行日志同一去重口径：缺口没解除前不再补发）。
            this._emitSessionsStale(key, {
                scope: entry.scope
            }, entry.state);
        }
    };

    /**
     * 从**网页自己那条**对话订阅流里取"最新一段 AI 正文"。
     *
     * 为什么需要它：会话索引里的 `lastAssistantPreview` **只在轮次边界变**，流式输出
     * 期间卡片是死的（真机 2026-09-15：帧一直在来，正文却停在某一刻不动）。而网页自己
     * 对"用户正在看的那条会话"是订阅着的，那些帧就走**同一条 socket**、我们本来就看得见
     * ——此前只给它打了个流量时间戳（见上）。于是不必开第二条连接、不会触发单控制端
     * 互斥（KICKED），也能拿到流式正文。
     *
     * 帧契约与 Kotlin 侧 `RelayWire` 的解码注释是同一份：
     *   {topic, subscriptionId, fromSeq, toSeq, payload:{kind:'snapshot'|'deltas'}}
     *   snapshot → payload.rows.window = [row…]
     *   deltas   → payload.ops = [{op:'row.appended'|'row.upserted'|'row.delta'|…}]
     *   row.delta 的字段 = {rowId, path:'text', append}
     * 行里只认 `kind === 'assistantText'` 的 `text`（与 Kotlin 侧 progressHead 同口径：
     * 工具调用 / 子代理 / reasoning 一律不上卡片）。
     *
     * 每个 topic 只留"最后一段"，不做整台 store——流体云要的就是这一句。
     */
    RemoteClient.prototype._trackConversationText = function (data) {
        if (!this._convText) {
            this._convText = {};
        }
        this._convFrames = (this._convFrames || 0) + 1;
        // 最后一帧的时刻（2026-09-17）：原生据此判"页面还在不在跟某个会话"——
        // 概览页（没进任何任务）永远收不到 conversation/* 帧，而进了任务哪怕 agent 静默，
        // 首屏快照/轮次帧也会到。承载用它做"提前接管"的判据（见 ShellRuntime）。
        this._convLastFrameAt = Date.now();
        // 最近收到的那个会话 topic（2026-09-19，档 0 观测）：**逐帧**记，不挑内容。
        // 与 `_convTextTopic` 的区别很重要：后者只在**解出正文**时才更新（见
        // `_trackConversationText` 末尾），于是"页面在跟一个没正文的会话"（刚进任务、
        // agent 还没开口）在它那里读不到。档 0 要判的是"用户此刻在看哪个会话"，
        // 所以必须用"最近收到帧的那个"——哪怕那帧里没有正文。
        this._convLastTopic = data.topic;
        // **真实结构（2026-09-15 真机打出来，三层，不要再猜）**：
        //   data  = { wireVersion, kind:'complete', deliveryKind, logicalFrameId,
        //             logicalFrameOrdinal, topic, subscriptionId, frame }
        //   frame = { topic, subscriptionId, sentAt, fromSeq, toSeq, payload }
        //   frame.payload = { kind:'snapshot'|'deltas', rows:{window:[…]} / ops:[…], logEpoch … }
        //
        // 两个曾经踩空的点：① 内容体在 **frame.payload**，不是 data.payload；
        // ② `data.kind` 是**投递**类别（'complete'），**不是** snapshot/deltas——所以下面
        // 判分支只看 `rows`/`ops` 是否存在，不再依赖那个字符串。
        var frame = data.frame && typeof data.frame === 'object' ? data.frame : null;
        var inner = frame && frame.payload && typeof frame.payload === 'object' ? frame.payload : null;
        var body = inner ||
            (data.payload && typeof data.payload === 'object' ? data.payload : null) ||
            frame || data;
        if (!this._convShapeSeen) {
            this._convShapeSeen = {};
        }
        if (!this._convShapeSeen[data.topic]) {
            this._convShapeSeen[data.topic] = true;
            var dataKeys = '-';
            var bodyKeys = '-';
            try {
                dataKeys = Object.keys(data).join(',');
            } catch (e) {
                dataKeys = 'keys-failed';
            }
            try {
                bodyKeys = body && typeof body === 'object' ?
                    Object.keys(body).join(',') : String(body);
            } catch (e) {
                bodyKeys = 'keys-failed';
            }
            this._log('对话帧形状 ' + data.topic + '：投递kind=' + data.kind +
                ' 内容kind=' + (body ? body.kind : '-') +
                ' data键=[' + dataKeys + '] 内容键=[' + bodyKeys + ']');
        }
        if (!body || typeof body !== 'object') {
            return;
        }
        var entry = this._convText[data.topic];
        if (!entry) {
            entry = this._convText[data.topic] = {
                rowId: null,
                text: ''
            };
        }
        // 窗口与增量两个位置都找一遍（快照在 rows.window，增量在 ops），并且**只看
        // 结构存在与否来判分支**——`data.kind` 是投递类别（'complete'），拿它判
        // snapshot/deltas 会永远判错（2026-09-15 就栽在这儿）。
        var rows = null;
        if (body.rows && Array.isArray(body.rows.window)) {
            rows = body.rows.window;
        } else if (data.rows && Array.isArray(data.rows.window)) {
            rows = data.rows.window;
        }
        var ops = Array.isArray(body.ops) ? body.ops :
            (Array.isArray(data.ops) ? data.ops : null);
        var changed = false;
        if (rows) {
            for (var i = rows.length - 1; i >= 0; i -= 1) {
                var row = rows[i];
                if (row && row.kind === 'assistantText' &&
                    typeof row.text === 'string' && row.text.trim().length > 0) {
                    entry.rowId = row.rowId || null;
                    entry.text = row.text;
                    changed = true;
                    break;
                }
            }
        } else if (ops) {
            for (var j = 0; j < ops.length; j += 1) {
                var op = ops[j];
                if (!op || typeof op !== 'object') {
                    continue;
                }
                var full = op.row || op;
                if ((op.op === 'row.appended' || op.op === 'row.upserted') &&
                    full && full.kind === 'assistantText' && typeof full.text === 'string') {
                    entry.rowId = full.rowId || entry.rowId;
                    entry.text = full.text;
                    changed = true;
                } else if (op.op === 'row.delta' && op.path === 'text' &&
                    typeof op.append === 'string' && op.append.length > 0) {
                    // 只在 rowId 对得上时拼接；对不上说明错过了这一行的开头，
                    // 那就等下一次 snapshot / upsert 把整行送回来。
                    if (!entry.rowId || op.rowId === entry.rowId) {
                        entry.rowId = op.rowId || entry.rowId;
                        entry.text += op.append;
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            this._convTextAt = Date.now();
            this._convTextTopic = data.topic;
        }
    };

    /**
     * 当前"最新一段 AI 正文"；null 表示还没有正文可报。
     * 只报最新那个 topic——卡片显示的是用户此刻在看的会话。
     */
    RemoteClient.prototype.latestConversationText = function () {
        var topic = this._convTextTopic;
        if (!topic || !this._convText || !this._convText[topic]) {
            return null;
        }
        var entry = this._convText[topic];
        if (!entry.text) {
            return null;
        }
        return {
            topic: topic,
            text: entry.text,
            rowId: entry.rowId,
            frames: this._convFrames || 0,
            at: this._convTextAt || 0
        };
    };

    /**
     * 最近收到过帧的那个会话 topic（`conversation/sess_xxx`），没有则空串。
     *
     * 2026-09-19（档 0 观测）新增。与 [latestConversationText] 的关键区别：
     * 那个读 `_convTextTopic`，而它**只在解出正文时**才更新——于是"页面刚进任务、
     * agent 还没开口"这种没有正文的状态在它那里读不到。档 0 要回答的是
     * "用户此刻在看哪个会话"，所以用逐帧更新的 `_convLastTopic`。
     *
     * 纯读，不改变任何状态；读者只有注入层的 `__zcodeShellViewSnapshot`。
     */
    RemoteClient.prototype.latestConversationTopic = function () {
        return this._convLastTopic || '';
    };

    /**
     * 诊断专用：**帧计数与"有没有正文"解耦**。
     *
     * 为什么必须分开：`latestConversationText()` 是给"上报正文"用的，没正文就返回 null，
     * 于是 `页面开销` 那行的 `对话帧` 也跟着变成 0——**"没帧"与"有帧但没解出正文"
     * 在日志里长得一模一样**。2026-09-15 我为此连查了几轮分片上限与候选时序，
     * 真相却是帧一直在到（入站直方图里 `conversation/…` 出现过），只是提取器形状不匹配。
     */
    RemoteClient.prototype.conversationFrameStats = function () {
        var topics = this._convShapeSeen ? Object.keys(this._convShapeSeen).length : 0;
        var textLen = 0;
        if (this._convTextTopic && this._convText && this._convText[this._convTextTopic]) {
            textLen = (this._convText[this._convTextTopic].text || '').length;
        }
        // 4c（2026-09-18）：`subs`/`resyncs` 原来取自 `_convSubs`（壳自己订的对话流
        // 订阅表）。整块对话自订阅簇已删 ⟹ 该表不复存在，这两个数在任何情况下都是 0。
        // ⚠️ **字段必须保留**（不能从返回值里删）：inject.js 的「页面开销」行会读
        // `convStats.subs` / `.resyncs`，字段缺失会在真机日志里打印成 undefined。
        return {
            frames: this._convFrames || 0,
            topics: topics,
            textLen: textLen,
            subs: 0,
            resyncs: 0,
            // −1 = 本客户端生命周期内一帧 conversation/* 都没见过（⇒ 页面没在跟任何会话）。
            // 原生"提前接管"的判据就是这个（见 inject.js 的 reportLiveness 与 ShellRuntime）。
            lastFrameAgoMs: this._convLastFrameAt ? Date.now() - this._convLastFrameAt : -1
        };
    };

    /** Reassembles the page's rpc-frames in a side table (never acks). */
    RemoteClient.prototype._tryAssemble = function (payload, outbound) {
        var table = outbound ? '_observedOut' : '_observedIn';
        if (!this[table]) {
            this[table] = {};
        }
        var assemblies = this[table];
        var messageSeq = payload.messageSeq;
        var fragmentIndex = payload.fragmentIndex;
        var fragmentCount = payload.fragmentCount;
        var messageBytes = payload.messageBytes;
        var dataBase64 = payload.dataBase64;
        if (typeof messageSeq !== 'number' || typeof fragmentIndex !== 'number' ||
            typeof fragmentCount !== 'number' || typeof messageBytes !== 'number' ||
            typeof dataBase64 !== 'string') {
            return null;
        }
        if (fragmentCount < 1 || fragmentCount > MAX_FRAGMENTS ||
            messageBytes < 1 || messageBytes > MAX_MESSAGE_BYTES) {
            return null;
        }
        var assembly = assemblies[messageSeq];
        if (!assembly || assembly.count !== fragmentCount) {
            assembly = {
                count: fragmentCount,
                bytes: messageBytes,
                parts: new Array(fragmentCount),
                received: 0,
                at: Date.now()
            };
            assemblies[messageSeq] = assembly;
        }
        if (assembly.parts[fragmentIndex] === undefined) {
            assembly.received += 1;
        }
        try {
            assembly.parts[fragmentIndex] = base64Decode(dataBase64);
        } catch (e) {
            delete assemblies[messageSeq];
            return null;
        }
        if (assembly.received !== fragmentCount) {
            return null;
        }
        delete assemblies[messageSeq];
        var joined = concatBytes(assembly.parts);
        return joined.length === messageBytes ? joined : null;
    };

    RemoteClient.prototype.dispose = function () {
        // （曾经这里还有 `this.subscribeAll = false;`——D7 开关本身已删。）
        // （4c·2026-09-18：这里原来还重置 `_bridges`/`_bridgesById`/`_activeKeys`/
        // `_pending` 四张表。四张表已随桥路由子系统删除，重置也就无从谈起。）
    };

    // -----------------------------------------------------------------------
    return {
        // constants
        CHANNEL_CONVERSATION: CHANNEL_CONVERSATION,
        EVENT_SESSIONS_INDEX: EVENT_SESSIONS_INDEX,
        REQ_PROMISE: REQ_PROMISE,
        REQ_PROMISE_CANCEL: REQ_PROMISE_CANCEL,
        REQ_EVENT_LISTEN: REQ_EVENT_LISTEN,
        RES_INITIALIZE: RES_INITIALIZE,
        RES_PROMISE_SUCCESS: RES_PROMISE_SUCCESS,
        RES_PROMISE_ERROR: RES_PROMISE_ERROR,
        RES_EVENT_FIRE: RES_EVENT_FIRE,
        DEFAULT_CLIENT_HELLO: DEFAULT_CLIENT_HELLO,
        // helpers (exported for the Node tests)
        utf8Encode: utf8Encode,
        utf8Decode: utf8Decode,
        base64Encode: base64Encode,
        base64Decode: base64Decode,
        concatBytes: concatBytes,
        crc32: crc32,
        crc32Hex: crc32Hex,
        ByteWriter: ByteWriter,
        ByteReader: ByteReader,
        encodeValue: encodeValue,
        decodeValue: decodeValue,
        RpcFrameSender: RpcFrameSender,
        RpcFrameAssembler: RpcFrameAssembler,
        ChannelClient: ChannelClient,
        SessionsIndexState: SessionsIndexState,
        workspaceKeyOf: workspaceKeyOf,
        workspaceTitle: workspaceTitle,
        normalizeSession: normalizeSession,
        RemoteClient: RemoteClient,
        MAX_FRAGMENT_BYTES: MAX_FRAGMENT_BYTES
    };
});
