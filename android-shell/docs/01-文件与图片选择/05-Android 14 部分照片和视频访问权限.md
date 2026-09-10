# Android 14 部分照片和视频访问权限

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/about/versions/14/changes/partial-photo-video-access?hl=zh-cn
> 页面标题：授予对照片和视频的部分访问权限

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
Android 14 引入了所选照片访问权限，可让用户授予应用对其库中特定图片和视频的访问权限，而不是授予对给定类型的所有媒体内容的访问权限。

只有当您的应用以 Android 14（API 级别 34）或更高版本为目标平台时，此更改才会启用。如果您尚未使用照片选择器，我们建议您[在应用中实现照片选择器](https://developer.android.com/training/data-storage/shared/photopicker?hl=zh-cn)，以提供一致的图片和视频选择体验，同时增强用户隐私保护，而无需请求任何存储权限。

如果您使用存储权限维护自己的图库选择器，并且需要对实现保持完全控制，请[调整实现](#app-gallery-picker)以使用新的 [`READ_MEDIA_VISUAL_USER_SELECTED`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_VISUAL_USER_SELECTED) 权限。如果您的应用不使用新权限，系统会以[兼容模式](#compatibility-mode)运行您的应用。

| 目标 SDK 版本 | 已声明[`READ_MEDIA_VISUAL_USER_SELECTED`](#permissions) | 已启用部分 Google 相册访问权限 | 用户体验行为 |
| --- | --- | --- | --- |
| SDK 33 | 否 | 否 | 不适用 |
| 是 | 是 | [由应用控制](#media-reselection) |
| SDK 34 | 否 | 是 | 由系统控制（兼容行为）[](#compatibility-mode) |
| 是 | 是 | [由应用控制](#media-reselection) |

## 创建或自定义自己的图库选择器

创建自己的图库选择器需要进行大量开发和维护，并且您的应用需要请求存储权限才能征得用户明确同意。用户可以拒绝这些请求，或者（如果您的应用在搭载 Android 14 的设备上运行，并且以 Android 14 \[API 级别 34\] 或更高版本为目标平台）限制对所选媒体的访问权限。下图展示了使用新选项请求权限和选择媒体的示例。

![.](https://developer.android.com/static/about/versions/14/images/partial-photo-video-access.png?hl=zh-cn)

**图 1.** 除了授予完整访问权限或拒绝所有访问权限的常规选项之外，用户还可以通过新对话框选择希望提供给应用的具体照片和视频。

本部分演示了使用 `MediaStore` 创建自定义图库选择器的推荐方法。如果您已为应用维护图库选择器，并且需要保持完全控制，则可以使用以下示例来调整实现。如果您不更新实现以处理“已选照片访问权限”，系统会以[兼容模式](#compatibility-mode)运行您的应用。

### 请求权限

首先，根据操作系统版本在 Android 清单中请求正确的存储权限：

```
<!-- Devices running Android 12L (API level 32) or lower  -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVers>io<n="32" /

!-- Devices running Android 13 (API le>v<el 33) or higher --
uses-permission android:name="android.permis>s<ion.READ_MEDIA_IMAGES" /
uses-permission android:name="and>ro<id.permission.READ_MEDIA_VIDEO" /

!-- To handle the reselection within the app on devices running Android 14
     or higher if your app targets >A<ndroid 14 (API level 34) or higher.  --
uses-permission android:name="android.>p
```

然后，根据操作系统版本请求正确的运行时权限：

```
// Register ActivityResult handler
val requestPermissions = registerForActivityResult(RequestMultiplePermissions()) { results ->
    // Handle permission requests results
    // See the permission example in the Android platform samples: https://github.com/android/platform-samples
}

// Permission request logic
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    requestPermissions.launch(arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED))
} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions.launch(arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO))
} else {
    requestPermissions.launch(arrayOf(READ_EXTERNAL_STORAGE))
}
```

#### 某些应用不需要权限

从 Android 10（API 级别 29）开始，应用无需存储权限即可将文件添加到共享存储空间。这意味着，应用无需请求存储权限，即可将图片添加到图库、录制视频并将其保存到共享存储空间，或下载 PDF 账单。如果您的应用仅向共享存储空间添加文件，而不会查询图片或视频，您应停止请求存储权限，并在 `AndroidManifest.xml` 中设置 API 28 的 `maxSdkVersion`：

```
<!-- No permission is needed to add files to shared storage on Android 10 (API level 29) or higher  -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVers>i
```

### 处理媒体重新选择

借助 Android 14 中的“所选照片访问权限”功能，您的应用应采用新的 [`READ_MEDIA_VISUAL_USER_SELECTED`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_VISUAL_USER_SELECTED) 权限来控制媒体重新选择，并更新应用界面，以便用户向您的应用授予对一组不同图片和视频的访问权限。下图显示了请求权限和重新选择媒体的示例：

![.](https://developer.android.com/static/about/versions/14/images/partial-photo-video-access-reselect.png?hl=zh-cn)

**图 2.** 在新对话框中，用户还可以重新选择要向您的应用提供哪些照片和视频。

打开选择对话框时，系统会根据请求的权限显示照片和/或视频。例如，如果您请求 `READ_MEDIA_VIDEO` 权限但未请求 `READ_MEDIA_IMAGES` 权限，则界面中只会显示视频供用户选择文件。

```
// Allow the user to select only videos
requestPermissions.launch(arrayOf(READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED))
```

**注意**： 请在应用中创建一个用户界面元素，用户必须先按此界面元素，您才能重新请求 `READ_MEDIA_IMAGES` 或 `READ_MEDIA_VIDEO` 权限。用户应该不会惊讶地再次看到该系统对话框。

您可以检查应用是否拥有对设备照片库的完整、部分或被拒绝的访问权限，并相应地更新界面。请在应用需要存储空间访问权限时（而不是在启动时）请求这些权限。请注意，在 `onStart` 和 `onResume` 应用生命周期回调之间可以更改权限授予，因为用户可以在不关闭应用的情况下在设置中更改访问权限。

```
if (
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    (
        ContextCompat.checkSelfPermission(context, READ_MEDIA_IMAGES) == PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, READ_MEDIA_VIDEO) == PERMISSION_GRANTED
    )
) {
    // Full access on Android 13 (API level 33) or higher
} else if (
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
    ContextCompat.checkSelfPermission(context, READ_MEDIA_VISUAL_USER_SELECTED) == PERMISSION_GRANTED
) {
    // Partial access on Android 14 (API level 34) or higher
}  else if (ContextCompat.checkSelfPermission(context, READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
    // Full access up to Android 12 (API level 32)
} else {
    // Access denied
}
```

### 查询设备库

在确认您拥有适当的存储权限后，您可以与 `MediaStore` 交互以查询设备库（无论授予的访问权限是部分访问权限还是完整访问权限，都可以使用相同的方法）：

```
data class Media(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
)

// Run the querying logic in a coroutine outside of the main thread to keep the app responsive.
// Keep in mind that this code snippet is querying only images of the shared storage.
suspend fun getImages(contentResolver: ContentResolver): List<Media> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        Images.Media._ID,
        Images.Media.DISPLAY_NAME,
        Images.Media.SIZE,
        Images.Media.MIME_TYPE,
    )

    val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Query all the device storage volumes instead of the primary only
        Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        Images.Media.EXTERNAL_CONTENT_URI
    }

    val images = mutableListOf<Media>()

    contentResolver.query(
        collectionUri,
        projection,
        null,
        null,
        "${Images.Media.DATE_ADDED} DESC"
    )?.use >{ cursor -
        val idColumn = cursor.getColumnIndexOrThrow(Images.Media._ID)
        val displayNameColumn = cursor.getColumnIndexOrThrow(Images.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(Images.Media.SIZE)
        val mimeTypeColumn = cursor.getColumnIndexOrThrow(Images.Media.MIME_TYPE)

        while (cursor.moveToNext()) {
            val uri = ContentUris.withAppendedId(collectionUri, cursor.getLong(idColumn))
            val name = cursor.getString(displayNameColumn)
            val size = cursor.getLong(sizeColumn)
            val mimeType = cursor.getString(mimeTypeColumn)

            val image = Media(uri, name, size, mimeType)
            images.add(image)
        }
    }

    return@withContext
```

此代码段经过简化，以说明如何与 `MediaStore` 交互。在正式版应用中，请将分页与 [Paging 库](https://developer.android.com/topic/libraries/architecture/paging/v3-overview?hl=zh-cn)等内容搭配使用，以确保良好的性能。

### 查询上次的选择

在 Android 15 及更高版本以及支持 Google Play 系统更新的 Android 14 上，应用可以通过启用 [`QUERY_ARG_LATEST_SELECTION_ONLY`](https://developer.android.com/reference/android/provider/MediaStore?hl=zh-cn#QUERY_ARG_LATEST_SELECTION_ONLY) 来查询用户在使用部分访问权限时最后选择的图片和视频：

```
if (getExtensionVersion(Build.VERSION_CODES.U) >= 12) {
    val queryArgs = bundleOf(
        QUERY_ARG_SQL_SORT_ORDER to "${Images.Media.DATE_ADDED} DESC"
        QUERY_ARG_LATEST_SELECTION_ONLY to true
    )

    contentResolver.query(collectionUri, projection, queryArgs,
```

### 升级设备后，照片和视频的访问权限将保留

如果您的应用安装在从较低 Android 版本升级到 Android 14 的设备上，系统会保留对用户照片和视频的完整访问权限，并自动向您的应用授予一些权限。确切行为取决于在设备升级到 Android 14 之前向您的应用授予的一组权限。

**注意：**用户仍然可能会手动拒绝授予权限，或者设备公司政策或自动重置权限功能可能会拒绝授予权限。请务必检查权限，而不是假定之前授予的状态。

#### Android 13 的权限

请考虑以下情况：

1.  您的应用安装在搭载 Android 13 的设备上。
2.  用户已向您的应用授予 `READ_MEDIA_IMAGES` 和 `READ_MEDIA_VIDEO` 权限。
3.  然后，当您的应用仍保持安装状态时，设备升级到 Android 14。
4.  您的应用开始以 Android 14（API 级别 34）或更高版本为目标平台。

在此情况下，您的应用仍然拥有对用户照片和视频的完整访问权限。系统还会保留已自动授予您的应用的 `READ_MEDIA_IMAGES` 权限和 `READ_MEDIA_VIDEO` 权限。

#### Android 12 及更低版本的权限

请考虑以下情况：

1.  您的应用安装在搭载 Android 13 的设备上。
2.  用户已向您的应用授予 `READ_EXTERNAL_STORAGE` 权限或 `WRITE_EXTERNAL_STORAGE` 权限。
3.  然后，当您的应用仍保持安装状态时，设备升级到 Android 14。
4.  您的应用开始以 Android 14（API 级别 34）或更高版本为目标平台。

在此情况下，您的应用仍然拥有对用户照片和视频的完整访问权限。系统还会自动向您的应用授予 `READ_MEDIA_IMAGES` 权限和 `READ_MEDIA_VIDEO` 权限。

### 最佳做法

本部分包含有关使用 `READ_MEDIA_VISUAL_USER_SELECTED` 权限的几项最佳实践。如需了解详情，请参阅我们的[权限最佳实践](https://developer.android.com/training/permissions/requesting?hl=zh-cn#principles)。

#### 请勿永久存储权限状态

请勿永久存储权限状态（包括 `SharedPreferences` 或 `DataStore`）。存储的状态可能与实际状态不同步。权限状态可能会在以下情况下发生更改：[权限重置](https://developer.android.com/about/versions/11/privacy/permissions?hl=zh-cn#auto-reset)、[应用休眠](https://developer.android.com/about/versions/12/behavior-changes-12?hl=zh-cn#app-hibernation)、用户在应用设置中发起更改，或者应用进入后台。请改用 [`ContextCompat.checkSelfPermission()`](https://developer.android.com/reference/androidx/core/content/ContextCompat?hl=zh-cn#checkSelfPermission\(android.content.Context,java.lang.String\)) 检查存储权限。

#### 不要假定拥有对照片和视频的完整访问权限

根据 Android 14 中引入的更改，您的应用可能只对设备的照片库拥有部分访问权限。如果应用在使用 `ContentResolver` 进行查询时缓存了 `MediaStore` 数据，则缓存可能不是最新的。

-   应始终使用 `ContentResolver` 查询 `MediaStore`，而不是依赖于存储的缓存。
-   当应用在前台运行时，将结果保存在内存中。
-   在应用经历 `onResume` 应用生命周期时刷新结果，因为用户可能会通过权限设置从完全访问权限切换到部分访问权限。

#### 将 URI 访问权限视为临时访问权限

如果用户在系统权限对话框中选择**选择照片和视频**，您的应用对所选照片和视频的访问权限最终会过期。无论发生何种授权，应用应始终处理无法访问任何 `Uri` 的情况。

#### 按权限过滤可选择的媒体类型

选择对话框受请求的权限类型的影响：

-   仅请求 `READ_MEDIA_IMAGES` 会仅显示可供选择的图片。
-   仅请求 `READ_MEDIA_VIDEO` 会仅显示可供选择的视频。
-   同时请求 `READ_MEDIA_IMAGES` 和 `READ_MEDIA_VIDEO` 会显示整个照片库以供选择。

根据应用的使用场景，您应确保请求正确的权限，以免用户体验不佳。如果某项功能仅预期选择视频，请务必仅请求 `READ_MEDIA_VIDEO`。

#### 在单个操作中请求权限

如需阻止用户看到多个系统运行时对话框，请在单次操作中请求 `READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION` 和“读取媒体”权限（`READ_MEDIA_IMAGES` 和/或 `READ_MEDIA_VIDEO`）。

#### 允许用户管理自己的选择

当用户选择部分访问模式时，您的应用不应假定设备的照片库为空，而应允许用户授予对更多文件的访问权限。

用户可能会决定通过权限设置从完全访问权限切换为部分访问权限，而无需授予对某些视觉媒体文件的访问权限。

## 兼容模式

如果您使用存储权限维护自己的图库选择器，但尚未[调整应用](#app-gallery-picker)以使用新的 [`READ_MEDIA_VISUAL_USER_SELECTED`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_VISUAL_USER_SELECTED) 权限，那么每当用户需要选择或重新选择媒体时，系统都会在兼容模式下运行您的应用。

### 初始媒体选择期间的行为

在初始选择期间，如果用户选择“选择照片和视频”（请参阅[图 1](#figure-1)），系统会在应用会话期间授予 `READ_MEDIA_IMAGES` 和 `READ_MEDIA_VIDEO` 权限，从而提供对用户选择的照片和视频的临时授权和临时访问权限。当您的应用转到后台或当用户主动终止您的应用时，系统最终会拒绝这些权限。此行为就像其他单次授权一样。

### 媒体重新选择期间的行为

如果您的应用稍后需要访问其他照片和视频，您必须再次手动请求 `READ_MEDIA_IMAGES` 权限或 `READ_MEDIA_VIDEO` 权限。系统遵循与初始权限请求相同的流程，提示用户选择照片和视频（如[图 2](#figure-2) 所示）。

如果您的应用遵循了[权限最佳实践](#best-practices)，则此更改不应破坏您的应用。如果您的应用不假设已保留 URI 访问权限、存储系统权限状态或在权限更改后刷新显示的一组图片，则尤其如此。不过，这种行为可能并不理想，具体取决于应用的用例。为了帮助您为用户提供最佳体验，我们建议您[实现照片选择器](https://developer.android.com/training/data-storage/shared/photopicker?hl=zh-cn)或[调整应用的图库选择器](#app-gallery-picker)，以便直接使用 `READ_MEDIA_VISUAL_USER_SELECTED` 权限处理此行为。
