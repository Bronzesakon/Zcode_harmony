# WebView 文件上传（WebChromeClient.onShowFileChooser）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/reference/android/webkit/WebChromeClient?hl=zh-cn
> 页面标题：WebView 文件上传（WebChromeClient.onShowFileChooser）
> 快照时间：2026-09-10

---
Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

Summary: [Nested Classes](#nestedclasses) | [Ctors](#pubctors) | [Methods](#pubmethods) | [Inherited Methods](#inhmethods)

# WebChromeClient

* * *

[Kotlin](/reference/kotlin/android/webkit/WebChromeClient "View this page in Kotlin") |Java

`public class WebChromeClient`  
`extends [Object](/reference/java/lang/Object)`

<table class="jd-inheritance-table"><tbody><tr><td colspan="2" class="jd-inheritance-class-cell"><a href="/reference/java/lang/Object">java.lang.Object</a></td></tr><tr><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="1" class="jd-inheritance-class-cell">android.webkit.WebChromeClient</td></tr></tbody></table>

  

* * *

## Summary

| 
### Nested classes

 |
| --- |
| `interface` | `[WebChromeClient.CustomViewCallback](/reference/android/webkit/WebChromeClient.CustomViewCallback)`

A callback interface used by the host application to notify the current page that its custom view has been dismissed. 

 |
| `class` | `[WebChromeClient.FileChooserParams](/reference/android/webkit/WebChromeClient.FileChooserParams)`

Parameters used in the `[WebChromeClient.onShowFileChooser(WebView, ValueCallback, FileChooserParams)](/reference/android/webkit/WebChromeClient#onShowFileChooser\(android.webkit.WebView,%20android.webkit.ValueCallback\<android.net.Uri[]\>,%20android.webkit.WebChromeClient.FileChooserParams\))` method. 

 |

| 
### Public constructors

 |
| --- |
| `[WebChromeClient](/reference/android/webkit/WebChromeClient#WebChromeClient\(\))()` |

| 
### Public methods

 |
| --- |
| `[Bitmap](/reference/android/graphics/Bitmap)` | `[getDefaultVideoPoster](/reference/android/webkit/WebChromeClient#getDefaultVideoPoster\(\))()`

When not playing, video elements are represented by a 'poster' image.

 |
| `[View](/reference/android/view/View)` | `[getVideoLoadingProgressView](/reference/android/webkit/WebChromeClient#getVideoLoadingProgressView\(\))()`

Obtains a View to be displayed while buffering of full screen video is taking place.

 |
| `void` | `[getVisitedHistory](/reference/android/webkit/WebChromeClient#getVisitedHistory\(android.webkit.ValueCallback\<java.lang.String[]\>\))([ValueCallback](/reference/android/webkit/ValueCallback)<[String[]](/reference/java/lang/String)> callback)`

Obtains a list of all visited history items, used for link coloring

 |
| `void` | `[onCloseWindow](/reference/android/webkit/WebChromeClient#onCloseWindow\(android.webkit.WebView\))([WebView](/reference/android/webkit/WebView) window)`

Notify the host application to close the given WebView and remove it from the view system if necessary.

 |
| `boolean` | `[onConsoleMessage](/reference/android/webkit/WebChromeClient#onConsoleMessage\(android.webkit.ConsoleMessage\))([ConsoleMessage](/reference/android/webkit/ConsoleMessage) consoleMessage)`

Report a JavaScript console message to the host application.

 |
| `void` | `[onConsoleMessage](/reference/android/webkit/WebChromeClient#onConsoleMessage\(java.lang.String,%20int,%20java.lang.String\))([String](/reference/java/lang/String) message, int lineNumber, [String](/reference/java/lang/String) sourceID)`

_This method was deprecated in API level 15. Use `[onConsoleMessage(ConsoleMessage)](/reference/android/webkit/WebChromeClient#onConsoleMessage\(android.webkit.ConsoleMessage\))` instead._

 |
| `boolean` | `[onCreateWindow](/reference/android/webkit/WebChromeClient#onCreateWindow\(android.webkit.WebView,%20boolean,%20boolean,%20android.os.Message\))([WebView](/reference/android/webkit/WebView) view, boolean isDialog, boolean isUserGesture, [Message](/reference/android/os/Message) resultMsg)`

Request the host application to create a new window.

 |
| `void` | `[onExceededDatabaseQuota](/reference/android/webkit/WebChromeClient#onExceededDatabaseQuota\(java.lang.String,%20java.lang.String,%20long,%20long,%20long,%20android.webkit.WebStorage.QuotaUpdater\))([String](/reference/java/lang/String) url, [String](/reference/java/lang/String) databaseIdentifier, long quota, long estimatedDatabaseSize, long totalQuota, [WebStorage.QuotaUpdater](/reference/android/webkit/WebStorage.QuotaUpdater) quotaUpdater)`

_This method was deprecated in API level 19. This method is no longer called; WebView now uses the HTML5 / JavaScript Quota Management API._

 |
| `void` | `[onGeolocationPermissionsHidePrompt](/reference/android/webkit/WebChromeClient#onGeolocationPermissionsHidePrompt\(\))()`

Notify the host application that a request for Geolocation permissions, made with a previous call to `[onGeolocationPermissionsShowPrompt()](/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt\(java.lang.String,%20android.webkit.GeolocationPermissions.Callback\))` has been canceled.

 |
| `void` | `[onGeolocationPermissionsShowPrompt](/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt\(java.lang.String,%20android.webkit.GeolocationPermissions.Callback\))([String](/reference/java/lang/String) origin, [GeolocationPermissions.Callback](/reference/android/webkit/GeolocationPermissions.Callback) callback)`

Notify the host application that web content from the specified origin is attempting to use the Geolocation API, but no permission state is currently set for that origin.

 |
| `void` | `[onHideCustomView](/reference/android/webkit/WebChromeClient#onHideCustomView\(\))()`

Notify the host application that the current page has exited full screen mode.

 |
| `boolean` | `[onJsAlert](/reference/android/webkit/WebChromeClient#onJsAlert\(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20android.webkit.JsResult\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) url, [String](/reference/java/lang/String) message, [JsResult](/reference/android/webkit/JsResult) result)`

Notify the host application that the web page wants to display a JavaScript `alert()` dialog.

 |
| `boolean` | `[onJsBeforeUnload](/reference/android/webkit/WebChromeClient#onJsBeforeUnload\(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20android.webkit.JsResult\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) url, [String](/reference/java/lang/String) message, [JsResult](/reference/android/webkit/JsResult) result)`

Notify the host application that the web page wants to confirm navigation from JavaScript `onbeforeunload`.

 |
| `boolean` | `[onJsConfirm](/reference/android/webkit/WebChromeClient#onJsConfirm\(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20android.webkit.JsResult\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) url, [String](/reference/java/lang/String) message, [JsResult](/reference/android/webkit/JsResult) result)`

Notify the host application that the web page wants to display a JavaScript `confirm()` dialog.

 |
| `boolean` | `[onJsPrompt](/reference/android/webkit/WebChromeClient#onJsPrompt\(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20java.lang.String,%20android.webkit.JsPromptResult\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) url, [String](/reference/java/lang/String) message, [String](/reference/java/lang/String) defaultValue, [JsPromptResult](/reference/android/webkit/JsPromptResult) result)`

Notify the host application that the web page wants to display a JavaScript `prompt()` dialog.

 |
| `boolean` | `[onJsTimeout](/reference/android/webkit/WebChromeClient#onJsTimeout\(\))()`

_This method was deprecated in API level 17. This method is no longer supported and will not be invoked._

 |
| `void` | `[onPermissionRequest](/reference/android/webkit/WebChromeClient#onPermissionRequest\(android.webkit.PermissionRequest\))([PermissionRequest](/reference/android/webkit/PermissionRequest) request)`

Notify the host application that web content is requesting permission to access the specified resources and the permission currently isn't granted or denied.

 |
| `void` | `[onPermissionRequestCanceled](/reference/android/webkit/WebChromeClient#onPermissionRequestCanceled\(android.webkit.PermissionRequest\))([PermissionRequest](/reference/android/webkit/PermissionRequest) request)`

Notify the host application that the given permission request has been canceled.

 |
| `void` | `[onProgressChanged](/reference/android/webkit/WebChromeClient#onProgressChanged\(android.webkit.WebView,%20int\))([WebView](/reference/android/webkit/WebView) view, int newProgress)`

Tell the host application the current progress of loading a page.

 |
| `void` | `[onReceivedIcon](/reference/android/webkit/WebChromeClient#onReceivedIcon\(android.webkit.WebView,%20android.graphics.Bitmap\))([WebView](/reference/android/webkit/WebView) view, [Bitmap](/reference/android/graphics/Bitmap) icon)`

Notify the host application of a new favicon for the current page.

 |
| `void` | `[onReceivedTitle](/reference/android/webkit/WebChromeClient#onReceivedTitle\(android.webkit.WebView,%20java.lang.String\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) title)`

Notify the host application of a change in the document title.

 |
| `void` | `[onReceivedTouchIconUrl](/reference/android/webkit/WebChromeClient#onReceivedTouchIconUrl\(android.webkit.WebView,%20java.lang.String,%20boolean\))([WebView](/reference/android/webkit/WebView) view, [String](/reference/java/lang/String) url, boolean precomposed)`

Notify the host application of the url for an apple-touch-icon.

 |
| `void` | `[onRequestFocus](/reference/android/webkit/WebChromeClient#onRequestFocus\(android.webkit.WebView\))([WebView](/reference/android/webkit/WebView) view)`

Request display and focus for this WebView.

 |
| `void` | `[onShowCustomView](/reference/android/webkit/WebChromeClient#onShowCustomView\(android.view.View,%20int,%20android.webkit.WebChromeClient.CustomViewCallback\))([View](/reference/android/view/View) view, int requestedOrientation, [WebChromeClient.CustomViewCallback](/reference/android/webkit/WebChromeClient.CustomViewCallback) callback)`

_This method was deprecated in API level 18. This method supports the obsolete plugin mechanism, and will not be invoked in future_

 |
| `void` | `[onShowCustomView](/reference/android/webkit/WebChromeClient#onShowCustomView\(android.view.View,%20android.webkit.WebChromeClient.CustomViewCallback\))([View](/reference/android/view/View) view, [WebChromeClient.CustomViewCallback](/reference/android/webkit/WebChromeClient.CustomViewCallback) callback)`

Notify the host application that the current page has entered full screen mode.

 |
| `boolean` | `[onShowFileChooser](/reference/android/webkit/WebChromeClient#onShowFileChooser\(android.webkit.WebView,%20android.webkit.ValueCallback\<android.net.Uri[]\>,%20android.webkit.WebChromeClient.FileChooserParams\))([WebView](/reference/android/webkit/WebView) webView, [ValueCallback](/reference/android/webkit/ValueCallback)<[Uri[]](/reference/android/net/Uri)> filePathCallback, [WebChromeClient.FileChooserParams](/reference/android/webkit/WebChromeClient.FileChooserParams) fileChooserParams)`

Asks the client app to show a file chooser.

 |

| 
### Inherited methods

 |
| --- |
| 

From class `[java.lang.Object](/reference/java/lang/Object)`

<table class="responsive"><tbody><tr data-version-added="1"><td><code translate="no" dir="ltr"><a href="/reference/java/lang/Object">Object</a></code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#clone()">clone</a>()</code><p>Creates and returns a copy of this object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">boolean</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#equals(java.lang.Object)">equals</a>(<a href="/reference/java/lang/Object">Object</a> obj)</code><p>Indicates whether some other object is "equal to" this one.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#finalize()">finalize</a>()</code><p>Called by the garbage collector on an object when garbage collection determines that there are no more references to the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final <a href="/reference/java/lang/Class">Class</a>&lt;?&gt;</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#getClass()">getClass</a>()</code><p>Returns the runtime class of this <code translate="no" dir="ltr">Object</code>.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">int</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#hashCode()">hashCode</a>()</code><p>Returns a hash code value for the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#notify()">notify</a>()</code><p>Wakes up a single thread that is waiting on this object's monitor.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#notifyAll()">notifyAll</a>()</code><p>Wakes up all threads that are waiting on this object's monitor.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr"><a href="/reference/java/lang/String">String</a></code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#toString()">toString</a>()</code><p>Returns a string representation of the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait(long,%20int)">wait</a>(long timeoutMillis, int nanos)</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>, or until a certain amount of real time has elapsed.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait(long)">wait</a>(long timeoutMillis)</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>, or until a certain amount of real time has elapsed.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait()">wait</a>()</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>.</p></td></tr></tbody></table>


 |

## Public constructors

### WebChromeClient

public WebChromeClient ()

## Public methods

### getDefaultVideoPoster

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public [Bitmap](/reference/android/graphics/Bitmap) getDefaultVideoPoster ()

When not playing, video elements are represented by a 'poster' image. The image to use can be specified by the poster attribute of the video tag in HTML. If the attribute is absent, then a default poster will be used. This method allows the ChromeClient to provide that default image.

| Returns |
| --- |
| `[Bitmap](/reference/android/graphics/Bitmap)` | Bitmap The image to use as a default poster, or `null` if no such image is available.
 |

### getVideoLoadingProgressView

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public [View](/reference/android/view/View) getVideoLoadingProgressView ()

Obtains a View to be displayed while buffering of full screen video is taking place. The host application can override this method to provide a View containing a spinner or similar.

| Returns |
| --- |
| `[View](/reference/android/view/View)` | View The View to be displayed whilst the video is loading.  
This value may be `null`.
 |

### getVisitedHistory

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void getVisitedHistory ([ValueCallback](/reference/android/webkit/ValueCallback)<[String\[\]](/reference/java/lang/String)\> callback)

Obtains a list of all visited history items, used for link coloring

| Parameters |
| --- |
| `callback` | `ValueCallback`
 |

### onCloseWindow

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onCloseWindow ([WebView](/reference/android/webkit/WebView) window)

Notify the host application to close the given WebView and remove it from the view system if necessary. At this point, WebCore has stopped any loading in this window and has removed any cross-scripting ability in javascript.

As with `[onCreateWindow(WebView, boolean, boolean, Message)](/reference/android/webkit/WebChromeClient#onCreateWindow\(android.webkit.WebView,%20boolean,%20boolean,%20android.os.Message\))`, the application should ensure that any URL or security indicator displayed is updated so that the user can tell that the page they were interacting with has been closed.

| Parameters |
| --- |
| `window` | `WebView`: The WebView that needs to be closed.
 |

### onConsoleMessage

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onConsoleMessage ([ConsoleMessage](/reference/android/webkit/ConsoleMessage) consoleMessage)

Report a JavaScript console message to the host application. The ChromeClient should override this to process the log message as they see fit.

| Parameters |
| --- |
| `consoleMessage` | `ConsoleMessage`: Object containing details of the console message.
 |

| Returns |
| --- |
| `boolean` | `true` if the message is handled by the client.
 |

### onConsoleMessage

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 15](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onConsoleMessage ([String](/reference/java/lang/String) message, 
                int lineNumber, 
                [String](/reference/java/lang/String) sourceID)

**This method was deprecated in API level 15.**  
Use `[onConsoleMessage(ConsoleMessage)](/reference/android/webkit/WebChromeClient#onConsoleMessage\(android.webkit.ConsoleMessage\))` instead.

Report a JavaScript error message to the host application. The ChromeClient should override this to process the log message as they see fit.

| Parameters |
| --- |
| `message` | `String`: The error message to report.
 |
| `lineNumber` | `int`: The line number of the error.

 |
| `sourceID` | `String`: The name of the source file that caused the error.

 |

### onCreateWindow

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onCreateWindow ([WebView](/reference/android/webkit/WebView) view, 
                boolean isDialog, 
                boolean isUserGesture, 
                [Message](/reference/android/os/Message) resultMsg)

Request the host application to create a new window. If the host application chooses to honor this request, it should return `true` from this method, create a new WebView to host the window, insert it into the View system and send the supplied resultMsg message to its target with the new WebView as an argument. If the host application chooses not to honor the request, it should return `false` from this method. The default implementation of this method does nothing and hence returns `false`.

Applications should typically not allow windows to be created when the `isUserGesture` flag is false, as this may be an unwanted popup.

Applications should be careful how they display the new window: don't simply overlay it over the existing WebView as this may mislead the user about which site they are viewing. If your application displays the URL of the main page, make sure to also display the URL of the new window in a similar fashion. If your application does not display URLs, consider disallowing the creation of new windows entirely.

**Note:** There is no trustworthy way to tell which page requested the new window: the request might originate from a third-party iframe inside the WebView.

| Parameters |
| --- |
| `view` | `WebView`: The WebView from which the request for a new window originated.
 |
| `isDialog` | `boolean`: `true` if the new window should be a dialog, rather than a full-size window.

 |
| `isUserGesture` | `boolean`: `true` if the request was initiated by a user gesture, such as the user clicking a link.

 |
| `resultMsg` | `Message`: The message to send when once a new WebView has been created. resultMsg.obj is a `[WebView.WebViewTransport](/reference/android/webkit/WebView.WebViewTransport)` object. This should be used to transport the new WebView, by calling `[WebView.WebViewTransport.setWebView(WebView)](/reference/android/webkit/WebView.WebViewTransport#setWebView\(android.webkit.WebView\))`.

 |

| Returns |
| --- |
| `boolean` | This method should return `true` if the host application will create a new window, in which case resultMsg should be sent to its target. Otherwise, this method should return `false`. Returning `false` from this method but also sending resultMsg will result in undefined behavior.
 |

### onExceededDatabaseQuota

Added in [API level 5](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 19](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onExceededDatabaseQuota ([String](/reference/java/lang/String) url, 
                [String](/reference/java/lang/String) databaseIdentifier, 
                long quota, 
                long estimatedDatabaseSize, 
                long totalQuota, 
                [WebStorage.QuotaUpdater](/reference/android/webkit/WebStorage.QuotaUpdater) quotaUpdater)

**This method was deprecated in API level 19.**  
This method is no longer called; WebView now uses the HTML5 / JavaScript Quota Management API.

Tell the client that the quota has been exceeded for the Web SQL Database API for a particular origin and request a new quota. The client must respond by invoking the `[updateQuota(long)](/reference/android/webkit/WebStorage.QuotaUpdater#updateQuota\(long\))` method of the supplied `[WebStorage.QuotaUpdater](/reference/android/webkit/WebStorage.QuotaUpdater)` instance. The minimum value that can be set for the new quota is the current quota. The default implementation responds with the current quota, so the quota will not be increased.

| Parameters |
| --- |
| `url` | `String`: The URL of the page that triggered the notification
 |
| `databaseIdentifier` | `String`: The identifier of the database where the quota was exceeded.

 |
| `quota` | `long`: The quota for the origin, in bytes

 |
| `estimatedDatabaseSize` | `long`: The estimated size of the offending database, in bytes

 |
| `totalQuota` | `long`: The total quota for all origins, in bytes

 |
| `quotaUpdater` | `WebStorage.QuotaUpdater`: An instance of `[WebStorage.QuotaUpdater](/reference/android/webkit/WebStorage.QuotaUpdater)` which must be used to inform the WebView of the new quota.

 |

### onGeolocationPermissionsHidePrompt

Added in [API level 5](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onGeolocationPermissionsHidePrompt ()

Notify the host application that a request for Geolocation permissions, made with a previous call to `[onGeolocationPermissionsShowPrompt()](/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt\(java.lang.String,%20android.webkit.GeolocationPermissions.Callback\))` has been canceled. Any related UI should therefore be hidden.

### onGeolocationPermissionsShowPrompt

Added in [API level 5](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onGeolocationPermissionsShowPrompt ([String](/reference/java/lang/String) origin, 
                [GeolocationPermissions.Callback](/reference/android/webkit/GeolocationPermissions.Callback) callback)

Notify the host application that web content from the specified origin is attempting to use the Geolocation API, but no permission state is currently set for that origin. The host application should invoke the specified callback with the desired permission state. See `[GeolocationPermissions](/reference/android/webkit/GeolocationPermissions)` for details.

Note that for applications targeting Android N and later SDKs (API level > `[Build.VERSION_CODES.M](/reference/android/os/Build.VERSION_CODES#M)`) this method is only called for requests originating from secure origins such as https. On non-secure origins geolocation requests are automatically denied.

| Parameters |
| --- |
| `origin` | `String`: The origin of the web content attempting to use the Geolocation API.
 |
| `callback` | `GeolocationPermissions.Callback`: The callback to use to set the permission state for the origin.

 |

### onHideCustomView

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onHideCustomView ()

Notify the host application that the current page has exited full screen mode. The host application must hide the custom View (the View which was previously passed to `[onShowCustomView()](/reference/android/webkit/WebChromeClient#onShowCustomView\(android.view.View,%20android.webkit.WebChromeClient.CustomViewCallback\))`). After this call, web content will render in the original WebView again.

**Note:** if overriding this method, the application must also override `[onShowCustomView()](/reference/android/webkit/WebChromeClient#onShowCustomView\(android.view.View,%20android.webkit.WebChromeClient.CustomViewCallback\))`.

### onJsAlert

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onJsAlert ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) url, 
                [String](/reference/java/lang/String) message, 
                [JsResult](/reference/android/webkit/JsResult) result)

Notify the host application that the web page wants to display a JavaScript `alert()` dialog.

The default behavior if this method returns `false` or is not overridden is to show a dialog containing the alert message and suspend JavaScript execution until the dialog is dismissed.

To show a custom dialog, the app should return `true` from this method, in which case the default dialog will not be shown and JavaScript execution will be suspended. The app should call `JsResult.confirm()` when the custom dialog is dismissed such that JavaScript execution can be resumed.

To suppress the dialog and allow JavaScript execution to continue, call `JsResult.confirm()` immediately and then return `true`.

Note that if the `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is set to be `null`, or if `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is not set at all, the default dialog will be suppressed and Javascript execution will continue immediately.

Note that the default dialog does not inherit the `[Display.FLAG_SECURE](/reference/android/view/Display#FLAG_SECURE)` flag from the parent window.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `url` | `String`: The url of the page requesting the dialog.

 |
| `message` | `String`: Message to be displayed in the window.

 |
| `result` | `JsResult`: A JsResult to confirm that the user closed the window.

 |

| Returns |
| --- |
| `boolean` | boolean `true` if the request is handled or ignored. `false` if WebView needs to show the default dialog.
 |

### onJsBeforeUnload

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onJsBeforeUnload ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) url, 
                [String](/reference/java/lang/String) message, 
                [JsResult](/reference/android/webkit/JsResult) result)

