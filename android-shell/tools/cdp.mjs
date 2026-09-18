#!/usr/bin/env node
/**
 * cdp.mjs — 用 Chrome DevTools Protocol 驱动真机上 WebView 里的页面。
 *
 * 为什么需要它：**adb 进不了 WebView 的输入框**。`input tap` + `input text` 在真机上
 * 实测无效（README 记过一次，2026-09-16 又复现一次：截图里输入框始终是占位符），
 * 而验收"后台承载把正文推到流体云"必须先能造出一条持续输出的会话。
 * CDP 的 `Input.insertText` / `Input.dispatchKeyEvent` 是**渲染器认的真实输入**，
 * 受控的 contenteditable（React）也会接受；`Runtime.evaluate` 还能直接读页面内部状态。
 *
 * 前置（两步，都在本机跑）：
 *   1) 手机上打开调试开关：
 *      adb shell am broadcast -n com.zcode.remote/.DiagReceiver \
 *          -a com.zcode.remote.action.DIAG --es diag_cmd wvdebug_on
 *   2) 端口转发（socket 名里带 pid，所以要现取）：
 *      adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.zcode.remote)
 *      （本脚本的 `forward` 子命令会自动做这件事，见下）
 *
 * 用法：
 *   node tools/cdp.mjs forward                # 自动 adb forward 到 9222（需要 adb 在 PATH 或 ADB 环境变量）
 *   node tools/cdp.mjs list                   # 列出可调试页面（title/url/id）
 *   node tools/cdp.mjs eval "<js 表达式>"      # 在页面里求值并打印结果（JSON）
 *   node tools/cdp.mjs text "<文本>"           # Input.insertText（插到当前焦点）
 *   node tools/cdp.mjs enter                  # 按一次回车（keyDown+keyUp）
 *   node tools/cdp.mjs click <x> <y>          # 在 CSS 像素坐标点一下（真实鼠标事件）
 *   node tools/cdp.mjs type "<文本>" [--enter] # insertText + 可选回车
 *
 * 退出码：0 成功；1 失败（连不上/超时/表达式抛错）。
 */

import {execFileSync} from 'node:child_process';

const PORT = Number(process.env.CDP_PORT || 9222);
const TIMEOUT_MS = Number(process.env.CDP_TIMEOUT_MS || 15000);

function adb(args) {
    const bin = process.env.ADB || 'adb';
    return execFileSync(bin, args, {encoding: 'utf8'}).trim();
}

