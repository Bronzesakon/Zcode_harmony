package com.zcode.remote.core

/**
 * The strip behind the status bar, and what it must be painted with.
 *
 * The main screen has no app bar any more: the page starts directly under the
 * status bar, so that strip *is* the top of the remote page's own chrome. It
 * therefore has to carry the page's surface colour, and the value has to be
 * exact — any mismatch reads as a seam between the system bar and the page.
 *
 * Runtime colour reading is deliberately absent (the same rule the HarmonyOS
 * build follows): the injected layer reports two *names* — which visual state the
 * DOM is in, and which theme the page resolved for itself — and this table maps
 * them to the literals. The literals come from the page's own stylesheet
 * (`docs/05-…`, 2026-09-11 snapshot):
 *
 * | token          | light     | dark      | where it is the top surface |
 * | -------------- | --------- | --------- | --------------------------- |
 * | `boot-shell`   | `#fafafa` | `#171717` | pre-rendered shell before the page stamps its theme class |
 * | `boot`         | `#f8f8f8` | `#161616` | the same shell once themed, plus KICKED / takeover / pairing pages |
 * | `main-surface` | `#ececee` | `#2b2b2b` | wide layout: the shell area is directly under the status bar |
 * | `main-header`  | `#ffffff` | `#202020` | narrow layout (the phone shell): the page's own title bar is |
 *
 * Light/dark is not taken from the system: the page has its own theme setting,
 * so the reported theme wins and the system's night mode is only the fallback.
 * See inject.js section 6 for how the state is derived.
 */
enum class PageBarState(val token: String) {
    /**
     * Pre-rendered boot shell while `<html>` carries no `theme-zai-*` class yet,
     * i.e. before the page's own top-level IIFE stamps it: the page background
     * still falls back to the `:root` neutral-50/900 token.
     */
    BOOT_SHELL("boot-shell"),

    /**
     * Themed boot shell (the page's IIFE stamps the theme class before React
     * mounts), plus status pages and every screen that is ours rather than the
     * page's — all of them show the themed page background.
     */
    BOOT("boot"),

    /** Control view, narrow: the page's own header sits directly under the bar. */
    MAIN_HEADER("main-header"),

    /** Control view, wide: the shell area sits directly under the bar. */
    MAIN_SURFACE("main-surface");

    companion object {
        val DEFAULT = BOOT
    }
}

/** Light or dark, as resolved by the page itself. */
enum class PageTheme(val token: String) {
    LIGHT("light"),
    DARK("dark"),
}

object PageBarColor {

    /**
     * Token -> state / theme. Both live on the table's owner rather than on the
     * enums so there is exactly one place that knows the wire names — a rename on
     * the inject.js side then has one counterpart to change, not two.
     */
    fun stateOf(token: String?): PageBarState =
        PageBarState.entries.firstOrNull { it.token == token } ?: PageBarState.DEFAULT

    /** Unknown or missing means "the page has not said"; the caller falls back. */
    fun themeOf(token: String?): PageTheme? =
        PageTheme.entries.firstOrNull { it.token == token }


    // 网页 boot 阶段有两段底色，切点是 <html> 上的 `theme-zai-*` 类（由主包**顶层
    // IIFE** 挂上，早于 React 挂载），**不是**预渲染壳是否存在（两段壳都在）：
    //   boot-shell：壳在、主题类未挂 → `--color-background` 落回 `:root` 的
    //               neutral-50/900 = #FAFAFA/#171717（2026-09-28 真机像素实测
    //               250/255，缝恰在状态栏高度）；
    //   boot：      壳在、主题类已挂（IIFE → 首次 render，中间夹着 relay 握手 await），
    //               与 KICKED/接管、配对等待等 React 状态页同色 = #F8F8F8/#161616。
    // 旧实现两段共用一个 boot 态，只能二选一（取 #FAFAFA 则状态页有色缝，取 #F8F8F8
    // 则开屏第一段有色缝）；2026-10-08 拆分后三段全覆盖，配对 waiting 这类可长时间
    // 停留的页面不再被当成「罕见错误页」牺牲掉。
    private const val BOOT_SHELL_LIGHT = 0xFFFAFAFA.toInt()
    private const val BOOT_SHELL_DARK = 0xFF171717.toInt()
    private const val BOOT_LIGHT = 0xFFF8F8F8.toInt()
    private const val BOOT_DARK = 0xFF161616.toInt()
    private const val MAIN_HEADER_LIGHT = 0xFFFFFFFF.toInt()
    private const val MAIN_HEADER_DARK = 0xFF202020.toInt()
    private const val MAIN_SURFACE_LIGHT = 0xFFECECEE.toInt()
    private const val MAIN_SURFACE_DARK = 0xFF2B2B2B.toInt()

    /**
     * @param state which page state the injected layer last reported.
     * @param theme the theme the page resolved for itself; null means "the page
     *   has not said", in which case the caller passes the system's night mode.
     */
    fun resolve(state: PageBarState, dark: Boolean): Int = when (state) {
        PageBarState.BOOT_SHELL -> if (dark) BOOT_SHELL_DARK else BOOT_SHELL_LIGHT
        PageBarState.BOOT -> if (dark) BOOT_DARK else BOOT_LIGHT
        PageBarState.MAIN_HEADER -> if (dark) MAIN_HEADER_DARK else MAIN_HEADER_LIGHT
        PageBarState.MAIN_SURFACE -> if (dark) MAIN_SURFACE_DARK else MAIN_SURFACE_LIGHT
    }

    /**
     * One line for the log, so a field round can tell what was applied.
     *
     * [appliedArgb] is passed in rather than recomputed: the caller may have had
     * to fall back to the system's night mode when the page has not reported a
     * theme, and the log has to state the colour that is actually on screen.
     */
    fun describe(state: PageBarState, theme: PageTheme?, appliedArgb: Int): String {
        val name = theme?.token ?: "system"
        return "状态栏底色: ${state.token}/$name → #${"%06X".format(appliedArgb and 0xFFFFFF)}"
    }

    /**
     * What `isAppearanceLightStatusBars` has to be set to, i.e. "the surface
     * behind the bar is light, so draw dark icons". The opposite of the theme,
     * which is why it lives next to the table instead of in the Activity.
     */
    fun appearanceLightStatusBars(theme: PageTheme?): Boolean = theme != PageTheme.DARK
}