Notify the host application that the web page wants to confirm navigation from JavaScript `onbeforeunload`.

The default behavior if this method returns `false` or is not overridden is to show a dialog containing the message and suspend JavaScript execution until the dialog is dismissed. The default dialog will continue the navigation if the user confirms the navigation, and will stop the navigation if the user wants to stay on the current page.

To show a custom dialog, the app should return `true` from this method, in which case the default dialog will not be shown and JavaScript execution will be suspended. When the custom dialog is dismissed, the app should call `JsResult.confirm()` to continue the navigation or, `JsResult.cancel()` to stay on the current page.

To suppress the dialog and allow JavaScript execution to continue, call `JsResult.confirm()` or `JsResult.cancel()` immediately and then return `true`.

Note that if the `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is set to be `null`, or if `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is not set at all, the default dialog will be suppressed and the navigation will be resumed immediately.

Note that the default dialog does not inherit the `[Display.FLAG_SECURE](/reference/android/view/Display#FLAG_SECURE)` flag from the parent window.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `url` | `String`: The url of the page requesting the dialog.

 |
| `message` | `String`: Message to be displayed in the window.

 |
| `result` | `JsResult`: A JsResult used to send the user's response to javascript.

 |

| Returns |
| --- |
| `boolean` | boolean `true` if the request is handled or ignored. `false` if WebView needs to show the default dialog.
 |

