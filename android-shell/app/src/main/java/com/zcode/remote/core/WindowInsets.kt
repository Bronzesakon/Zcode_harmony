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
 * mode where the decor has already inset the content, and [padForSystemBars]
 * would then pad a second time.
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
 * Pads [this] root view by the system bars and any display cutout, so the app's
 * content stays inside the safe area while the window background still paints
 * behind the bars. The root of each layout carries the themed background, which
 * is what makes that strip look intentional rather than like a gap.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        // Consumed: the root has handled them, children must not pad again.
        WindowInsetsCompat.CONSUMED
    }
}
