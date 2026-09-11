package com.zcode.remote.core

import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Window-inset handling for a targetSdk 35 app.
 *
 * Android 15 (API 35) ENFORCES edge-to-edge: the window draws behind the status
 * and navigation bars and `android:statusBarColor` is ignored. Without explicit
 * inset handling the app's own chrome ends up underneath the system bars — the
 * toolbar's overflow button gets clipped by the status bar, and bottom content
 * sits under the navigation bar.
 *
 * [enableThemeEdgeToEdge] is called first so the behaviour is identical across
 * API 26..35. Skipping it would leave pre-15 devices in the old non-edge-to-edge
 * mode where the decor has already inset the content, and
 * [padForSystemBarsAndIme] would then pad a second time.
 */
fun ComponentActivity.enableThemeEdgeToEdge() {
    // The system bar icons must contrast with whatever the window paints behind
    // them — that is the theme's surface, which inverts with night mode. Forcing
    // "light" styling unconditionally (as an earlier revision did) leaves the
    // status bar icons invisible in dark mode.
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val style = if (night) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/**
 * Pads [this] root view by the system bars, any display cutout and the on-screen
 * keyboard, so the app's content stays inside the safe area while the window
 * background still paints behind the bars. The root of each layout carries the
 * themed background, which is what makes that strip look intentional rather than
 * like a gap.
 *
 * The keyboard inset is the part that cannot be skipped. Edge-to-edge means
 * `decorFitsSystemWindows=false`, and from that point on `adjustResize` no longer
 * resizes the window — the framework hands the keyboard over as an inset instead.
 * An earlier revision consumed only the system bars, so nothing shrank when the
 * keyboard appeared and the page's composer stayed underneath it: the viewport
 * the web page measures never changed.
 *
 * Padding (rather than a height/weight change) is deliberate: reducing the
 * available height shrinks the WebView's frame, which is exactly what makes the
 * page re-measure — `innerHeight` drops by the keyboard height, so a composer
 * pinned to the bottom of the viewport rises with it.
 *
 * Returning CONSUMED is also deliberate: the WebView draws no insets of its own,
 * and letting the raw insets through would tell Chromium that the top of *its*
 * frame is covered by the status bar (it is not — the toolbar already sits above
 * it), which shifts the page's visual viewport. The shell stays the only party
 * that translates insets into layout.
 */
fun View.padForSystemBarsAndIme() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        // Consumed: the root has handled them, children must not pad again.
        WindowInsetsCompat.CONSUMED
    }
}