### onJsConfirm

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onJsConfirm ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) url, 
                [String](/reference/java/lang/String) message, 
                [JsResult](/reference/android/webkit/JsResult) result)

Notify the host application that the web page wants to display a JavaScript `confirm()` dialog.

The default behavior if this method returns `false` or is not overridden is to show a dialog containing the message and suspend JavaScript execution until the dialog is dismissed. The default dialog will return `true` to the JavaScript `confirm()` code when the user presses the 'confirm' button, and will return `false` to the JavaScript code when the user presses the 'cancel' button or dismisses the dialog.

To show a custom dialog, the app should return `true` from this method, in which case the default dialog will not be shown and JavaScript execution will be suspended. The app should call `JsResult.confirm()` or `JsResult.cancel()` when the custom dialog is dismissed.

To suppress the dialog and allow JavaScript execution to continue, call `JsResult.confirm()` or `JsResult.cancel()` immediately and then return `true`.

Note that if the `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is set to be `null`, or if `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is not set at all, the default dialog will be suppressed and the default value of `false` will be returned to the JavaScript code immediately.

Note that the default dialog does not inherit the `[Display.FLAG_SECURE](/reference/android/view/Display#FLAG_SECURE)` flag from the parent window.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `url` | `String`: The url of the page requesting the dialog.

 |
