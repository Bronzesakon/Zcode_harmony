# WebChromeClient.FileChooserParams（acceptTypes 等）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/reference/android/webkit/WebChromeClient.FileChooserParams?hl=zh-cn
> 页面标题：WebChromeClient.FileChooserParams（acceptTypes 等）
> 快照时间：2026-09-10

---
Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

Summary: [Constants](#constants) | [Ctors](#pubctors) | [Methods](#pubmethods) | [Inherited Methods](#inhmethods)

# WebChromeClient.FileChooserParams

* * *

[Kotlin](/reference/kotlin/android/webkit/WebChromeClient.FileChooserParams "View this page in Kotlin") |Java

`public static abstract class WebChromeClient.FileChooserParams`  
`extends [Object](/reference/java/lang/Object)`

<table class="jd-inheritance-table"><tbody><tr><td colspan="2" class="jd-inheritance-class-cell"><a href="/reference/java/lang/Object">java.lang.Object</a></td></tr><tr><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="1" class="jd-inheritance-class-cell">android.webkit.WebChromeClient.FileChooserParams</td></tr></tbody></table>

  

* * *

Parameters used in the `[WebChromeClient.onShowFileChooser(WebView, ValueCallback, FileChooserParams)](/reference/android/webkit/WebChromeClient#onShowFileChooser\(android.webkit.WebView,%20android.webkit.ValueCallback\<android.net.Uri[]\>,%20android.webkit.WebChromeClient.FileChooserParams\))` method.

## Summary

| 
### Constants

 |
| --- |
| `int` | `[MODE_OPEN](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN)`

Open single file.

 |
| `int` | `[MODE_OPEN_FOLDER](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN_FOLDER)`

Like Open but allows a folder to be selected.

 |
| `int` | `[MODE_OPEN_MULTIPLE](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN_MULTIPLE)`

Like Open but allows multiple files to be selected.

 |
| `int` | `[MODE_SAVE](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_SAVE)`

Allows picking a nonexistent file and saving it.

 |
| `int` | `[PERMISSION_MODE_READ](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ)`

File or directory should be opened for reading only.

 |
| `int` | `[PERMISSION_MODE_READ_WRITE](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ_WRITE)`

File or directory should be opened for read and write.

 |

| 
### Public constructors

 |
| --- |
| `[FileChooserParams](/reference/android/webkit/WebChromeClient.FileChooserParams#FileChooserParams\(\))()` |

| 
### Public methods

 |
| --- |
| `abstract [Intent](/reference/android/content/Intent)` | `[createIntent](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))()`

Creates an intent that would start a file picker for file selection.

 |
| `abstract [String[]](/reference/java/lang/String)` | `[getAcceptTypes](/reference/android/webkit/WebChromeClient.FileChooserParams#getAcceptTypes\(\))()`

Returns an array of acceptable MIME types.

 |
| `abstract [String](/reference/java/lang/String)` | `[getFilenameHint](/reference/android/webkit/WebChromeClient.FileChooserParams#getFilenameHint\(\))()`

The file name of a default selection if specified, or `null`.

 |
| `abstract int` | `[getMode](/reference/android/webkit/WebChromeClient.FileChooserParams#getMode\(\))()`

Returns file chooser mode.

 |
| `int` | `[getPermissionMode](/reference/android/webkit/WebChromeClient.FileChooserParams#getPermissionMode\(\))()`

Returns permission mode `[PERMISSION_MODE_READ](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ)` or `[PERMISSION_MODE_READ_WRITE](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ_WRITE)` which indicates the intended mode for opening a file or directory.

 |
| `abstract [CharSequence](/reference/java/lang/CharSequence)` | `[getTitle](/reference/android/webkit/WebChromeClient.FileChooserParams#getTitle\(\))()`

Returns the title to use for this file selector.

 |
| `abstract boolean` | `[isCaptureEnabled](/reference/android/webkit/WebChromeClient.FileChooserParams#isCaptureEnabled\(\))()`

Returns preference for a live media captured value (e.g. Camera, Microphone).

 |
| `static [Uri[]](/reference/android/net/Uri)` | `[parseResult](/reference/android/webkit/WebChromeClient.FileChooserParams#parseResult\(int,%20android.content.Intent\))(int resultCode, [Intent](/reference/android/content/Intent) data)`

Parse the result returned by the file picker activity.

 |

| 
### Inherited methods

 |
| --- |
| 

From class `[java.lang.Object](/reference/java/lang/Object)`

<table class="responsive"><tbody><tr data-version-added="1"><td><code translate="no" dir="ltr"><a href="/reference/java/lang/Object">Object</a></code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#clone()">clone</a>()</code><p>Creates and returns a copy of this object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">boolean</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#equals(java.lang.Object)">equals</a>(<a href="/reference/java/lang/Object">Object</a> obj)</code><p>Indicates whether some other object is "equal to" this one.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#finalize()">finalize</a>()</code><p>Called by the garbage collector on an object when garbage collection determines that there are no more references to the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final <a href="/reference/java/lang/Class">Class</a>&lt;?&gt;</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#getClass()">getClass</a>()</code><p>Returns the runtime class of this <code translate="no" dir="ltr">Object</code>.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">int</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#hashCode()">hashCode</a>()</code><p>Returns a hash code value for the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#notify()">notify</a>()</code><p>Wakes up a single thread that is waiting on this object's monitor.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#notifyAll()">notifyAll</a>()</code><p>Wakes up all threads that are waiting on this object's monitor.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr"><a href="/reference/java/lang/String">String</a></code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#toString()">toString</a>()</code><p>Returns a string representation of the object.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait(long,%20int)">wait</a>(long timeoutMillis, int nanos)</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>, or until a certain amount of real time has elapsed.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait(long)">wait</a>(long timeoutMillis)</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>, or until a certain amount of real time has elapsed.</p></td></tr><tr data-version-added="1"><td><code translate="no" dir="ltr">final void</code></td><td width="100%"><code translate="no" dir="ltr"><a href="/reference/java/lang/Object#wait()">wait</a>()</code><p>Causes the current thread to wait until it is awakened, typically by being <em>notified</em> or <em>interrupted</em>.</p></td></tr></tbody></table>


 |

## Constants

### MODE\_OPEN

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MODE\_OPEN

Open single file. Requires that the file exists before allowing the user to pick it.

Constant Value: 0 (0x00000000)

### MODE\_OPEN\_FOLDER

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MODE\_OPEN\_FOLDER

Like Open but allows a folder to be selected.

Constant Value: 2 (0x00000002)

### MODE\_OPEN\_MULTIPLE

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MODE\_OPEN\_MULTIPLE

Like Open but allows multiple files to be selected.

Constant Value: 1 (0x00000001)

### MODE\_SAVE

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MODE\_SAVE

Allows picking a nonexistent file and saving it.

Constant Value: 3 (0x00000003)

### PERMISSION\_MODE\_READ

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int PERMISSION\_MODE\_READ

File or directory should be opened for reading only.

Constant Value: 0 (0x00000000)

### PERMISSION\_MODE\_READ\_WRITE

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int PERMISSION\_MODE\_READ\_WRITE

File or directory should be opened for read and write.

Constant Value: 1 (0x00000001)

## Public constructors

### FileChooserParams

public FileChooserParams ()

## Public methods

### createIntent

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract [Intent](/reference/android/content/Intent) createIntent ()

Creates an intent that would start a file picker for file selection. The Intent supports choosing files from simple file sources available on the device. Some advanced sources (for example, live media capture) may not be supported and applications wishing to support these sources or more advanced file operations should build their own Intent.

How to use:

1.  Build an intent using `[createIntent()](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))`
2.  Fire the intent using `[Activity.startActivityForResult(Intent, int)](/reference/android/app/Activity#startActivityForResult\(android.content.Intent,%20int\))`.
3.  Check for ActivityNotFoundException and take a user friendly action if thrown.
4.  Listen the result using `[Activity.onActivityResult(int, int, Intent)](/reference/android/app/Activity#onActivityResult\(int,%20int,%20android.content.Intent\))`
5.  Parse the result using `[parseResult(int, Intent)](/reference/android/webkit/WebChromeClient.FileChooserParams#parseResult\(int,%20android.content.Intent\))` only if media capture was not requested.
6.  Send the result using filePathCallback of `[WebChromeClient.onShowFileChooser](/reference/android/webkit/WebChromeClient#onShowFileChooser\(android.webkit.WebView,%20android.webkit.ValueCallback\<android.net.Uri[]\>,%20android.webkit.WebChromeClient.FileChooserParams\))`

**Note:** The created intent may be handled by third-party applications on device. The received result must be treated as untrusted as it can contain Uris pointing to your own app's sensitive data files. Your app should check the resultant Uris in `[parseResult(int, Intent)](/reference/android/webkit/WebChromeClient.FileChooserParams#parseResult\(int,%20android.content.Intent\))` before calling the `filePathCallback`.

| Returns |
| --- |
| `[Intent](/reference/android/content/Intent)` | an Intent that supports basic file chooser sources.
 |

### getAcceptTypes

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract [String\[\]](/reference/java/lang/String) getAcceptTypes ()

Returns an array of acceptable MIME types. The returned MIME type could be partial such as audio/\*. The array will be empty if no acceptable types are specified.

| Returns |
| --- |
| `[String[]](/reference/java/lang/String)` | 
 |

### getFilenameHint

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract [String](/reference/java/lang/String) getFilenameHint ()

The file name of a default selection if specified, or `null`.

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | 
 |

### getMode

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract int getMode ()

Returns file chooser mode.

| Returns |
| --- |
| `int` | Value is one of the following:
-   `[MODE_OPEN](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN)`
-   `[MODE_OPEN_MULTIPLE](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN_MULTIPLE)`
-   `[MODE_OPEN_FOLDER](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_OPEN_FOLDER)`
-   `[MODE_SAVE](/reference/android/webkit/WebChromeClient.FileChooserParams#MODE_SAVE)`


 |

### getPermissionMode

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public int getPermissionMode ()

Returns permission mode `[PERMISSION_MODE_READ](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ)` or `[PERMISSION_MODE_READ_WRITE](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ_WRITE)` which indicates the intended mode for opening a file or directory. This can be used to determine whether an Intent such as `[Intent.ACTION_OPEN_DOCUMENT](/reference/android/content/Intent#ACTION_OPEN_DOCUMENT)` should be used rather than `[Intent.ACTION_GET_CONTENT](/reference/android/content/Intent#ACTION_GET_CONTENT)` to choose files.

| Returns |
| --- |
| `int` | Value is one of the following:
-   `[PERMISSION_MODE_READ](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ)`
-   `[PERMISSION_MODE_READ_WRITE](/reference/android/webkit/WebChromeClient.FileChooserParams#PERMISSION_MODE_READ_WRITE)`


 |

### getTitle

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract [CharSequence](/reference/java/lang/CharSequence) getTitle ()

Returns the title to use for this file selector. If `null` a default title should be used.

| Returns |
| --- |
| `[CharSequence](/reference/java/lang/CharSequence)` | 
 |

### isCaptureEnabled

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public abstract boolean isCaptureEnabled ()

Returns preference for a live media captured value (e.g. Camera, Microphone). True indicates capture is enabled, `false` disabled. Use `getAcceptTypes` to determine suitable capture devices.

| Returns |
| --- |
| `boolean` | 
 |

### parseResult

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri\[\]](/reference/android/net/Uri) parseResult (int resultCode, 
                [Intent](/reference/android/content/Intent) data)

Parse the result returned by the file picker activity. This method should be used with `[createIntent()](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))`. Refer to `[createIntent()](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))` for how to use it.

**Note:** The intent returned by the file picker activity should be treated as untrusted. A third-party app handling the implicit intent created by `[createIntent()](/reference/android/webkit/WebChromeClient.FileChooserParams#createIntent\(\))` might return Uris that the third-party app itself does not have access to, such as your own app's sensitive data files. WebView does not enforce any restrictions on the returned Uris. It is the app's responsibility to ensure that the untrusted source (such as a third-party app) has access the Uris it has returned and that the Uris are not pointing to any sensitive data files.

| Parameters |
| --- |
| `resultCode` | `int`: the integer result code returned by the file picker activity.
 |
| `data` | `Intent`: the intent returned by the file picker activity.

 |

| Returns |
| --- |
| `[Uri[]](/reference/android/net/Uri)` | the Uris of selected file(s) or `null` if the resultCode indicates activity canceled or any other error.
 |
