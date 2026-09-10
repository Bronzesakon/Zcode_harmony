# The release build runs without minification (isMinifyEnabled = false), so
# these rules are inert today. Kept so enabling R8 later is a one-line change.

# javascript interfaces are called reflectively from the WebView
-keepclassmembers class com.zcode.remote.** {
    @android.webkit.JavascriptInterface <methods>;
}