| `message` | `String`: Message to be displayed in the window.

 |
| `result` | `JsResult`: A JsResult used to send the user's response to javascript.

 |

| Returns |
| --- |
| `boolean` | boolean `true` if the request is handled or ignored. `false` if WebView needs to show the default dialog.
 |

### onJsPrompt

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onJsPrompt ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) url, 
                [String](/reference/java/lang/String) message, 
                [String](/reference/java/lang/String) defaultValue, 
                [JsPromptResult](/reference/android/webkit/JsPromptResult) result)

Notify the host application that the web page wants to display a JavaScript `prompt()` dialog.

The default behavior if this method returns `false` or is not overridden is to show a dialog containing the message and suspend JavaScript execution until the dialog is dismissed. Once the dialog is dismissed, JavaScript `prompt()` will return the string that the user typed in, or null if the user presses the 'cancel' button.

To show a custom dialog, the app should return `true` from this method, in which case the default dialog will not be shown and JavaScript execution will be suspended. The app should call `JsPromptResult.confirm(result)` when the custom dialog is dismissed.

To suppress the dialog and allow JavaScript execution to continue, call `JsPromptResult.confirm(result)` immediately and then return `true`.

Note that if the `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is set to be `null`, or if `[WebChromeClient](/reference/android/webkit/WebChromeClient)` is not set at all, the default dialog will be suppressed and `null` will be returned to the JavaScript code immediately.

Note that the default dialog does not inherit the `[Display.FLAG_SECURE](/reference/android/view/Display#FLAG_SECURE)` flag from the parent window.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `url` | `String`: The url of the page requesting the dialog.

 |
| `message` | `String`: Message to be displayed in the window.

 |
| `defaultValue` | `String`: The default value displayed in the prompt dialog.

 |
| `result` | `JsPromptResult`: A JsPromptResult used to send the user's reponse to javascript.

 |

| Returns |
| --- |
| `boolean` | boolean `true` if the request is handled or ignored. `false` if WebView needs to show the default dialog.
 |

### onJsTimeout

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 17](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onJsTimeout ()

**This method was deprecated in API level 17.**  
This method is no longer supported and will not be invoked.

Tell the client that a JavaScript execution timeout has occured. And the client may decide whether or not to interrupt the execution. If the client returns `true`, the JavaScript will be interrupted. If the client returns `false`, the execution will continue. Note that in the case of continuing execution, the timeout counter will be reset, and the callback will continue to occur if the script does not finish at the next check point.

| Returns |
| --- |
| `boolean` | boolean Whether the JavaScript execution should be interrupted.
 |

### onPermissionRequest

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onPermissionRequest ([PermissionRequest](/reference/android/webkit/PermissionRequest) request)

Notify the host application that web content is requesting permission to access the specified resources and the permission currently isn't granted or denied. The host application must invoke `[PermissionRequest.grant(String[])](/reference/android/webkit/PermissionRequest#grant\(java.lang.String[]\))` or `[PermissionRequest.deny()](/reference/android/webkit/PermissionRequest#deny\(\))`. If this method isn't overridden, the permission is denied.

| Parameters |
| --- |
| `request` | `PermissionRequest`: the PermissionRequest from current web content.
 |

### onPermissionRequestCanceled

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onPermissionRequestCanceled ([PermissionRequest](/reference/android/webkit/PermissionRequest) request)

Notify the host application that the given permission request has been canceled. Any related UI should therefore be hidden.

| Parameters |
| --- |
| `request` | `PermissionRequest`: the PermissionRequest that needs be canceled.
 |

### onProgressChanged

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onProgressChanged ([WebView](/reference/android/webkit/WebView) view, 
                int newProgress)

Tell the host application the current progress of loading a page.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `newProgress` | `int`: Current page loading progress, represented by an integer between 0 and 100.

 |

### onReceivedIcon

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onReceivedIcon ([WebView](/reference/android/webkit/WebView) view, 
                [Bitmap](/reference/android/graphics/Bitmap) icon)

Notify the host application of a new favicon for the current page.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `icon` | `Bitmap`: A Bitmap containing the favicon for the current page.

 |

### onReceivedTitle

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onReceivedTitle ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) title)

