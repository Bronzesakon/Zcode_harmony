# ActivityResultContracts.PickVisualMedia API

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia?hl=zh-cn
> 页面标题：ActivityResultContracts.PickVisualMedia API
> 快照时间：2026-09-10

---
# ActivityResultContracts.PickVisualMedia

Artifact: [androidx.activity:activity](/jetpack/androidx/releases/activity)

[View Source](https://cs.android.com/search?q=file:androidx/activity/result/contract/ActivityResultContracts.kt+class:androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia)

Added in [1.6.0](/jetpack/androidx/releases/activity#1.6.0)

* * *

[Kotlin](/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia "View this page in Kotlin") |Java

public class [ActivityResultContracts.PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia) extends [ActivityResultContract](/reference/androidx/activity/result/contract/ActivityResultContract)

<table class="jd-inheritance-table"><tbody><tr><td colspan="3"><a href="https://developer.android.com/reference/java/lang/Object.html">java.lang.Object</a></td></tr><tr><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="2"><a href="/reference/androidx/activity/result/contract/ActivityResultContract">androidx.activity.result.contract.ActivityResultContract</a></td></tr><tr><td>&nbsp;</td><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="1"><a href="/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia">androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia</a></td></tr></tbody></table>

* * *

An `[ActivityResultContract](/reference/androidx/activity/result/contract/ActivityResultContract)` to use the [Photo Picker](https://developer.android.com/training/data-storage/shared/photopicker) to select a single image, video, or other type of visual media.

This contract always prefers the system framework provided Photo Picker available via `[MediaStore.ACTION_PICK_IMAGES](https://developer.android.com/reference/android/provider/MediaStore.html#ACTION_PICK_IMAGES)` when it is available, but will also provide a fallback on devices that it is not available to ensure a consistent API surface across all Android API 19 or higher devices.

The priority order for handling the Photo Picker is:

1.  The system framework provided `[MediaStore.ACTION_PICK_IMAGES](https://developer.android.com/reference/android/provider/MediaStore.html#ACTION_PICK_IMAGES)`.
    

-   An OEM can provide a system app that implements `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` to provide a consistent Photo Picker to older devices.
    
-   `[Intent.ACTION_OPEN_DOCUMENT](https://developer.android.com/reference/android/content/Intent.html#ACTION_OPEN_DOCUMENT)` is used as a final fallback on all Android API 19 or higher devices.
    

The input is a `[PickVisualMediaRequest](/reference/androidx/activity/result/PickVisualMediaRequest)`.

The output is a `Uri` when the user has selected a media or `null` when the user hasn't selected any item. Keep in mind that `Uri` returned by the photo picker isn't writable.

This can be extended to override `[createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#createIntent\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))` if you wish to pass additional extras to the Intent created by `super.createIntent()`.

## Summary

 
| 
### Nested types

 |
| --- |
| 

`public abstract class [ActivityResultContracts.PickVisualMedia.DefaultTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab)`

Represents filter input type accepted by the photo picker.

 |
| 

`public static class [ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab) extends [ActivityResultContracts.PickVisualMedia.DefaultTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab)`

`[DefaultTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab)` object used to open the picker in Albums tab.

 |
| 

`public static class [ActivityResultContracts.PickVisualMedia.DefaultTab.PhotosTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab.PhotosTab) extends [ActivityResultContracts.PickVisualMedia.DefaultTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab)`

`[DefaultTab](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.DefaultTab)` object used to open the picker in Photos tab (also the default if no value is provided).

 |
| 

`public static class [ActivityResultContracts.PickVisualMedia.ImageAndVideo](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.ImageAndVideo) implements [ActivityResultContracts.PickVisualMedia.VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)`

`[VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)` object used to filter images and video when using the photo picker.

 |
| 

`public static class [ActivityResultContracts.PickVisualMedia.ImageOnly](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.ImageOnly) implements [ActivityResultContracts.PickVisualMedia.VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)`

`[VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)` object used to filter images only when using the photo picker.

 |
| 

`public final class [ActivityResultContracts.PickVisualMedia.MediaCapabilities](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.MediaCapabilities)`

Represents the media capabilities of an application.

 |
| 

`public final class [ActivityResultContracts.PickVisualMedia.MediaCapabilities.Builder](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.MediaCapabilities.Builder)`

A builder for constructing `[MediaCapabilities](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.MediaCapabilities)` instances.

 |
| 

`public final class [ActivityResultContracts.PickVisualMedia.SingleMimeType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.SingleMimeType) implements [ActivityResultContracts.PickVisualMedia.VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)`

`[VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)` class used to filter a single mime type only when using the photo picker.

 |
| 

`public static class [ActivityResultContracts.PickVisualMedia.VideoOnly](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VideoOnly) implements [ActivityResultContracts.PickVisualMedia.VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)`

`[VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)` object used to filter video only when using the photo picker.

 |
| 

`public sealed interface [ActivityResultContracts.PickVisualMedia.VisualMediaType](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia.VisualMediaType)`

Represents filter input type accepted by the photo picker.

 |

 
| 
### Constants

 |
| --- |
| `static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html)` | 

`[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))`

In cases where the system framework provided `[MediaStore.ACTION_PICK_IMAGES](https://developer.android.com/reference/android/provider/MediaStore.html#ACTION_PICK_IMAGES)` Photo Picker cannot be implemented, OEMs or system apps can provide a consistent Photo Picker experience to those devices by creating an Activity that handles this action.

 |
| `static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html)` | 

`[EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_ACCENT_COLOR](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_ACCENT_COLOR\(\))`

Extra that will be sent by `[PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia)` and `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates the preferred accent color of the picker.

 |
| `static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html)` | 

`[EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_IN_ORDER](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_IN_ORDER\(\))`

Extra that will be sent by `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates allowing the user to control the order in which images are returned to the calling app.

 |
| `static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html)` | 

`[EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_LAUNCH_TAB](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_LAUNCH_TAB\(\))`

Extra that will be sent by `[PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia)` and `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates the preferred default tab of the picker.

 |
| `static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html)` | 

`[EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_MAX](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_MAX\(\))`

Extra that will be sent by `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates that maximum number of photos the user should select.

 |

 
| 
### Public constructors

 |
| --- |
| 

`[PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#PickVisualMedia\(\))()`

 |

 
| 
### Public methods

 |
| --- |
| `@[NonNull](/reference/androidx/annotation/NonNull) [Intent](https://developer.android.com/reference/android/content/Intent.html)` | 

`@[CallSuper](/reference/androidx/annotation/CallSuper)   [createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#createIntent\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))(       @[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context,       @[NonNull](/reference/androidx/annotation/NonNull) [PickVisualMediaRequest](/reference/androidx/activity/result/PickVisualMediaRequest) input   )`

Create an intent that can be used for `[android.app.Activity.startActivityForResult](<https://developer.android.com/reference/android/app/Activity.html#startActivityForResult\(android.content.Intent, int\)>)`.

 |
| `final [ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<[Uri](https://developer.android.com/reference/android/net/Uri.html)>` | 

`[getSynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#getSynchronousResult\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))(       @[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context,       @[NonNull](/reference/androidx/annotation/NonNull) [PickVisualMediaRequest](/reference/androidx/activity/result/PickVisualMediaRequest) input   )`

An optional method you can implement that can be used to potentially provide a result in lieu of starting an activity.

 |
| `static final boolean` | 

`~[isPhotoPickerAvailable](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#isPhotoPickerAvailable\(\))~()`

**This method is deprecated.** This method is deprecated in favor of isPhotoPickerAvailable(context) to support the picker provided by updatable system apps

 |
| `static final boolean` | 

`[isPhotoPickerAvailable](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#isPhotoPickerAvailable\(android.content.Context\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context)`

Check if the current device has support for the photo picker by checking the running Android version, the SDK extension version or the picker provided by a system app implementing `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))`.

 |
| `final [Uri](https://developer.android.com/reference/android/net/Uri.html)` | 

`[parseResult](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#parseResult\(kotlin.Int,android.content.Intent\))(int resultCode, [Intent](https://developer.android.com/reference/android/content/Intent.html) intent)`

Convert result obtained from `[android.app.Activity.onActivityResult](<https://developer.android.com/reference/android/app/Activity.html#onActivityResult\(int, int, android.content.Intent\)>)` to `[O](/reference/androidx/activity/result/contract/ActivityResultContract)`.

 |

## Constants

### ACTION\_SYSTEM\_FALLBACK\_PICK\_IMAGES

public static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html) [ACTION\_SYSTEM\_FALLBACK\_PICK\_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))

