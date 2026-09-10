/**
 * A scripted "desktop" peer for the protocol, shared by the two test suites.
 *
 * It answers the relay-level requests (workspace list, bridge open), speaks the
 * ChannelClient protocol over fragmented rpc-frames, and can push
 * sessions-index envelopes on demand. `deliver` is the only transport-specific
 * part, which is what lets the same mock serve both seams:
 *
 *   * protocol.test.js -> deliver directly into RemoteClient.acceptPayload()
 *   * inject.test.js   -> deliver by dispatching a WebSocket message event
 */
'use strict';

const P = require('../app/src/main/assets/zcode-protocol.js');

const FRAGMENT_BYTES = 512 * 1024;

function ascii(str) {
    const out = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) {
        out[i] = str.charCodeAt(i) & 0xff;
    }
    return out;
}

/** Independent outbound fragmenter (deliberately not RpcFrameSender). */
function fragment(bytes, bridgeSessionId, messageSeq) {
    const count = Math.max(1, Math.ceil(bytes.length / FRAGMENT_BYTES));
    const payloads = [];
    for (let i = 0; i < count; i++) {
        const chunk = bytes.subarray(i * FRAGMENT_BYTES, Math.min((i + 1) * FRAGMENT_BYTES, bytes.length));
        payloads.push({
            zcode_type: 'rpc-frame',
            bridgeSessionId,
            seq: i + 1,
            messageSeq,
            fragmentIndex: i,
            fragmentCount: count,
            messageBytes: bytes.length,
            checksum: {algorithm: 'crc32', value: P.crc32Hex(bytes)},
            dataBase64: P.base64Encode(chunk)
        });
    }
    return payloads;
}

function encodeBody(header, value) {
    const writer = new P.ByteWriter();
    P.encodeValue(writer, header);
    P.encodeValue(writer, value === undefined ? null : value);
    return writer.toBytes();
}

class DesktopCore {
    /** options.deliver(payload) sends a business payload back to the client. */
    constructor(options) {
        this.deliver = options.deliver;
        this.workspaces = [];
        this.bridges = new Map();
        this.subscriptions = [];
        this.sent = [];
        this.acked = [];
        this.messageSeq = 0;
        this.subscribeDelayMs = 0;
        /** workspaceKeys whose bridge open must fail. */
        this.failingWorkspaces = new Set();
        /** Bridge open counter, so each open gets a distinct generation. */
        this.openCount = 0;
    }

    /** Entry point: the client sent us a business payload. */
    accepts(payload) {
        this.sent.push(payload);
        this.onSent(payload);
    }

    onSent(payload) {
        switch (payload.zcode_type) {
            case 'workspace-list-request':
                this.deliver({
                    zcode_type: 'workspace-list-response',
                    requestId: payload.requestId,
                    result: this.workspaces
                });
                break;
            case 'workspace-bridge-open':
                this.openCount += 1;
                if (this.failingWorkspaces.has(payload.workspaceKey)) {
                    this.deliver({
                        zcode_type: 'workspace-bridge-error',
                        bridgeSessionId: payload.bridgeSessionId,
                        error: 'no such workspace'
                    });
                    return;
                }
                this.bridges.set(payload.bridgeSessionId, {
                    workspaceKey: payload.workspaceKey,
                    listens: new Map()
                });
                this.deliver({
                    zcode_type: 'workspace-bridge-ready',
                    bridgeSessionId: payload.bridgeSessionId,
                    bridge: {
                        bridgeSessionId: payload.bridgeSessionId,
                        bridgeGeneration: payload.bridgeGeneration,
                        workspaceKey: payload.workspaceKey
                    }
                });
                // The desktop announces channel readiness with Initialize,
                // immediately — before the client's open promise has resolved.
                // That ordering is the race the shell must buffer for.
                this.pushBody(payload.bridgeSessionId, encodeBody([P.RES_INITIALIZE]));
                break;
            case 'rpc-frame':
                this.onRpcFrame(payload);
                break;
            case 'rpc-frame-ack':
                this.acked.push(payload.ackMessageSeq);
                break;
            default:
                break;
        }
    }

