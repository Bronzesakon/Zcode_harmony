package com.zcode.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入就绪位（`ShellRuntime.injectedReady` 的搬家对象）。
 *
 * 钉住的是刚修掉的那个 bug：就绪位的**清除**只写在 `ShellRuntime.onPageStarted()` 里，
 * 而那个方法当时**零调用者**，于是首次页面加载报到之后这一位永久 latch 在 `true`——
 * `MainActivity` 的两处就绪守卫因此放行、设置页永远显示"已就绪"、诊断指令队列
 * 发给一个根本没装好的页面。
 *
 * 所以这里的第一条不是"能置位"，而是"**一次页面加载真的能把它清掉**"。
 *
 * ⚠️ **覆盖边界（别把这几条当接线保护）**：本类只钉 `InjectionReadiness` 自身的语义——
 * `reset()` 之后必须回到未就绪。它**观察不到**"`MainActivity` 是否真的调用了
 * `ShellRuntime.onPageStarted()`"这条**接线**，而当初的 bug 恰恰是接线缺失（那个方法零调用者）。
 * 也就是说：**把 `MainActivity` 里那行调用删掉，本类仍然全绿**。接线级保护需要一条静态检查
 * （断言该回调里含这次调用），属另议项，不在本文件职责内。
 */
class InjectionReadinessTest {

    @Test
    fun `a fresh holder is not ready`() {
        // 初值必须与原来的 `private var injectedReady = false` 一致：
        // 页面还没报到时，守卫必须拦住。
        assertFalse(InjectionReadiness().isReady())
    }

    @Test
    fun `a page load clears readiness even though the script had reported ready`() {
        // 这就是坏掉的那条行为：先前已就绪，页面重新加载 ⇒ 必须回到未就绪。
        val readiness = InjectionReadiness()
        readiness.markReady()
        assertTrue(readiness.isReady())
        readiness.reset()
        assertFalse(readiness.isReady())
    }

    @Test
    fun `the script can report ready again after a page load`() {
        // reset 不是单向闩锁：新页面报到后必须重新算就绪（否则守卫永远拦住）。
        val readiness = InjectionReadiness()
        readiness.markReady()
        readiness.reset()
        readiness.markReady()
        assertTrue(readiness.isReady())
    }

    @Test
    fun `repeated reports and resets keep the last state`() {
        // 幂等：`ready` 桥消息可能重复到达（重复注入），而 onPageStarted 在首次加载也会
        // 走一次 reset——两个方向重复调用都只能是"最后一次说了算"，不能翻回来。
        val readiness = InjectionReadiness()
        readiness.markReady()
        readiness.markReady()
        assertTrue(readiness.isReady())
        readiness.reset()
        readiness.reset()
        assertFalse(readiness.isReady())
    }
}
