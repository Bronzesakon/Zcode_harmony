package com.zcode.remote

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.ShellLog

/**
 * Process entry point: brings up the log file (with its crash handler) and feeds
 * app foreground transitions to [ShellRuntime], which is what turns "was the
 * connection still alive while I was in another app?" into a measurable result.
 *
 * Foreground state is tracked at the Application level rather than per Activity
 * so it stays correct if the app ever grows a second screen.
 */
class ZcodeRemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Material 3 推荐做法：Android 12+ 采用系统/壁纸派生的动态配色，低版本回落到
        // 主题里的基础配色。这一步同时解决两件事——配色对比度符合无障碍标准，以及
        // 暗色模式自动正确（基础配色是 DayNight 的，两套都齐全）。
        //
        // 设置页是唯一的例外：它整页是 MiuiX 调色板，而动态配色会把 colorPrimary /
        // colorOnPrimary / colorSurfaceContainerHighest / colorOutline 这些**角色**换成
        // 壁纸派生色——按角色取色的控件（MaterialSwitch 等）于是又变回另一套蓝，一页两种蓝。
        // 动态配色是以 ThemeOverlay 形式套在主题**之上**的，优先级高于主题里显式写的角色值，
        // 所以在 themes.xml 里对着盖没有用，只能在源头排除这一页。
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                // Two parameters, not one: Material's Precondition is
                // `shouldApplyDynamicColors(Activity, @ColorScheme int)`. The
                // scheme argument is the one it would have used, which this app
                // has no opinion about.
                .setPrecondition { activity, _ -> activity !is SettingsActivity }
                .build(),
        )
        ShellLog.init(this)
        ShellRuntime.init(this)
        Diagnostics.info("ZCode 远程启动")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) {
                    ShellRuntime.onAppForegroundChanged(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    Diagnostics.info("应用进入后台，保活服务继续运行")
                    ShellRuntime.onAppForegroundChanged(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onTerminate() {
        // Only reached in emulated processes, but it keeps the log tidy.
        ShellLog.markProcessExit("onTerminate")
        super.onTerminate()
    }
}
