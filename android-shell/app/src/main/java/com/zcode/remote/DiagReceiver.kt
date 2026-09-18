package com.zcode.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcode.remote.core.Diagnostics

/**
 * 背景可调用的诊断入口（adb broadcast）。
 *
 * 为什么需要它：诊断指令过去只挂在 [MainActivity] 的 intent 上，而 `am start`
 * 会把已经退到后台的应用**拉回前台**——可见性一变，页面自己的恢复路径立刻跑起来，
 * 于是"后台链路"这件事就没法测了（这正是 2026-09-15 那两轮实测的取证难点）。
 * 广播不碰 Activity 生命周期，所以后台现场可测。
 *
 * 用法：
 * ```
 * adb shell am broadcast -n com.zcode.remote/.DiagReceiver \
 *     -a com.zcode.remote.action.DIAG --es diag_cmd <cmd>
 * ```
 * `-n` 必带：自定义 action 的隐式广播送不到清单接收器（Android 8+ 限制）。
 *
 * 安全：清单上给这个接收器挂了 `android.permission.DUMP`，只有持有该权限的调用方
 * （adb shell / root / 系统）能送到，普通应用发不进来（`getSendingUid()` 是隐藏
 * API，公开渠道拿不到，所以用权限门而不是 uid 判据）。这与现状等价——[MainActivity]
 * 本来就是 `exported=true`，同一串指令也能被任意应用用 `am start` 驱动——这里只是
 * 把同一组指令变成"后台可用"。
 */
class DiagReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(MainActivity.EXTRA_DIAG_CMD).orEmpty()
        if (cmd.isEmpty()) {
            Diagnostics.log("warn", "忽略诊断广播：没有 ${MainActivity.EXTRA_DIAG_CMD}")
            return
        }
        Diagnostics.log("info", "诊断广播: $cmd（后台可调用）")
        // 原生侧命令（Tier2 实验、失速自愈开关）自己处理；其余转给注入层。
        if (ShellRuntime.runNativeDiag(cmd)) return
        ShellRuntime.dispatchJsDiag(cmd)
    }
}