    onRpcFrame(payload) {
        const bridge = this.bridges.get(payload.bridgeSessionId);
        if (!bridge) {
            return;
        }
        if (!bridge.assemblies) {
            bridge.assemblies = new Map();
        }
        let assembly = bridge.assemblies.get(payload.messageSeq);
        if (!assembly) {
            assembly = {count: payload.fragmentCount, parts: new Array(payload.fragmentCount)};
            bridge.assemblies.set(payload.messageSeq, assembly);
        }
        assembly.parts[payload.fragmentIndex] = P.base64Decode(payload.dataBase64);
        if (assembly.parts.some((part) => part === undefined)) {
            return;
        }
        bridge.assemblies.delete(payload.messageSeq);
        const reader = new P.ByteReader(P.concatBytes(assembly.parts));
        const header = P.decodeValue(reader);
        const args = reader.remaining > 0 ? P.decodeValue(reader) : null;
        this.onChannelRequest(payload.bridgeSessionId, bridge, header, args);
    }

    onChannelRequest(bridgeSessionId, bridge, header, args) {
        const [reqType, id, channel, name] = header;
        if (reqType === P.REQ_EVENT_LISTEN) {
            bridge.listens.set(id, name);
            return;
        }
        if (channel !== P.CHANNEL_CONVERSATION) {
            this.replyBody(bridgeSessionId, [201, id], {ok: true});
            return;
        }
        switch (name) {
            case 'helloConversationV4':
                this.replyBody(bridgeSessionId, [201, id], {connectionId: 'conn-1'});
                break;
            case 'initializeConversationV4':
                this.replyBody(bridgeSessionId, [201, id], {ok: true});
                break;
            case 'subscribeSessionsIndexV4': {
                const subscriptionId = 'sub-' + (this.subscriptions.length + 1);
                this.subscriptions.push({
                    bridgeSessionId,
                    subscriptionId,
                    workspaceKey: bridge.workspaceKey,
                    scope: args && args[0] ? args[0] : {}
                });
                const fire = () => this.replyBody(bridgeSessionId, [201, id],
                    {ack: {subscriptionId}});
                if (this.subscribeDelayMs) {
                    setTimeout(fire, this.subscribeDelayMs);
                } else {
                    fire();
                }
                break;
            }
            default:
                this.replyBody(bridgeSessionId, [201, id], {ok: true});
                break;
        }
    }

    pushBody(bridgeSessionId, body) {
        this.messageSeq += 1;
        for (const payload of fragment(body, bridgeSessionId, this.messageSeq)) {
            this.deliver(payload);
        }
    }

    replyBody(bridgeSessionId, header, value) {
        this.pushBody(bridgeSessionId, encodeBody(header, value));
    }

    /** Pushes a sessions-index wire envelope to every listener of a workspace. */
    pushSessionsWire(workspaceKey, wire) {
        for (const sub of this.subscriptions) {
            if (sub.workspaceKey !== workspaceKey) {
                continue;
            }
            const bridge = this.bridges.get(sub.bridgeSessionId);
            if (!bridge) {
                continue;
            }
            for (const [listenId, name] of bridge.listens) {
                if (name === P.EVENT_SESSIONS_INDEX) {
                    this.replyBody(sub.bridgeSessionId, [P.RES_EVENT_FIRE, listenId], wire);
                }
            }
        }
    }
}

function snapshotWire(sessions, toSeq, topic) {
    return {
        topic: topic || 'sessions-index/ws-a',
        kind: 'complete',
        frame: {
            toSeq: toSeq === undefined ? 1 : toSeq,
            payload: {
                kind: 'snapshot',
                snapshot: {workspaceId: 'ws-a', logEpoch: 'epoch-1', sessions: sessions}
            }
        }
    };
}

module.exports = {DesktopCore, fragment, encodeBody, ascii, snapshotWire};
