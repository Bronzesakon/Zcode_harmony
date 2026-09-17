package com.zcode.remote.core

/**
 * Decides whether the native carrier may give the relay connection back to the page.
 *
 * The reverse invariant of the whole carrier design is 「页面链路一旦自己活了，原生立刻交还」
 * (README §实现要点 15): only one side may ever hold the connection. Read as
 * `lastInboundAgoMs < STALL_SILENCE_MS` that is correct for the **dead-link** takeover,
 * because that path only starts after 35s of silence — anything arriving later really is
 * the page coming back on its own.
 *
 * It is *wrong* for the **early** takeover, and 真机 2026-09-17 22:49:01 抓到的正是它。
 * Early takeover exists precisely because the page's link is *alive* while nobody feeds the
 * cards. When the native side pairs, the desktop pushes the page a `KICKED` error frame and
 * the page disposes its socket (`socket.close() … X2t.dispose ← at Object.onFailure`) — and
 * **that frame itself** refreshes `lastInboundAt`. One pump tick later the shell read
 * `age = 16s` (< 35s) as 「页面链路已恢复」, handed the connection back to a page that was
 * already terminal, and the same call took it straight back: a needless unpair/re-pair plus
 * a bridge teardown/rebuild. Only the 20s socket-quiet guard kept it from looping — luck of
 * timing, not design.
 *
 * So the early path needs a stronger witness than "some frame arrived": the page must be
 * *serving* again — an OPEN socket, still paired, and conversation frames arriving (the very
 * metric that triggers the early takeover, read in reverse). Until then the native side keeps
 * feeding the cards; returning to the foreground is what hands the connection back, exactly
 * as it always did.
 *
 * Android-free and pure so it can be unit tested on the JVM.
 */
object CarrierHandback {

    /** `WebSocket.readyState` of an open socket, as reported by the injected layer. */
    const val SOCKET_OPEN = 1

    /**
     * Is the page demonstrably back in service?
     *
     * @param socketState `socket.readyState` from the page's own liveness report
     *   (`-1` = no socket, `3` = closed).
     * @param paired the page's relay pairing flag — a KICKED page loses it.
     * @param convFrameAgoMs ms since the page last received a `conversation/` frame;
     *   `-1` = none in this client's lifetime (see `__zcodeShellReportLiveness`).
     * @param convSilenceMs the silence that means "nobody is watching a task"
     *   (`ShellRuntime.EARLY_TAKEOVER_CONV_SILENCE_MS`).
     */
    fun pageIsServing(
        socketState: Int,
        paired: Boolean,
        convFrameAgoMs: Long,
        convSilenceMs: Long,
    ): Boolean = socketState == SOCKET_OPEN && paired && convFrameAgoMs in 0 until convSilenceMs

    /**
     * Should the carrier yield now?
     *
     * @param pageLinkAlive `lastInboundAgeMs() < STALL_SILENCE_MS` — some frame arrived recently.
     * @param startedEarly this carrier session came from the early-takeover branch
     *   (i.e. the page's link was alive when it began).
     * @param pageIsServing see [pageIsServing].
     */
    fun shouldHandBack(
        pageLinkAlive: Boolean,
        startedEarly: Boolean,
        pageIsServing: Boolean,
    ): Boolean = pageLinkAlive && (!startedEarly || pageIsServing)
}
