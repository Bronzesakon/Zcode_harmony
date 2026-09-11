#!/usr/bin/env bash
# 真机验收一键脚本 —— 屏幕锁着也能跑，因为没有一项依赖"看得见"。
#
# 为什么要有它：这个工程的编译只能在 CI（本机没有 JDK/SDK），所以每次改动的验证成本
# 都落在"把手机接上、按顺序敲十来条 adb"上。顺序错了（比如没清日志就装包）就会读到上一轮
# 的结论，这个脚本把它固定下来。
#
# 用法：
#   bash tools/device_check.sh                  # 用默认 serial
#   ADB=/path/to/adb S=192.168.0.185:45903 bash tools/device_check.sh
#
# 做完这些之后仍然需要"用眼睛看"的（脚本会提醒）：状态栏与网页顶面有没有接缝、
# MiuiX 观感、长按菜单的图标外观、键盘上抬、点进任务时那张「已完成」卡片的弹出。

set -uo pipefail

ADB="${ADB:-C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe}"
SERIAL="${S:-192.168.0.185:45903}"
APK_URL="${APK_URL:-https://github.com/Bronzesakon/Zcode_harmony/releases/download/android-pre/zcode-remote.apk}"
REMOTE_LOG="/sdcard/Android/data/com.zcode.remote/files/logs/zcode-shell.log"

# 本机坑：Git Bash 会把 /sdcard/... 改写成 Windows 路径，设备端命令必须关掉它。
msys() { MSYS_NO_PATHCONV=1 "$@"; }
a() { msys "$ADB" -s "$SERIAL" "$@"; }
section() { printf '\n===== %s =====\n' "$1"; }

section "设备"
a devices -l
state="$(msys "$ADB" devices | awk -v s="$SERIAL" '$1 == s {print $2}')"
if [ "$state" != "device" ]; then
  cat <<EOF

设备 $SERIAL 当前状态：'${state:-未连接}'，脚本无法继续。

怎么恢复（顺序）：
  1) 唤醒手机并解锁一次（锁屏时无线调试会随息屏掉线）；
  2) 开启「开发者选项 → 无线调试」（端口每次都变，别照抄旧端口）；
  3) adb connect <手机上显示的 IP:端口>，或直接插 USB；
  4) 重新跑本脚本。
注意：息屏就会断——先把息屏超时设长再开测：
  adb shell settings put system screen_off_timeout 86400000
EOF
  exit 1
fi

section "当前版本"
a shell dumpsys package com.zcode.remote | grep -m1 versionName

section "从滚动预发布取包并覆盖安装"
if [ "${SKIP_INSTALL:-}" = "1" ]; then
  echo "（SKIP_INSTALL=1，跳过）"
else
  tmp="$(mktemp -d)"
  curl -fsSL --max-time 180 -o "$tmp/zcode-remote.apk" "$APK_URL" || { echo "下载失败（github 间歇不可达，重试即可）"; exit 1; }
  msys "$ADB" -s "$SERIAL" install -r -g "$tmp/zcode-remote.apk"
fi

section "每轮验证前清日志（否则会把上一轮的结论当成本轮）"
a shell "rm -f $REMOTE_LOG*"

section "启动（屏幕锁着也能起进程并跑注入层）"
a shell am start -n com.zcode.remote/.MainActivity
sleep 8

section "状态栏底色：应看到 boot/… 与 main-header/… 两类"
a shell "grep -E '状态栏底色' $REMOTE_LOG | tail -5"

section "页面状态观察器是否装上（没有这一行才正常；出现即降级）"
a shell "grep -E '页面状态观察器' $REMOTE_LOG | tail -3"

section "fault 收敛：reopening 应为 0"
a shell "echo reopening=\$(grep -c reopening $REMOTE_LOG) 放弃重开=\$(grep -c 本次连接放弃重开 $REMOTE_LOG) 冷却=\$(grep -c 冷却 $REMOTE_LOG)"
a shell "grep -E 'rpc-transport-fault|放弃重开|冷却' $REMOTE_LOG | tail -5"

section "长按快捷方式（重新扫码 / 打开设置）"
a shell cmd shortcut get-shortcuts --user 0 com.zcode.remote | grep -E 'id=|shortLabel=|longLabel=|iconRes=|act='

section "设置页能不能起来（借快捷方式那条 intent，不需要点桌面）"
a shell am start -a com.zcode.remote.action.OPEN_SETTINGS -n com.zcode.remote/.MainActivity
sleep 3
a shell "logcat -d -t 200 | grep -iE 'FATAL|InflateException' | tail -5"

section "通知内容：标题应是「状态 · 任务名」，正文只留进展"
a shell "dumpsys notification --noredact | grep -E 'android.title=|android.text=|android.shortCriticalText|PROMOTED' | head -16"

section "屏幕内的实际边界（替代截图：WebView 顶边应 = 状态栏高，底边应 = 屏幕高）"
a shell "dumpsys activity top" | sed -n '/MainActivity/,/Looper/p' | grep -E 'WebView|#root|setupPanel|errorPanel' | head -6

section "回前台后再读一次（应出现 app foreground = true 与一份存活检查）"
a shell "grep -E 'app foreground|后台存活检查' $REMOTE_LOG | tail -4"

cat <<'NOTE'

===== 仍然需要"用眼睛看"的部分（脚本代替不了）=====
1. 状态栏那条带子与网页顶面之间有没有接缝（开屏 / 控制页 / 错误页各看一次）；
2. MiuiX 设置页的观感（浅色 + 深色）；
3. 长按图标菜单里两个快捷方式的图标是否正常；
4. 键盘上抬后页面输入框是否仍然可用；
5. 页面底部输入区被手势条盖住多少（不满意就加回一点底部内边距）；
6. 等一次任务真正完成，看那张「已完成 · …」卡片是否弹出、并在 15 秒后收起。
   判据：日志出现 `任务完成: <任务名>`，且 dumpsys notification 里出现标题以「已完成 · 」开头的记录。
NOTE
