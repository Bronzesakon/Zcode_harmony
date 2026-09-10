package com.zcode.remote

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
