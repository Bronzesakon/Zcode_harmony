package com.zcode.remote.core

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
 * [enableLightEdgeToEdge] wraps androidx's `enableEdgeToEdge`, which is an
 * extension on ComponentActivity (not Activity) — hence the receiver below. It is
 * called first so the behaviour is identical across API 26..35. Skipping it would leave pre-15 devices in the old non-edge-to-edge
 * mode where the decor has already inset the content, and [padForSystemBars]
 * would then pad a second time.
 *
 * The styles are forced to "light" rather than left on auto: this app's palette
 * is a fixed light colour in both day and night mode, so the system bar icons
 * must stay dark regardless of the device's theme.
 */
fun ComponentActivity.enableLightEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
}

/**
 * Pads [this] root view by the system bars and any display cutout, so the app's
 * content stays inside the safe area while the window background still paints
 * behind the bars. The root of each layout carries the app background colour,
 * which is what makes that strip look intentional rather than like a gap.
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
