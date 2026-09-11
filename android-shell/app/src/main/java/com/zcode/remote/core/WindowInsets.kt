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
 * inset handling the page ends up underneath the system bars — its own title row
 * under the status bar's clock. [padForStatusBarAndIme] fixes the top and, on
 * purpose, leaves the bottom immersed.
 *
 * [enableThemeEdgeToEdge] is called first so the behaviour is identical across
 * API 26..35. Skipping it would leave pre-15 devices in the old non-edge-to-edge
 * mode where the decor has already inset the content, and
 * [padForStatusBarAndIme] would then pad a second time.
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
 * Pads [this] root view so the app's own content stays inside the safe area at
 * the top while the bottom is deliberately left immersed.
 *
 * The two directions are not symmetric, and that is the point:
 *
 *  * **Top** — the status bar's inset becomes the web page's top padding. The
 *    page is the app's chrome now (there is no app bar), and the page must not
 *    start underneath the status bar's clock: what would be covered there is the
 *    page's own title row. The strip above the padding is painted by the root's
 *    background, which is the page's own top surface colour (see
 *    [com.zcode.remote.core.PageBarColor]). Cutouts count too, otherwise a
 *    landscape notch eats the page's first characters.
 *
 *  * **Bottom** — the navigation/gesture bar inset is *not* consumed, so the page
 *    ends flush with the screen edge and the gesture bar floats over it. The page
 *    gets the full height back; the alternative (padding the bottom) would leave
 *    a dead band that the page cannot use and that reads as a seam, because the
 *    page's own background differs from the window's.
 *
 * The keyboard is the one exception at the bottom: [`Type.ime`] padding is still
 * applied, because edge-to-edge means `decorFitsSystemWindows=false` and
 * `adjustResize` no longer resizes the window — from that point on the framework
 * reports the keyboard as an inset instead. Without it nothing shrinks when the
 * keyboard appears and the page's composer stays underneath it. Padding (rather
 * than a height/weight change) is what makes the page re-measure: `innerHeight`
 * drops by the keyboard height, so a composer pinned to the viewport's bottom
 * rises with it.
 *
 * Returning CONSUMED is also deliberate: the WebView draws no insets of its own,
 * and letting the raw insets through would tell Chromium that the top of *its*
 * frame is covered by the status bar (it is not — the root already padded it
 * down), which shifts the page's visual viewport. The shell stays the only party
 * that translates insets into layout.
 */
fun View.padForStatusBarAndIme() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, ime.bottom)
        // Consumed: the root has handled them, children must not pad again.
        WindowInsetsCompat.CONSUMED
    }
}
