/**
 * 远程控制页 URL 持久化与校验（@ohos.data.preferences）
 *
 * 存储约定（项目文档 §5）：
 * - 文件名 `zcode_remote`，键 `remote_url`，存完整 URL（含全部 query），不做参数级解析/改写。
 * - 校验规则（项目文档 §2）：scheme=https 且 host 属于 z.ai 域 且 path 以 /remote 开头。
 *
 * 安全约定（项目文档 §12）：URL 含会话凭证（sid/hash/mid），严禁写入日志或文档。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { url } from '@kit.ArkTS';

const PREFS_FILE = 'zcode_remote';
const KEY_REMOTE_URL = 'remote_url';

/**
 * 校验扫码/深链结果是否为有效的远程控制页 URL
 * scheme=https 且 host 属于 z.ai 域（z.ai / zcode.z.ai 及其子域）且 path 以 /remote 开头
 */
export function isValidRemoteUrl(target: string): boolean {
  if (!target || target.length === 0) {
    return false;
  }
  try {
    const parsed = url.URL.parseURL(target);
    // WHATWG URL 的 protocol 属性带冒号，值为 "https:"
    if (parsed.protocol !== 'https:') {
      return false;
    }
    const hostname: string = parsed.hostname;
    const isZaiHost: boolean = hostname === 'z.ai' || hostname.endsWith('.z.ai');
    if (!isZaiHost) {
      return false;
    }
    const pathname: string = parsed.pathname || '';
    return pathname.startsWith('/remote');
  } catch (err) {
    return false;
  }
}

/** 读取已保存的远程控制页 URL（无/异常返回空串） */
export function loadRemoteUrl(context: common.Context): string {
  try {
    const prefs = preferences.getPreferencesSync(context, { name: PREFS_FILE });
    const value = prefs.getSync(KEY_REMOTE_URL, '');
    return typeof value === 'string' ? value : '';
  } catch (err) {
    return '';
  }
}

/** 覆盖保存远程控制页 URL（单连接覆盖式模型） */
export function saveRemoteUrl(context: common.Context, target: string): void {
  try {
    const prefs = preferences.getPreferencesSync(context, { name: PREFS_FILE });
    prefs.putSync(KEY_REMOTE_URL, target);
    prefs.flushSync();
  } catch (err) {
    // 持久化失败不阻断热切换，仅当次会话生效
  }
}