In cases where the system framework provided `[MediaStore.ACTION_PICK_IMAGES](https://developer.android.com/reference/android/provider/MediaStore.html#ACTION_PICK_IMAGES)` Photo Picker cannot be implemented, OEMs or system apps can provide a consistent Photo Picker experience to those devices by creating an Activity that handles this action. This app must also include `[Intent.CATEGORY_DEFAULT](https://developer.android.com/reference/android/content/Intent.html#CATEGORY_DEFAULT)` in the activity's intent filter.

Only system apps can implement this action - any non-system apps will be ignored when searching for the activities that handle this Intent.

Note: this should not be used directly, instead relying on the selection logic done by `[createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#createIntent\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))` to create the correct Intent for the current device.

### EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_ACCENT\_COLOR

public static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html) [EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_ACCENT\_COLOR](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_ACCENT_COLOR\(\))

Extra that will be sent by `[PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia)` and `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates the preferred accent color of the picker.

If this extra is not present, the default accent color of the picker will be used.

### EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_IN\_ORDER

public static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html) [EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_IN\_ORDER](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_IN_ORDER\(\))

Extra that will be sent by `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates allowing the user to control the order in which images are returned to the calling app.

### EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_LAUNCH\_TAB

public static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html) [EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_LAUNCH\_TAB](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_LAUNCH_TAB\(\))

