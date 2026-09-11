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
 * | `boot`         | `#f8f8f8` | `#161616` | boot shell, KICKED / takeover pages, and every native screen |
 * | `main-surface` | `#ececee` | `#2b2b2b` | wide layout: the shell area is directly under the status bar |
 * | `main-header`  | `#ffffff` | `#202020` | narrow layout (the phone shell): the page's own title bar is |
 *
 * Light/dark is not taken from the system: the page has its own theme setting,
 * so the reported theme wins and the system's night mode is only the fallback.
 * See inject.js section 6 for how the state is derived.
 */
enum class PageBarState(val token: String) {
    /** Boot shell, status pages, and every screen that is ours rather than the page's. */
    BOOT("boot"),

    /** Control view, narrow: the page's own header sits directly under the bar. */
    MAIN_HEADER("main-header"),

    /** Control view, wide: the shell area sits directly under the bar. */
    MAIN_SURFACE("main-surface"),

    ;

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