async function httpJson(path) {
    const res = await fetch(`http://127.0.0.1:${PORT}${path}`);
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${path}`);
    }
    return res.json();
}

/** 挑页面目标：优先 URL 里带 /remote/ 的那个。 */
function pickTarget(targets) {
    const pages = targets.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl);
    return pages.find((t) => String(t.url).includes('/remote/')) || pages[0] || null;
}

class Cdp {
    constructor(wsUrl) {
        this.ws = new WebSocket(wsUrl);
        this.seq = 0;
        this.pending = new Map();
        this.ws.addEventListener('message', (ev) => {
            let msg;
            try {
                msg = JSON.parse(ev.data);
            } catch (e) {
                return;
            }
            const slot = this.pending.get(msg.id);
            if (slot) {
                this.pending.delete(msg.id);
                slot(msg);
            }
        });
    }

    ready() {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('CDP 连接超时')), TIMEOUT_MS);
            this.ws.addEventListener('open', () => {
                clearTimeout(timer);
                resolve();
            });
            this.ws.addEventListener('error', (e) => {
                clearTimeout(timer);
                reject(new Error('CDP 连接失败: ' + (e.message || 'ws error')));
            });
        });
    }

    send(method, params) {
        const id = ++this.seq;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`CDP ${method} 超时`));
            }, TIMEOUT_MS);
            this.pending.set(id, (msg) => {
                clearTimeout(timer);
                if (msg.error) {
                    reject(new Error(`CDP ${method}: ${JSON.stringify(msg.error)}`));
                    return;
                }
                resolve(msg.result);
            });
            this.ws.send(JSON.stringify({id, method, params: params || {}}));
        });
    }

    close() {
        try {
            this.ws.close();
        } catch (e) {
            // ignore
        }
    }
}

async function withPage(fn) {
    const targets = await httpJson('/json');
    const target = pickTarget(targets);
    if (!target) {
        throw new Error(`9222 上没有可调试页面（先跑 wvdebug_on + forward）。目标数=${targets.length}`);
    }
    const cdp = new Cdp(target.webSocketDebuggerUrl);
    await cdp.ready();
    try {
        return await fn(cdp, target);
    } finally {
        cdp.close();
    }
}

function out(obj) {
    process.stdout.write(typeof obj === 'string' ? obj + '\n' : JSON.stringify(obj, null, 2) + '\n');
}

const [cmd, ...rest] = process.argv.slice(2);

try {
    if (cmd === 'forward') {
        const pid = adb(['shell', 'pidof', 'com.zcode.remote']);
        if (!pid) {
            throw new Error('拿不到 com.zcode.remote 的 pid（应用没在跑？）');
        }
        const socket = `localabstract:webview_devtools_remote_${pid.split(/\s+/)[0]}`;
        adb(['forward', `tcp:${PORT}`, socket]);
        out(`forward tcp:${PORT} -> ${socket}`);
        const targets = await httpJson('/json');
        out(targets.map((t) => ({type: t.type, title: t.title, url: String(t.url).slice(0, 80)})));
    } else if (cmd === 'list') {
        const targets = await httpJson('/json');
        out(targets.map((t) => ({type: t.type, title: t.title, url: String(t.url).slice(0, 80)})));
    } else if (cmd === 'eval') {
        const expr = rest.join(' ');
        if (!expr) {
            throw new Error('用法: eval "<js 表达式>"');
        }
        const r = await withPage((cdp) => cdp.send('Runtime.evaluate', {
            expression: expr,
            returnByValue: true,
            awaitPromise: true,
            userGesture: true
        }));
        if (r.exceptionDetails) {
            throw new Error('页面里抛错: ' + JSON.stringify(r.exceptionDetails).slice(0, 400));
        }
        out(r.result && r.result.value !== undefined ? r.result.value : r.result);
    } else if (cmd === 'text') {
        const text = rest.join(' ');
        await withPage((cdp) => cdp.send('Input.insertText', {text}));
        out(`insertText ${text.length} 字`);
    } else if (cmd === 'enter') {
        await withPage(async (cdp) => {
            for (const type of ['keyDown', 'char', 'keyUp']) {
                await cdp.send('Input.dispatchKeyEvent', {
                    type,
                    key: 'Enter',
                    code: 'Enter',
                    windowsVirtualKeyCode: 13,
                    nativeVirtualKeyCode: 13,
                    text: type === 'char' ? '\r' : undefined
                });
            }
        });
        out('enter 已派发');
    } else if (cmd === 'click') {
        const x = Number(rest[0]);
        const y = Number(rest[1]);
        if (!Number.isFinite(x) || !Number.isFinite(y)) {
            throw new Error('用法: click <x> <y>（CSS 像素，页面视口坐标）');
        }
        await withPage(async (cdp) => {
            for (const type of ['mousePressed', 'mouseReleased']) {
                await cdp.send('Input.dispatchMouseEvent', {
                    type, x, y, button: 'left', clickCount: 1, buttons: 1
                });
            }
        });
        out(`click ${x},${y} 已派发`);
    } else if (cmd === 'type') {
        const wantEnter = rest.includes('--enter');
        const text = rest.filter((a) => a !== '--enter').join(' ');
        await withPage(async (cdp) => {
            await cdp.send('Input.insertText', {text});
            if (wantEnter) {
                for (const type of ['keyDown', 'char', 'keyUp']) {
                    await cdp.send('Input.dispatchKeyEvent', {
                        type,
                        key: 'Enter',
                        code: 'Enter',
                        windowsVirtualKeyCode: 13,
                        nativeVirtualKeyCode: 13,
                        text: type === 'char' ? '\r' : undefined
                    });
                }
            }
        });
        out(`typed ${text.length} 字${wantEnter ? ' + enter' : ''}`);
    } else {
        out(`用法:
  node tools/cdp.mjs forward | list
  node tools/cdp.mjs eval "<js>"
  node tools/cdp.mjs text "<文本>" | enter | click <x> <y>
  node tools/cdp.mjs type "<文本>" [--enter]`);
        process.exit(cmd ? 1 : 0);
    }
} catch (e) {
    process.stderr.write(String(e && e.message ? e.message : e) + '\n');
    process.exit(1);
}