Notify the host application of a change in the document title.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `title` | `String`: A String containing the new title of the document.

 |

### onReceivedTouchIconUrl

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onReceivedTouchIconUrl ([WebView](/reference/android/webkit/WebView) view, 
                [String](/reference/java/lang/String) url, 
                boolean precomposed)

Notify the host application of the url for an apple-touch-icon.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that initiated the callback.
 |
| `url` | `String`: The icon url.

 |
| `precomposed` | `boolean`: `true` if the url is for a precomposed touch icon.

 |

### onRequestFocus

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onRequestFocus ([WebView](/reference/android/webkit/WebView) view)

Request display and focus for this WebView. This may happen due to another WebView opening a link in this WebView and requesting that this WebView be displayed.

| Parameters |
| --- |
| `view` | `WebView`: The WebView that needs to be focused.
 |

### onShowCustomView

Added in [API level 14](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 18](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onShowCustomView ([View](/reference/android/view/View) view, 
                int requestedOrientation, 
                [WebChromeClient.CustomViewCallback](/reference/android/webkit/WebChromeClient.CustomViewCallback) callback)

**This method was deprecated in API level 18.**  
This method supports the obsolete plugin mechanism, and will not be invoked in future

Notify the host application that the current page would like to show a custom View in a particular orientation.

| Parameters |
| --- |
| `view` | `View`: is the View object to be shown.
 |
| `requestedOrientation` | `int`: An orientation constant as used in `[ActivityInfo.screenOrientation](/reference/android/content/pm/ActivityInfo#screenOrientation)`.

 |
| `callback` | `WebChromeClient.CustomViewCallback`: is the callback to be invoked if and when the view is dismissed.

 |

### onShowCustomView

Added in [API level 7](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public void onShowCustomView ([View](/reference/android/view/View) view, 
                [WebChromeClient.CustomViewCallback](/reference/android/webkit/WebChromeClient.CustomViewCallback) callback)

Notify the host application that the current page has entered full screen mode. After this call, web content will no longer be rendered in the WebView, but will instead be rendered in `view`. The host application should add this View to a Window which is configured with `[WindowManager.LayoutParams.FLAG_FULLSCREEN](/reference/android/view/WindowManager.LayoutParams#FLAG_FULLSCREEN)` flag in order to actually display this web content full screen.

The application may explicitly exit fullscreen mode by invoking `callback` (ex. when the user presses the back button). However, this is generally not necessary as the web page will often show its own UI to close out of fullscreen. Regardless of how the WebView exits fullscreen mode, WebView will invoke `[onHideCustomView()](/reference/android/webkit/WebChromeClient#onHideCustomView\(\))`, signaling for the application to remove the custom View.

If this method is not overridden, WebView will report to the web page it does not support fullscreen mode and will not honor the web page's request to run in fullscreen mode.

**Note:** if overriding this method, the application must also override `[onHideCustomView()](/reference/android/webkit/WebChromeClient#onHideCustomView\(\))`.

| Parameters |
| --- |
| `view` | `View`: is the View object to be shown.
 |
| `callback` | `WebChromeClient.CustomViewCallback`: invoke this callback to request the page to exit full screen mode.

 |

### onShowFileChooser

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public boolean onShowFileChooser ([WebView](/reference/android/webkit/WebView) webView, 
                [ValueCallback](/reference/android/webkit/ValueCallback)<[Uri\[\]](/reference/android/net/Uri)\> filePathCallback, 
                [WebChromeClient.FileChooserParams](/reference/android/webkit/WebChromeClient.FileChooserParams) fileChooserParams)

Asks the client app to show a file chooser. The web page has requested to either upload or save a file, such as from a 'file' input in an HTML form or due to a JavaScript API call. The client app can decide whether to show the user a file chooser dialog or to programmatically handle the request. This can be invoked both for uploads and downloads, and it can also be invoked for single files, multiple files, or entire folders. Client apps should check `fileChooserParams` for details about what type of file is being requested.

To show the user a file chooser dialog, override this callback to return `true` and store the `filePathCallback` in a variable. Present the dialog to the user and when they reply, invoke the callback with a URI which points to the file. Make sure to check the `fileChooserParams` in order to show appropriate options in the file chooser dialog, such as automatically filtering by relevant file type (see `[FileChooserParams.getAcceptTypes](/reference/android/webkit/WebChromeClient.FileChooserParams#getAcceptTypes\(\))`).

To handle each request programmatically, respond to this callback with `true`. You can invoke `filePathCallback` with `null` in order to cancel the request or you can invoke the callback with a URI to actual file or folder.

The default behavior is that WebView will cancel all file requests.

**Note:** WebView does not enforce any restrictions on the chosen file(s). WebView can access all files that your app can access. In case the file(s) are chosen through an untrusted source such as a third-party app, it is your own app's responsibility to check what the returned Uris refer to before calling the `filePathCallback`. See `[FileChooserParams.createIntent](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))` and `[FileChooserParams.parseResult](/reference/android/webkit/WebChromeClient.FileChooserParams#parseResult\(int,%20android.content.Intent\))` for more details.

| Parameters |
| --- |
| `webView` | `WebView`: The WebView instance that is initiating the request.
 |
| `filePathCallback` | `ValueCallback`: Invoke this callback to supply the list of paths to files to upload, or `null` to cancel. Must only be called if the `[onShowFileChooser(WebView, ValueCallback, FileChooserParams)](/reference/android/webkit/WebChromeClient#onShowFileChooser\(android.webkit.WebView,%20android.webkit.ValueCallback\<android.net.Uri[]\>,%20android.webkit.WebChromeClient.FileChooserParams\))` implementation returns `true`.

 |
| `fileChooserParams` | `WebChromeClient.FileChooserParams`: Describes the mode of file chooser to be opened, and options to be used with it.

 |

| Returns |
| --- |
| `boolean` | `true` if filePathCallback will be invoked, `false` to use default handling.
 |

**See also:**

-   `[FileChooserParams](/reference/android/webkit/WebChromeClient.FileChooserParams)`
