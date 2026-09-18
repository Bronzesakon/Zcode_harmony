#!/usr/bin/env node
/**
 * cdp-capture.mjs — 抓页面自己那一轮"接入"的网络契约（HTTP + WebSocket 帧）。
 *
 * 目的：原生承载要补 window 控制面（docs/16 §8.3 的五步），但那条链路的**确切形状**
 * 只有一个权威来源——页面自己。本脚本用 CDP 的 Network 域：
 *   1) 打开 Network/Page，重载页面；
 *   2) 记录所有打到 `/api/remote-control/` 的请求（方法、URL、状态、响应体）；
 *   3) 记录 window 控制面那条 WebSocket 的收发帧（`/ws/remote-control/window/…`）。
 *
 * **凭证处理**：URL 查询串里的值、`token`、`mobileConnectionId`、`windowControlSessionId`
 * 一律按占位符打印（只留长度），不落任何原文——与 shell 侧 `Diagnostics.redact` 同一口径。
 *
 * 用法：
 *   node tools/cdp-capture.mjs [--seconds N] [--noreload]
 * 前置：`wvdebug_on` + `node tools/cdp.mjs forward`（本脚本自动 forward 一次）。
 */

import {execFileSync} from 'node:child_process';

const PORT = Number(process.env.CDP_PORT || 9222);
const args = process.argv.slice(2);
const seconds = Number((args[args.indexOf('--seconds') + 1] || 25));
const noreload = args.includes('--noreload');

function adb(argv) {
    return execFileSync(process.env.ADB || 'adb', argv, {encoding: 'utf8'}).trim();
}

function redactUrl(raw) {
    const s = String(raw);
    const q = s.indexOf('?');
    if (q < 0) {
        return s;
    }
    const head = s.slice(0, q);
    const params = s.slice(q + 1).split('&').map((kv) => {
        const i = kv.indexOf('=');
        if (i < 0) {
            return kv;
        }
        return `${kv.slice(0, i)}=<${kv.slice(i + 1).length}字>`;
    });
    return head + '?' + params.join('&');
}

/** 把可能含凭证的字段名换成占位符（只保留长度与类型）。 */
function redactObj(obj, depth = 0) {
    if (obj === null || obj === undefined) {
        return obj;
    }
    if (typeof obj === 'string') {
        return obj.length > 80 ? `<${obj.length}字>` : obj;
    }
    if (typeof obj !== 'object' || depth > 3) {
        return obj;
    }
    if (Array.isArray(obj)) {
        return obj.slice(0, 6).map((v) => redactObj(v, depth + 1));
    }
    const secret = /(token|sid|hash|mid|secret|proof|nonce|sessionid|connectionid|password|key)/i;
    const out = {};
    for (const [k, v] of Object.entries(obj)) {
        out[k] = secret.test(k) ? `<${String(v).length}字>` : redactObj(v, depth + 1);
    }
    return out;
}

async function main() {
    // 自动 forward（socket 名带 pid，重启后会变）
    try {
        const pid = adb(['shell', 'pidof', 'com.zcode.remote']).split(/\s+/)[0];
        adb(['forward', `tcp:${PORT}`, `localabstract:webview_devtools_remote_${pid}`]);
        console.log(`forward ok (pid=${pid})`);
    } catch (e) {
        console.log('forward 失败，沿用已有转发: ' + e.message);
    }

    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    const page = targets.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl)
        .find((t) => String(t.url).includes('/remote/'));
    if (!page) {
        throw new Error('没有 /remote/ 页面目标');
    }
    console.log('target: ' + redactUrl(page.url));

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    let seq = 0;
    const waiters = new Map();
    const bodies = new Map();      // requestId -> {url, method}
    const lines = [];

    ws.addEventListener('message', (ev) => {
        let msg;
        try {
            msg = JSON.parse(ev.data);
        } catch (e) {
            return;
        }
        if (msg.id && waiters.has(msg.id)) {
            const w = waiters.get(msg.id);
            waiters.delete(msg.id);
            w(msg);
            return;
        }
        const p = msg.params;
        if (!p) {
            return;
        }
        if (msg.method === 'Network.requestWillBeSent') {
            const url = String(p.request.url);
            if (url.includes('/api/remote-control/')) {
                bodies.set(p.requestId, {url, method: p.request.method});
                lines.push(`→ ${p.request.method} ${redactUrl(url)}`);
                const hdrs = p.request.headers || {};
                const interesting = Object.keys(hdrs)
                    .filter((k) => /mobile-connection|content-type|authorization/i.test(k));
                if (interesting.length) {
                    lines.push('   headers: ' + interesting.map((k) =>
                        `${k}=${/connection/i.test(k) ? `<${String(hdrs[k]).length}字>` : hdrs[k]}`).join(' '));
                }
                if (p.request.postData) {
                    lines.push('   body: ' + String(p.request.postData).slice(0, 200));
                }
            }
        } else if (msg.method === 'Network.responseReceived') {
            const meta = bodies.get(p.requestId);
            if (meta) {
                lines.push(`← ${p.response.status} ${redactUrl(meta.url)}` +
                    (p.response.mimeType ? ` (${p.response.mimeType})` : ''));
            }
        } else if (msg.method === 'Network.loadingFinished') {
            const meta = bodies.get(p.requestId);
            if (meta) {
                bodies.delete(p.requestId);
                send('Network.getResponseBody', {requestId: p.requestId}).then((r) => {
                    if (r && r.result && r.result.body) {
                        let body = r.result.body;
                        try {
                            body = JSON.stringify(redactObj(JSON.parse(body)));
                        } catch (e) {
                            body = String(body).slice(0, 200);
                        }
                        lines.push('   resp: ' + String(body).slice(0, 300));
                    }
                }).catch(() => {});
            }
        } else if (msg.method === 'Network.webSocketCreated') {
            if (String(p.url).includes('remote-control')) {
                lines.push(`WS 建立: ${redactUrl(p.url)}`);
            }
        } else if (msg.method === 'Network.webSocketFrameSent' ||
            msg.method === 'Network.webSocketFrameReceived') {
            const dir = msg.method.endsWith('Sent') ? 'WS →' : 'WS ←';
            const data = (p.response && p.response.payloadData) || '';
            if (data.length > 400) {
                lines.push(`${dir} <${data.length}字>`);
                return;
            }
            let shown = data;
            try {
                shown = JSON.stringify(redactObj(JSON.parse(data)));
            } catch (e) {
                // 非 JSON：按原样（多半是控制帧）
            }
            lines.push(`${dir} ${shown}`);
        }
    });

    function send(method, params) {
        const id = ++seq;
        return new Promise((resolve) => {
            waiters.set(id, resolve);
            ws.send(JSON.stringify({id, method, params: params || {}}));
        });
    }

    await new Promise((resolve) => ws.addEventListener('open', resolve));
    await send('Network.enable', {maxTotalBufferSize: 20 * 1024 * 1024, maxResourceBufferSize: 5 * 1024 * 1024});
    await send('Page.enable');
    console.log(noreload ? '不重载，监听 ' + seconds + 's…' : `重载页面，监听 ${seconds}s…`);
    if (!noreload) {
        await send('Page.reload', {ignoreCache: false});
    }
    await new Promise((r) => setTimeout(r, seconds * 1000));
    console.log('================ 抓包结果 ================');
    console.log(lines.length ? lines.join('\n') : '（没有命中 /api/remote-control/ 或 window socket 的流量）');
    ws.close();
}

main().catch((e) => {
    console.error(String(e && e.message ? e.message : e));
    process.exit(1);
});
