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
    var MAX_FRAGMENT_BYTES = 512 * 1024;
    var MAX_FRAGMENTS = 64;
    var MAX_LOGICAL_FRAGMENTS = 64;
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

    // How many times one workspace may fault and be reopened within a single
    // relay connection before we stop trying (see _handleDegraded).
    var MAX_REOPENS_PER_BRIDGE = 2;

    // A workspace that faults once is a transient transport problem and deserves
    // a retry. One that faults again on the NEXT connection is the desktop
    // refusing it, and re-opening it there is what closed the loop: a relay
    // rebuild wiped the per-connection budget, the burst re-opened the bridge,
    // and the fault came back on the same cadence (field log: `default` faulted
    // every ~45 s, forever). After this many distinct connections have faulted on
    // one key it goes on cooldown. Deliberately reversible — the cooldown expires
    // and the key is tried again — so a desktop that was merely unreachable for a
    // while can never cost notification coverage permanently.
    var FAULT_COOLDOWN_CONNECTIONS = 3;
    var FAULT_COOLDOWN_MS = 10 * 60 * 1000;

    // Total time the subscription burst may spend waiting for the page to be
    // idle, spread across its workspaces. The gate in inject.js keeps the burst
    // from STARTING while the page is busy; this keeps it from ploughing on when
    // the user opens a task mid-burst. Bounded, so a chatty page cannot stretch
    // the burst without limit.
    var BURST_YIELD_TOTAL_MS = 8000;
    var BURST_YIELD_POLL_MS = 120;

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
    RpcFrameAssembler.prototype.purgeStale = function (maxAgeMs) {
        var now = Date.now();
        for (var key in this._assemblies) {
            if (now - this._assemblies[key].at > maxAgeMs) {
                delete this._assemblies[key];
            }
        }
    };

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
        this._fragments = {};
    }

    SessionsIndexState.prototype.reset = function () {
        this.workspaceId = null;
        this.logEpoch = null;
        this.seq = 0;
        this.sessions = {};
        this.ready = false;
        this._fragments = {};
    };

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
        } else if (payload.kind === 'deltas') {
            var fromSeq = typeof frame.fromSeq === 'number' ? frame.fromSeq : this.seq;
            if (fromSeq !== this.seq) {
                // Lost an update: the caller must resync, otherwise the phase
                // table silently drifts and completion events are missed.
                this.needsResync = true;
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
    // Bridge — one workspace's RPC endpoint
    // -----------------------------------------------------------------------
    function Bridge(options) {
        this.key = options.key;
        this.scope = options.scope;
        this.info = options.info;
        this.bridgeSessionId = options.bridgeSessionId;
        this.generation = options.generation;
        this.recoveryId = options.recoveryId;
        this.sender = null;
        this.assembler = null;
        this.channels = null;
        this.closed = false;
        this._clientHello = null;
    }

    Bridge.prototype.attach = function (sendPayload, options) {
        var self = this;
        this.sender = new RpcFrameSender({
            bridgeSessionId: this.bridgeSessionId,
            bridgeGeneration: this.generation,
            recoveryId: this.recoveryId,
            sendPayload: sendPayload
        });
        this.assembler = new RpcFrameAssembler({
            bridgeSessionId: this.bridgeSessionId,
            onLog: options.onLog,
            onAck: function (messageSeq) {
                sendPayload({
                    zcode_type: 'rpc-frame-ack',
                    bridgeSessionId: self.bridgeSessionId,
                    ackMessageSeq: messageSeq
                });
            },
            onMessage: function (bytes) {
                self.channels.handleMessage(bytes);
            }
        });
        this.channels = new ChannelClient({
            sendBody: function (bytes) {
                self.sender.sendMessage(bytes);
            },
            // Share the socket with the page's own client: keep our request ids
            // far away from the small integers it allocates.
            idBase: options.idBase,
            onLog: options.onLog
        });
    };

    Bridge.prototype.acceptPayload = function (payload) {
        if (this.closed || !this.assembler) {
            return false;
        }
        return this.assembler.acceptPayload(payload);
    };

    Bridge.prototype.clientHello = function () {
        return this._clientHello || DEFAULT_CLIENT_HELLO;
    };

    // -----------------------------------------------------------------------
    // RemoteClient — the business layer
    // -----------------------------------------------------------------------
    /**
     * options:
     *   send            function(businessPayload)   -> writes to the relay
     *   log             function(message)
     *   idBase          number (default 0x100000)
     *   maxWorkspaces   number (default 12)
     *   subscribeAll    boolean — false keeps the client in passive-only mode
     *
     * Events (assign callbacks):
     *   onSessions(update)   {key, title, workspacePath, workspaceIdentity,
     *                         source, sessions:[...]}
     *   onStatus(status)     {active, passive, workspaces, bridges, reason}
     */
    function RemoteClient(options) {
        this._send = options.send;
        this._log = options.log || function () {};
        this._idBase = options.idBase || 0x100000;
        this._maxWorkspaces = options.maxWorkspaces || 12;
        this.subscribeAll = options.subscribeAll !== false;

        this.onSessions = null;
        this.onStatus = null;

        this._pending = {};
        // Two indexes on purpose: workspaces are addressed by key (for
        // lifecycle/status) while inbound rpc-frames are addressed by
        // bridgeSessionId. Mixing them up silently drops every response.
        this._bridges = {};
        this._bridgesById = {};
        this._activeKeys = {};
        // Frames can arrive for a bridge before openBridge's await continuation
        // has attached the transport (the desktop pushes Initialize the moment
        // it accepts the bridge). Anything addressed to a bridge we requested
        // is held here and flushed on attach. Bridges we did NOT request — i.e.
        // the page's own — are never buffered, so this cannot grow on their
        // traffic.
        this._pendingBridgePayloads = {};
        this._inflightOpens = 0;
        this._bridgeSeq = 0;
        this._bridgeGeneration = 0;
        this._clientHello = null;

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
        var sharedMaps = ['passive', 'outboundListenIds', 'bridgeWorkspace',
            'pageBridges', 'pageOwned', 'faultStreak', 'cooldownUntil'];
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
        // Workspaces we know the PAGE has a bridge for. `_passive` only covers
        // the ones where the page also streams a sessions-index; the page's
        // conversation bridge does not send that listen, so keying "do not
        // duplicate this" on `_passive` alone missed it.
        this._pageBridges = (this._shared && this._shared.pageBridges) || {};
        // Workspaces we have given up on because the page holds them and the
        // desktop refused our duplicate. Only ever set on that evidence, so a
        // transient fault can never silently cost us notification coverage.
        this._pageOwned = (this._shared && this._shared.pageOwned) || {};
        // Fault history outlives the client too, and for the opposite reason to
        // `_faultCounts` below: rebuilding the relay connection must NOT hand a
        // repeatedly-refused workspace a clean slate, or the loop never ends.
        this._faultStreak = (this._shared && this._shared.faultStreak) || {};
        this._cooldownUntil = (this._shared && this._shared.cooldownUntil) || {};
        // Faults already counted for this relay connection: one connection earns
        // one strike however many times it reopens the same key within it.
        this._faultedInConnection = {};
        // bridgeSessionIds WE asked for, so a `workspace-bridge-ready` can be
        // attributed to the shell or to the page. Needed because ownership is
        // decided from that reply, and our own reply arrives before the bridge
        // is registered in _bridgesById.
        this._requestedBridgeIds = {};
        // Faults counted per relay connection, deliberately NOT shared: after a
        // reconnect the desktop's state is different, so the workspace gets one
        // more chance. Within one connection a workspace that keeps faulting is
        // not going to be accepted, and every reopen costs 4 RPCs on the socket
        // the page is using. Injectable so a test does not have to wait out the
        // reopen delay.
        this._maxReopens = typeof options.maxReopensPerBridge === 'number' ?
            options.maxReopensPerBridge : MAX_REOPENS_PER_BRIDGE;
        this._faultCounts = {};
        this._started = false;
        this._lastStatus = null;

        // Page-RPC trace: what the page itself asks the desktop for, and how
        // long the desktop takes to answer. The shell's own bridges say nothing
        // about "opening a task hangs", because the conversation request belongs
        // to the page — this is the only place it can be observed.
        // The threshold is injectable so the Node tests do not have to sleep.
        this._pageRpcSlowMs = typeof options.pageRpcSlowMs === 'number' ?
            options.pageRpcSlowMs : PAGE_RPC_SLOW_MS;
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
    RemoteClient.prototype.isStarted = function () {
        return this._started === true;
    };

    /** The state a caller must carry across relay reconnects (see constructor). */
    RemoteClient.prototype.sharedState = function () {
        return {
            passive: this._passive,
            outboundListenIds: this._outboundListenIds,
            bridgeWorkspace: this._bridgeWorkspace,
            pageBridges: this._pageBridges,
            pageOwned: this._pageOwned,
            faultStreak: this._faultStreak,
            cooldownUntil: this._cooldownUntil
        };
    };

    /** How many page RPCs are awaiting a reply right now (0 when idle). */
    RemoteClient.prototype.inFlightPageRpcs = function () {
        var count = 0;
        for (var slot in this._pageRpc.pending) {
            count += 1;
        }
        return count;
    };

    /**
     * Resolves once the page has no request outstanding, or once [untilMs] has
     * passed. Used between the burst's workspaces so the shell's 4 RPCs per
     * workspace do not sit in front of whatever the user just tapped; the
     * deadline is what keeps a page that is never idle from stretching it.
     */
    RemoteClient.prototype.awaitPageIdle = function (untilMs) {
        var self = this;
        return new Promise(function (resolve) {
            var check = function () {
                if (self.inFlightPageRpcs() === 0 || Date.now() >= untilMs) {
                    resolve();
                    return;
                }
                setTimeout(check, BURST_YIELD_POLL_MS);
            };
            check();
        });
    };

    /**
     * Records that the page opened its own bridge for a workspace.
     *
     * On its own this is not enough to drop ours: the page holding a bridge does
     * not prove it streams that workspace's sessions-index, and dropping on a
     * guess would silently cost notification coverage. The decision is made in
     * `_handleDegraded`, where this evidence is combined with the desktop
     * actually refusing our bridge.
     */
    RemoteClient.prototype._notePageBridge = function (key) {
        if (!key || this._pageBridges[key]) {
            return;
        }
        this._pageBridges[key] = true;
        this._log('页面自己持有 bridge：' + key);
    };

    RemoteClient.prototype._emitStatus = function (reason) {
        if (!this.onStatus) {
            return;
        }
        var bridges = 0;
        for (var key in this._bridges) {
            if (!this._bridges[key].closed) {
                bridges += 1;
            }
        }
        var passive = 0;
        for (var p in this._passive) {
            passive += 1;
        }
        var status = {
            active: this.subscribeAll,
            bridges: bridges,
            passive: passive,
            reason: reason || ''
        };
        var json = JSON.stringify(status);
        if (json !== this._lastStatus) {
            this._lastStatus = json;
            this.onStatus(status);
        }
    };

    // ---------------------------------------------------------------- requests

    RemoteClient.prototype._request = function (payload, match, timeoutMs) {
        var self = this;
        var requestId = payload.requestId;
        return new Promise(function (resolve, reject) {
            var timer = setTimeout(function () {
                delete self._pending[requestId];
                reject(new Error('request ' + requestId + ' timed out'));
            }, timeoutMs || 30000);
            self._pending[requestId] = {
                match: match,
                resolve: function (value) {
                    clearTimeout(timer);
                    resolve(value);
                },
                reject: function (err) {
                    clearTimeout(timer);
                    reject(err);
                }
            };
            self._send(payload);
        });
    };

    /** Every inbound business payload must be fed here. */
    RemoteClient.prototype.acceptPayload = function (payload) {
        if (!payload || typeof payload !== 'object') {
            return;
        }
        var type = payload.zcode_type;
        if (type === 'rpc-frame' || type === 'rpc-frame-ack') {
            var bridge = this._bridgesById[payload.bridgeSessionId];
            if (bridge) {
                bridge.acceptPayload(payload);
                return;
            }
            var buffer = this._pendingBridgePayloads[payload.bridgeSessionId];
            if (buffer) {
                if (buffer.length < 128) {
                    buffer.push(payload);
                } else {
                    this._log('pre-attach buffer full for ' + payload.bridgeSessionId);
                }
                return;
            }
            // While a bridge open is in flight the desktop may address frames
            // to a bridgeSessionId it chose itself, so keep a short-lived
            // catch-all that attach() filters by the real id. Outside that
            // window nothing is buffered, so the page's own traffic is ignored.
            if (this._inflightOpens > 0) {
                var catchAll = this._pendingBridgePayloads['*'] ||
                    (this._pendingBridgePayloads['*'] = []);
                if (catchAll.length < 128) {
                    catchAll.push(payload);
                }
            }
            return;
        }
        if (type === 'bridge-degraded') {
            this._handleDegraded(payload);
        }
        // Responses are matched by predicate, not by our requestId: the
        // desktop does not guarantee that a reply echoes it.
        for (var id in this._pending) {
            var pending = this._pending[id];
            var value = null;
            try {
                value = pending.match(payload);
            } catch (e) {
                value = null;
            }
            if (value) {
                delete this._pending[id];
                pending.resolve(value);
                return;
            }
        }
    };

    /** True while a workspace the desktop keeps refusing is being backed off. */
    RemoteClient.prototype._inCooldown = function (key) {
        var until = this._cooldownUntil[key];
        return typeof until === 'number' && until > Date.now();
    };

    /**
     * One strike per relay connection for a workspace the desktop degraded, and
     * the cooldown once strikes run out.
     *
     * `_faultCounts` only bounds the churn inside one connection; a relay rebuild
     * used to reset it, so a workspace the desktop refuses on every connection was
     * retried on every connection. Striking across connections is what turns that
     * into a back-off. The count is cleared when the cooldown is applied, so the
     * attempt after it starts from zero rather than cooling down on its first
     * fault.
     *
     * Returns true when the caller must not reopen the key.
     */
    RemoteClient.prototype._noteFault = function (key) {
        if (this._faultedInConnection[key]) {
            return this._inCooldown(key);
        }
        this._faultedInConnection[key] = true;
        var streak = (this._faultStreak[key] || 0) + 1;
        if (streak < FAULT_COOLDOWN_CONNECTIONS) {
            this._faultStreak[key] = streak;
            return false;
        }
        delete this._faultStreak[key];
        this._cooldownUntil[key] = Date.now() + FAULT_COOLDOWN_MS;
        this._log('冷却 ' + key + '：连续 ' + streak + ' 条 relay 连接都被桌面端 fault，' +
            Math.round(FAULT_COOLDOWN_MS / 60000) + ' 分钟内不再为它开 bridge（到期自动重试）');
        return true;
    };

    RemoteClient.prototype._handleDegraded = function (payload) {
        var bridgeSessionId = payload.bridgeSessionId;
        var reason = payload.reason || 'unknown';
        // The desktop sometimes explains the fault in the same payload; without
        // it a repeated fault on one workspace is indistinguishable from any other.
        var detail = payload.message || payload.error || payload.detail;
        for (var key in this._bridges) {
            var bridge = this._bridges[key];
            if (bridge.bridgeSessionId !== bridgeSessionId) {
                continue;
            }
            this._log('bridge degraded for ' + key + ': ' + reason +
                (detail ? ' · ' + detail : ''));
            // Two pieces of evidence together mean "we cannot have this one":
            // the page opened its own bridge for the workspace, AND the desktop
            // is refusing ours. Only then do we give up on it for good — a bare
            // fault is not proof, and treating it as proof would silently cost
            // notification coverage for a healthy workspace.
            if (this._pageBridges[key] && !this._pageOwned[key]) {
                this._pageOwned[key] = true;
                this._log('放弃 ' + key + '：页面自己持有该工作区，桌面端拒绝我们的重复 bridge（通知改由页面侧覆盖）');
                this._forgetBridge(key);
                this._emitStatus('degraded ' + key);
                return;
            }
            if (this._noteFault(key)) {
                this._forgetBridge(key);
                this._emitStatus('degraded ' + key);
                return;
            }
            this._faultCounts[key] = (this._faultCounts[key] || 0) + 1;
            if (this._faultCounts[key] > this._maxReopens) {
                // No alternative evidence, but the desktop keeps rejecting it on
                // this relay connection (observed on `default`: once every ~47s,
                // on and on). Every reopen costs 4 RPCs on the socket the page is
                // using, so stop for this connection; the next relay connection
                // gives it one more chance, since the desktop's state may differ.
                this._log('本次连接放弃重开 ' + key + '（已 fault ' + this._faultCounts[key] + ' 次）');
                this._forgetBridge(key);
                this._emitStatus('degraded ' + key);
                return;
            }
            this._scheduleReopen(key, 1);
        }
    };

    RemoteClient.prototype.listWorkspaces = function () {
        var requestId = randomId('zcshell-ws');
        var self = this;
        return this._request({
            zcode_type: 'workspace-list-request',
            requestId: requestId
        }, function (payload) {
            if (payload.zcode_type !== 'workspace-list-response') {
                return null;
            }
            if (payload.requestId !== requestId) {
                return null;
            }
            var result = payload.result;
            if (Array.isArray(result)) {
                return result;
            }
            if (result && Array.isArray(result.workspaces)) {
                return result.workspaces;
            }
            return [];
        }, 20000).then(function (list) {
            self._log('workspace list: ' + list.length);
            return list;
        });
    };

    RemoteClient.prototype.openBridge = function (workspace, generation, recoveryId) {
        var key = workspaceKeyOf(workspace);
        if (!key) {
            return Promise.reject(new Error('workspace has no key'));
        }
        var self = this;
        var bridgeSessionId = randomId('zcshell-bridge');
        var requestId = randomId('zcshell-bopen');
        var scope = { workspacePath: workspace.workspacePath };
        if (workspace.workspaceIdentity) {
            scope.workspaceIdentity = workspace.workspaceIdentity;
        }
        var payload = {
            zcode_type: 'workspace-bridge-open',
            requestId: requestId,
            bridgeSessionId: bridgeSessionId,
            bridgeGeneration: generation,
            workspaceKey: key
        };
        if (recoveryId) {
            payload.recoveryId = recoveryId;
        }
        // Start buffering before the request goes out: the desktop may push
        // Initialize before this promise resolves.
        this._pendingBridgePayloads[bridgeSessionId] = [];
        this._requestedBridgeIds[bridgeSessionId] = true;
        this._inflightOpens += 1;
        return this._request(payload, function (reply) {
            if (reply.bridgeSessionId !== bridgeSessionId) {
                return null;
            }
            if (reply.zcode_type === 'workspace-bridge-error') {
                return { error: String(reply.error || 'workspace-bridge-error') };
            }
            if (reply.zcode_type === 'workspace-bridge-ready') {
                return { info: reply.bridge || {} };
            }
            return null;
        }, 30000).then(function (reply) {
            if (reply.error) {
                self._releaseInflight(bridgeSessionId, null);
                throw new Error(reply.error);
            }
            var info = reply.info;
            var bridge = new Bridge({
                key: key,
                scope: scope,
                info: info,
                bridgeSessionId: info.bridgeSessionId || bridgeSessionId,
                generation: typeof info.bridgeGeneration === 'number' ?
                    info.bridgeGeneration : generation,
                recoveryId: info.recoveryId
            });
            bridge.attach(function (out) {
                self._send(out);
            }, {
                onLog: function (message) {
                    self._log('[' + key + '] ' + message);
                },
                idBase: self._idBase
            });
            self._bridges[key] = bridge;
            self._bridgesById[bridge.bridgeSessionId] = bridge;
            self._log('bridge ready for ' + key + ' (' + bridge.bridgeSessionId + ')');
            // Replay whatever arrived while the bridge was still being set up;
            // this is where the desktop's Initialize frame usually comes from.
            var buffered = self._releaseInflight(bridgeSessionId, bridge.bridgeSessionId);
            for (var i = 0; i < buffered.length; i++) {
                bridge.acceptPayload(buffered[i]);
            }
            self._emitStatus('bridge open ' + key);
            return bridge;
        }, function (err) {
            self._releaseInflight(bridgeSessionId, null);
            throw err;
        });
    };

    /**
     * Ends the "bridge open in flight" window and returns every frame buffered
     * for it, in arrival order. Must run on the failure path too, otherwise the
     * catch-all buffer would keep growing on the page's own traffic.
     */
    RemoteClient.prototype._releaseInflight = function (requestedId, actualId) {
        var out = (this._pendingBridgePayloads[requestedId] || []).slice();
        delete this._pendingBridgePayloads[requestedId];
        if (actualId && actualId !== requestedId) {
            out = out.concat(this._pendingBridgePayloads[actualId] || []);
            delete this._pendingBridgePayloads[actualId];
        }
        var catchAll = this._pendingBridgePayloads['*'];
        if (catchAll) {
            for (var i = 0; i < catchAll.length; i++) {
                if (catchAll[i].bridgeSessionId === requestedId ||
                    (actualId && catchAll[i].bridgeSessionId === actualId)) {
                    out.push(catchAll[i]);
                }
            }
        }
        this._inflightOpens = Math.max(0, this._inflightOpens - 1);
        if (this._inflightOpens === 0) {
            delete this._pendingBridgePayloads['*'];
        }
        // Stop claiming this id: anything that arrives for it now belongs to a
        // bridge whose ownership is already settled.
        delete this._requestedBridgeIds[requestedId];
        if (actualId) {
            delete this._requestedBridgeIds[actualId];
        }
        return out;
    };

    /**
     * Handshake + subscribe + listen. The desktop gates V4 capabilities on the
     * clientHello exchange, so it must run before subscribeSessionsIndexV4.
     */
    RemoteClient.prototype.subscribeSessionsIndex = function (bridge) {
        var self = this;
        var state = new SessionsIndexState();
        var hello = this._clientHello || DEFAULT_CLIENT_HELLO;
        var cleanup = {
            listener: null,
            subscriptionId: null
        };

        var run = bridge.channels
            .call(CHANNEL_CONVERSATION, 'helloConversationV4', [], 45000)
            .then(function () {
                return bridge.channels.call(CHANNEL_CONVERSATION, 'initializeConversationV4', [{
                    kind: 'clientHello',
                    protocolVersion: hello.protocolVersion,
                    clientId: randomId('zcshell-client'),
                    clientKind: hello.clientKind,
                    appVersion: hello.appVersion
                }], 45000);
            })
            .then(function () {
                var args = {};
                for (var k in bridge.scope) {
                    args[k] = bridge.scope[k];
                }
                args.runtimePolicy = 'existing-only';
                return bridge.channels.call(CHANNEL_CONVERSATION, METHOD_SUBSCRIBE_SI, [args], 60000);
            })
            .then(function (result) {
                var ack = result && result.ack ? result.ack : null;
                cleanup.subscriptionId = ack && ack.subscriptionId ? ack.subscriptionId : null;
                if (!cleanup.subscriptionId) {
                    throw new Error('subscribeSessionsIndexV4: no ack.subscriptionId');
                }
                cleanup.listener = bridge.channels.addEventListener(
                    CHANNEL_CONVERSATION,
                    EVENT_SESSIONS_INDEX,
                    bridge.scope,
                    function (data) {
                        if (!data || typeof data !== 'object') {
                            return;
                        }
                        if (!data.topic || String(data.topic).indexOf('sessions-index/') !== 0) {
                            return;
                        }
                        if (state.applyWireFrame(data)) {
                            self._emitSessions(bridge.key, bridge, state, 'active');
                        }
                        if (state.needsResync) {
                            state.needsResync = false;
                            self._resyncSessionsIndex(bridge, cleanup.subscriptionId, state);
                        }
                    }
                );
                self._log('subscribed sessions-index for ' + bridge.key);
                return {
                    key: bridge.key,
                    scope: bridge.scope,
                    state: state,
                    dispose: function () {
                        if (cleanup.listener) {
                            cleanup.listener.dispose();
                            cleanup.listener = null;
                        }
                        if (!cleanup.subscriptionId) {
                            return Promise.resolve();
                        }
                        var args = {};
                        for (var k in bridge.scope) {
                            args[k] = bridge.scope[k];
                        }
                        args.subscriptionId = cleanup.subscriptionId;
                        args.runtimePolicy = 'existing-only';
                        return bridge.channels
                            .call(CHANNEL_CONVERSATION, METHOD_UNSUBSCRIBE_SI, [args], 15000)
                            .catch(function () {});
                    }
                };
            });

        return run;
    };

    RemoteClient.prototype._resyncSessionsIndex = function (bridge, subscriptionId, state) {
        var args = {};
        for (var k in bridge.scope) {
            args[k] = bridge.scope[k];
        }
        args.subscriptionId = subscriptionId;
        args.runtimePolicy = 'existing-only';
        if (state.logEpoch) {
            args.base = { logEpoch: state.logEpoch, seq: state.seq };
        }
        this._log('resync sessions-index for ' + bridge.key + ' (gap at seq ' + state.seq + ')');
        bridge.channels
            .call(CHANNEL_CONVERSATION, METHOD_RESYNC_SI, [args], 30000)
            .catch(function (err) {
                bridge._logResyncFailed = String(err);
            });
    };

    RemoteClient.prototype._emitSessions = function (key, bridge, state, source) {
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
            sessions: state.list()
        });
    };

    // ------------------------------------------------------------------ active

    RemoteClient.prototype._scheduleReopen = function (key, attempt) {
        var self = this;
        var bridge = this._bridges[key];
        if (!bridge || bridge.closed) {
            return;
        }
        bridge.closed = true;
        var workspace = {
            workspacePath: bridge.scope.workspacePath,
            workspaceIdentity: bridge.scope.workspaceIdentity
        };
        if (attempt > 3) {
            this._log('giving up on ' + key + ' after ' + attempt + ' attempts');
            this._forgetBridge(key);
            this._emitStatus('degraded ' + key);
            return;
        }
        var delay = Math.min(30000, 2000 * Math.pow(2, attempt - 1));
        this._log('reopening ' + key + ' in ' + delay + 'ms (attempt ' + attempt + ')');
        setTimeout(function () {
            if (!self.subscribeAll || self._passive[key] || self._pageOwned[key] ||
                self._inCooldown(key)) {
                return;
            }
            self.openBridge(workspace, ++self._bridgeGeneration, bridge.recoveryId)
                .then(function (next) {
                    return self.subscribeSessionsIndex(next);
                })
                .then(function () {
                    self._log('recovered ' + key);
                    self._emitStatus('recovered ' + key);
                })
                .catch(function (err) {
                    self._log('reopen failed for ' + key + ': ' + err);
                    self._scheduleReopen(key, attempt + 1);
                });
        }, delay);
    };

    /**
     * Starts (or refreshes) active coverage: one bridge + one sessions-index
     * subscription per workspace the page is not already covering.
     */
    RemoteClient.prototype.start = function () {
        var self = this;
        if (this._started) {
            return Promise.resolve();
        }
        this._started = true;
        if (!this.subscribeAll) {
            this._emitStatus('passive only (subscribe-all off)');
            return Promise.resolve();
        }
        var startedAt = Date.now();
        return this.listWorkspaces().then(function (list) {
            var targets = [];
            var skipped = 0;
            var cooled = 0;
            for (var i = 0; i < list.length; i++) {
                var workspace = list[i];
                var key = workspaceKeyOf(workspace);
                if (!key || targets.length >= self._maxWorkspaces) {
                    continue;
                }
                if (self._inCooldown(key)) {
                    // This desktop refused the workspace on several consecutive
                    // relay connections. Re-opening it here is the churn the
                    // user sees as "正在尝试重连"; wait the cooldown out.
                    cooled += 1;
                    continue;
                }
                if (self._passive[key] || self._pageOwned[key] || self._activeKeys[key]) {
                    // The page already has a bridge for this one; do not
                    // duplicate it. Opening a second bridge for a workspace the
                    // page owns is what the desktop answers with
                    // rpc-transport-fault, over and over.
                    skipped += 1;
                    continue;
                }
                targets.push(workspace);
            }
            self._log('active subscribe: ' + targets.length + ' workspace(s) of ' + list.length +
                (skipped ? '（跳过 ' + skipped + ' 个页面已覆盖）' : '') +
                (cooled ? '（冷却中 ' + cooled + ' 个）' : ''));
            // Sequential with a small gap: opening a dozen RPC bridges at once
            // hammers the desktop and makes failures hard to attribute. Between
            // workspaces the burst yields while the page has a request in
            // flight, so tapping a task mid-burst does not leave the page's own
            // conversation request queued behind the rest of our handshakes. The
            // yield budget is shared across the whole burst, so this stays
            // bounded.
            var yieldUntil = Date.now() + BURST_YIELD_TOTAL_MS;
            return targets.reduce(function (chain, workspace) {
                return chain.then(function () {
                    return self.awaitPageIdle(yieldUntil);
                }).then(function () {
                    return self._openAndSubscribe(workspace);
                }).then(function () {
                    return new Promise(function (r) {
                        setTimeout(r, 500);
                    });
                });
            }, Promise.resolve());
        }).then(function () {
            // The wall-clock cost of the whole burst is the number that matters:
            // every one of those RPCs is queued on the same relay socket the
            // page is using to open whatever the user just tapped.
            self._log('主动订阅完成：用时 ' + (Date.now() - startedAt) + 'ms');
            self._emitStatus('active started');
        }).catch(function (err) {
            self._log('active subscribe failed: ' + err);
            self._emitStatus('active failed: ' + err);
        });
    };

    /** Re-runs active coverage after a failed or empty first attempt. */
    RemoteClient.prototype.retryStart = function () {
        if (!this.subscribeAll) {
            return Promise.resolve();
        }
        this._started = false;
        return this.start();
    };

    RemoteClient.prototype._openAndSubscribe = function (workspace) {
        var self = this;
        var key = workspaceKeyOf(workspace);
        this._activeKeys[key] = true;
        this._bridgeGeneration += 1;
        return this.openBridge(workspace, this._bridgeGeneration)
            .then(function (bridge) {
                return self.subscribeSessionsIndex(bridge);
            })
            .then(function (sub) {
                self._subs = self._subs || {};
                self._subs[key] = sub;
                self._emitStatus('subscribed ' + key);
            })
            .catch(function (err) {
                delete self._activeKeys[key];
                self._forgetBridge(key);
                self._log('subscribe failed for ' + key + ': ' + err);
                self._emitStatus('subscribe failed ' + key);
            });
    };

    /** Drops both indexes for a workspace; the frame router keys off the id. */
    RemoteClient.prototype._forgetBridge = function (key) {
        var bridge = this._bridges[key];
        delete this._bridges[key];
        delete this._activeKeys[key];
        if (bridge) {
            bridge.closed = true;
            delete this._bridgesById[bridge.bridgeSessionId];
        }
    };

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
                // A ready frame for an id we never requested is the page opening
                // its own bridge. Recorded, not acted on: dropping ours on this
                // alone could lose coverage (see _notePageBridge).
                if (!this._requestedBridgeIds[payload.bridgeSessionId]) {
                    this._notePageBridge(info.workspaceKey);
                }
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
    RemoteClient.prototype._tracePageCall = function (bridgeSessionId, header) {
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
        if (type !== RES_PROMISE_SUCCESS) {
            this._pageRpc.errors += 1;
            this._pageRpc.windowErrors += 1;
            var message = '';
            try {
                message = data && typeof data === 'object' && data.message ?
                    String(data.message) : (typeof data === 'string' ? data : JSON.stringify(data));
            } catch (e) {
                message = '';
            }
            this._log('页面调用失败 ' + cost + 'ms：' + call.name +
                (message ? ' · ' + String(message).substring(0, 160) : ''));
            return;
        }
        if (cost >= this._pageRpcSlowMs) {
            this._pageRpc.slow += 1;
            this._pageRpc.windowSlow += 1;
            if (this._pageRpc.slowLogged < PAGE_RPC_SLOW_LOG_MAX) {
                this._pageRpc.slowLogged += 1;
                this._log('页面调用慢 ' + cost + 'ms：' + call.name +
                    (this._pageRpc.slowLogged === PAGE_RPC_SLOW_LOG_MAX ?
                        '（后续慢调用只计入窗口汇总）' : ''));
            }
        }
    };

    /** 每个心跳窗口一条有上限的汇总；这一窗口没有任何页面调用时保持安静。 */
    RemoteClient.prototype.reportPageRpcWindow = function () {
        var rpc = this._pageRpc;
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

    /**
     * 页面确实在流这个工作区，那我们自己的 bridge 就是重复的。桌面端会用
     * rpc-transport-fault 拒掉重复项，而那个 fault 会触发重开循环——循环打的正是
     * 用户此刻正在看的工作区。所以一旦页面证明它自己覆盖了这个工作区，就撤掉我们的。
     * 通知覆盖不会丢：被动侧继续从页面的流量里读 sessions-index。
     */
    RemoteClient.prototype._dropRedundantBridge = function (key) {
        var bridge = this._bridges[key];
        if (!bridge || bridge.closed) {
            return;
        }
        var sub = this._subs ? this._subs[key] : null;
        if (sub) {
            delete this._subs[key];
            try {
                var done = sub.dispose();
                if (done && typeof done.catch === 'function') {
                    done.catch(function () {});
                }
            } catch (e) {
                // 无论如何都要撤掉这个 bridge，取消失败不影响结论
            }
        }
        this._forgetBridge(key);
        this._log('页面已接管 ' + key + '，关闭重复 bridge');
    };

    RemoteClient.prototype._observeOutboundRpc = function (payload) {
        if (this._bridgesById[payload.bridgeSessionId]) {
            return;
        }
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
            // Learn the page's clientHello so our own handshake agrees with
            // whatever protocol version the desktop negotiated with it.
            if (header[3] === 'initializeConversationV4' && Array.isArray(args) &&
                args[0] && args[0].kind === 'clientHello') {
                this._clientHello = {
                    protocolVersion: args[0].protocolVersion,
                    appVersion: args[0].appVersion,
                    clientKind: args[0].clientKind
                };
            }
            this._tracePageCall(payload.bridgeSessionId, header);
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
        if (!this._passive[key]) {
            this._passive[key] = {
                key: key,
                scope: scope,
                state: new SessionsIndexState()
            };
            this._log('passive: following sessions-index of ' + key);
            this._emitStatus('passive tracking ' + key);
        }
        this._dropRedundantBridge(key);
    };

    RemoteClient.prototype._observeInboundRpc = function (payload) {
        if (this._bridgesById[payload.bridgeSessionId]) {
            return;
        }
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
        if (entry.state.applyWireFrame(data)) {
            this._emitSessions(key, {
                scope: entry.scope
            }, entry.state, 'passive');
        }
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

    RemoteClient.prototype.takeObservedCounts = function () {
        var counts = { passive: 0, active: 0 };
        for (var p in this._passive) {
            counts.passive += 1;
        }
        for (var a in this._bridges) {
            if (!this._bridges[a].closed) {
                counts.active += 1;
            }
        }
        return counts;
    };

    RemoteClient.prototype.dispose = function () {
        this.subscribeAll = false;
        for (var key in this._bridges) {
            this._bridges[key].closed = true;
        }
        this._bridges = {};
        this._bridgesById = {};
        this._activeKeys = {};
        this._pending = {};
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