Extra that will be sent by `[PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia)` and `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates the preferred default tab of the picker.

If this extra is not present, the default tab of the picker will be used.

### EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_MAX

public static final @[NonNull](/reference/androidx/annotation/NonNull) [String](https://developer.android.com/reference/java/lang/String.html) [EXTRA\_SYSTEM\_FALLBACK\_PICK\_IMAGES\_MAX](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_MAX\(\))

Extra that will be sent by `[PickMultipleVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)` to an Activity that handles `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))` that indicates that maximum number of photos the user should select.

If this extra is not present, only a single photo should be selectable.

If this extra is present but equal to `[Int.MAX_VALUE](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/-companion/-m-a-x_-v-a-l-u-e.html)`, then no limit should be enforced.

## Public constructors

### PickVisualMedia

Added in [1.6.0](/jetpack/androidx/releases/activity#1.6.0)

public [PickVisualMedia](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#PickVisualMedia\(\))()

## Public methods

### createIntent

Added in [1.6.0](/jetpack/androidx/releases/activity#1.6.0)

@[CallSuper](/reference/androidx/annotation/CallSuper)  
public @[NonNull](/reference/androidx/annotation/NonNull) [Intent](https://developer.android.com/reference/android/content/Intent.html) [createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#createIntent\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))(  
    @[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context,  
    @[NonNull](/reference/androidx/annotation/NonNull) [PickVisualMediaRequest](/reference/androidx/activity/result/PickVisualMediaRequest) input  
)

Create an intent that can be used for `[android.app.Activity.startActivityForResult](<https://developer.android.com/reference/android/app/Activity.html#startActivityForResult\(android.content.Intent, int\)>)`.

### getSynchronousResult

Added in [1.6.0](/jetpack/androidx/releases/activity#1.6.0)

public final [ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<[Uri](https://developer.android.com/reference/android/net/Uri.html)\> [getSynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#getSynchronousResult\(android.content.Context,androidx.activity.result.PickVisualMediaRequest\))(  
    @[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context,  
    @[NonNull](/reference/androidx/annotation/NonNull) [PickVisualMediaRequest](/reference/androidx/activity/result/PickVisualMediaRequest) input  
)

An optional method you can implement that can be used to potentially provide a result in lieu of starting an activity.

 
| Returns |
| --- |
| `[ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<[Uri](https://developer.android.com/reference/android/net/Uri.html)>` | 
the result wrapped in a `[SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)` or `null` if the call should proceed to start an activity.

 |

### isPhotoPickerAvailable

Added in [1.6.0](/jetpack/androidx/releases/activity#1.6.0)

Deprecated in [1.7.0](/jetpack/androidx/releases/activity#1.7.0)

public static final boolean ~[isPhotoPickerAvailable](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#isPhotoPickerAvailable\(\))~()

**This method is deprecated.**  
This method is deprecated in favor of isPhotoPickerAvailable(context) to support the picker provided by updatable system apps

Check if the current device has support for the photo picker by checking the running Android version or the SDK extension version.

Note that this does not check for any Intent handled by `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))`.

### isPhotoPickerAvailable

Added in [1.7.0](/jetpack/androidx/releases/activity#1.7.0)

public static final boolean [isPhotoPickerAvailable](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#isPhotoPickerAvailable\(android.content.Context\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context)

Check if the current device has support for the photo picker by checking the running Android version, the SDK extension version or the picker provided by a system app implementing `[ACTION_SYSTEM_FALLBACK_PICK_IMAGES](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#ACTION_SYSTEM_FALLBACK_PICK_IMAGES\(\))`.

### parseResult

public final [Uri](https://developer.android.com/reference/android/net/Uri.html) [parseResult](/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia#parseResult\(kotlin.Int,android.content.Intent\))(int resultCode, [Intent](https://developer.android.com/reference/android/content/Intent.html) intent)

Convert result obtained from `[android.app.Activity.onActivityResult](<https://developer.android.com/reference/android/app/Activity.html#onActivityResult\(int, int, android.content.Intent\)>)` to `[O](/reference/androidx/activity/result/contract/ActivityResultContract)`.
