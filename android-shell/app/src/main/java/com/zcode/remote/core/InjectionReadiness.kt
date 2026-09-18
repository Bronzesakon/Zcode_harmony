package com.zcode.remote.core

/**
 * "注入脚本已就绪"这一位状态（原 `ShellRuntime.injectedReady`）。
 *
 * 含义：**当前这次页面加载**里，注入层已经通过 `ready` 桥消息报到（`inject.js` 装好
 * 顶层 `__zcodeShell*` 之后才发）。读它的人都在问同一句话："现在能不能往页面里派 JS？"
 * ——守门的有两处：`MainActivity` 的定位重试（`tryLocate`）与
 * [ShellRuntime.dispatchJsDiag] 的重试队列（诊断指令**唯一**的队列，两条 adb 通道共用）；
 * 另有设置页的"已就绪/未就绪"只是**显示**，不据此放行。
 *
 * ⚠️ **它的清除点在页面加载回调里**：`MainActivity` 的
 * `WebViewClient.onPageStarted` → `ShellRuntime.onPageStarted()` → [reset]。
 * 历史 bug 就出在这里：清除**只**写在 `ShellRuntime.onPageStarted()` 里，而那个方法当时
 * **一个调用者都没有**，于是首次加载后这一位就永久 latch 在 `true`（守卫放行了根本没装好的
 * 页面、诊断指令发给不存在的函数）。若哪天觉得那个调用"没人用、可以删"，先看这段：
 * 一次页面加载**必须**能把就绪位清掉。
 *
 * 放在 core 且**不碰任何 Android 类型**，是为了能在纯 JVM 上测：[ShellRuntime] 是
 * `object`，其类初始化器就要从 `Looper.getMainLooper()` 造 `Handler`，单测碰不得。
 *
 * 用 class 而不是 object：单测每个用例拿一个干净实例（object 的静态状态会在同一 JVM 的
 * 用例之间串味，逼出顺序依赖）；而"进程内唯一"这件事没变——持有它的 [ShellRuntime]
 * 本身就是单例，全进程恰好一个实例（与 `ShellRuntime.store` 同一模式）。
 */
class InjectionReadiness {

    // 由 WebView 的 JavaBridge 线程写（`ready` 桥消息），主线程读（就绪守卫与设置页）⇒ volatile。
    @Volatile
    private var ready = false

    /**
     * 注入层报到：`ShellRuntime` 收到 `ready` 桥消息时调用。
     * 幂等（连报两次仍是"就绪"）。
     */
    fun markReady() {
        ready = true
    }

    /**
     * 页面开始（重新）加载：注入层从零开始，就绪位必须回到未就绪。
     * 由 `ShellRuntime.onPageStarted()` 调用，而它的调用者是 `MainActivity` 的
     * `onPageStarted` 回调（见类注释——这条调用链**不是**死代码）。
     */
    fun reset() {
        ready = false
    }

    /** 当前页面里的注入层是否已报到。未加载 / 加载中 / 刚加载完但脚本还没报到时为 `false`。 */
    fun isReady(): Boolean = ready
}
