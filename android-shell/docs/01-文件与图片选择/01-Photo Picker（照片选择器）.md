# Photo Picker（照片选择器）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/training/data-storage/shared/photo-picker?hl=zh-cn
> 页面标题：照片选择器

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
![照片选择器对话框中会显示设备上的媒体文件。您可选择要与应用分享的照片。](https://developer.android.com/static/images/training/data-storage/kotlin-picker.gif?hl=zh-cn)

**图 1.** 照片选择器提供了一个直观的界面，便于与您的应用分享照片。

照片选择器提供了一个可浏览的界面，按日期从新到旧向用户显示其媒体库。如[隐私保护最佳实践 Codelab](https://developer.android.com/codelabs/android-privacy-codelab?hl=zh-cn) 中所示，照片选择器为用户提供了一种安全的内置授权方式，让用户可以仅向应用授予对所选图片和视频的访问权限，而不是对整个媒体库的访问权限。

如果用户在设备上拥有符合条件的云媒体服务提供商，还可以选择远程存储的照片和视频。[详细了解云媒体提供商](https://developer.android.com/guide/topics/providers/cloud-media-provider?hl=zh-cn)。

该工具会自动更新，让应用用户能够长期使用扩展的功能，而无需更改任何代码。

## 使用 Jetpack activity 协定

为了简化照片选择器的集成，请添加 1.7.0 版或更高版本的 [`androidx.activity`](https://developer.android.com/jetpack/androidx/releases/activity?hl=zh-cn#1.7.0) 库。

您可以使用以下 activity 结果协定来启动照片选择器：

-   [`PickVisualMedia`](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia?hl=zh-cn)，用于[选择单张图片或单个视频](#select-single-item)。
-   [`PickMultipleVisualMedia`](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia?hl=zh-cn)，用于[选择多张图片或多个视频](#select-multiple-items)。

如果照片选择器在设备上不可用，该库会自动调用 [`ACTION_OPEN_DOCUMENT`](https://developer.android.com/reference/kotlin/android/content/Intent?hl=zh-cn#action_open_document) intent 操作。搭载 Android 4.4（API 级别 19）或更高版本的设备支持此 intent。您可以通过调用 [`isPhotoPickerAvailable()`](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia?hl=zh-cn#isPhotoPickerAvailable\(android.content.Context\)) 来验证照片选择器在给定设备上是否可用。

### 选择单个媒体文件

如需选择单个媒体项，请使用 `PickVisualMedia` activity 结果协定，如以下代码段所示：

### 观看次数

// Registers a photo picker activity launcher in single-select mode.
val pickMedia \= registerForActivityResult(PickVisualMedia()) { uri \->
    // Callback is invoked after the user selects a media item or closes the
    // photo picker.
    if (uri != null) {
        Log.d("PhotoPicker", "Selected URI: $uri")
    } else {
        Log.d("PhotoPicker", "No media selected")
    }
}

// Include only one of the following calls to launch(), depending on the types
// of media that you want to let the user choose from.

// Launch the photo picker and let the user choose images and videos.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))

// Launch the photo picker and let the user choose only images.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))

// Launch the photo picker and let the user choose only videos.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))

// Launch the photo picker and let the user choose only images/videos of a
// specific MIME type, such as GIFs.
val mimeType \= "image/gif"
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.SingleMimeType(mimeType)))

### 视图

// Registers a photo picker activity launcher in single-select mode.
ActivityResultLauncher<PickVisualMediaRequest> pickMedia \=
        registerForActivityResult(new PickVisualMedia(), uri \-\> {
    // Callback is invoked after the user selects a media item or closes the
    // photo picker.
    if (uri != null) {
        Log.d("PhotoPicker", "Selected URI: " + uri);
    } else {
        Log.d("PhotoPicker", "No media selected");
    }
});

// Include only one of the following calls to launch(), depending on the types
// of media that you want to let the user choose from.

// Launch the photo picker and let the user choose images and videos.
pickMedia.launch(new PickVisualMediaRequest.Builder()
        .setMediaType(PickVisualMedia.ImageAndVideo.INSTANCE)
        .build());

// Launch the photo picker and let the user choose only images.
pickMedia.launch(new PickVisualMediaRequest.Builder()
        .setMediaType(PickVisualMedia.ImageOnly.INSTANCE)
        .build());

// Launch the photo picker and let the user choose only videos.
pickMedia.launch(new PickVisualMediaRequest.Builder()
        .setMediaType(PickVisualMedia.VideoOnly.INSTANCE)
        .build());

// Launch the photo picker and let the user choose only images/videos of a
// specific MIME type, such as GIFs.
String mimeType \= "image/gif";
pickMedia.launch(new PickVisualMediaRequest.Builder()
        .setMediaType(new PickVisualMedia.SingleMimeType(mimeType))
        .build());

### Compose

// Registers a photo picker activity launcher in single-select mode.
val pickMedia \= rememberLauncherForActivityResult(PickVisualMedia()) { uri \->
    // Callback is invoked after the user selects a media item or closes the
    // photo picker.
    if (uri != null) {
        Log.d("PhotoPicker", "Selected URI: $uri")
    } else {
        Log.d("PhotoPicker", "No media selected")
    }
}

// Include only one of the following calls to launch(), depending on the types
// of media that you want to let the user choose from.

// Launch the photo picker and let the user choose images and videos.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))

// Launch the photo picker and let the user choose only images.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))

// Launch the photo picker and let the user choose only videos.
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))

// Launch the photo picker and let the user choose only images/videos of a
// specific MIME type, such as GIFs.
val mimeType \= "image/gif"
pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.SingleMimeType(mimeType)))

**注意：**使用 `PickVisualMedia` 时，照片选择器会以半屏模式打开。

### 选择多个媒体项

如需选择多个媒体项，请设置可选媒体文件数量上限，如以下代码段所示。

### 观看次数

// Registers a photo picker activity launcher in multi-select mode.
// In this example, the app lets the user select up to 5 media files.
val pickMultipleMedia \=
        registerForActivityResult(PickMultipleVisualMedia(5)) { uris \-\>
    // Callback is invoked after the user selects media items or closes the
    // photo picker.
    if (uris.isNotEmpty()) {
        Log.d("PhotoPicker", "Number of items selected: ${uris.size}")
    } else {
        Log.d("PhotoPicker", "No media selected")
    }
}

// For this example, launch the photo picker and let the user choose images
// and videos. If you want the user to select a specific type of media file,
// use the overloaded versions of launch(), as shown in the section about how
// to [select a single media item](#select-single-item).
pickMultipleMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))

### 视图

// Registers a photo picker activity launcher in multi-select mode.
// In this example, the app lets the user select up to 5 media files.
ActivityResultLauncher<PickVisualMediaRequest> pickMultipleMedia \=
        registerForActivityResult(new PickMultipleVisualMedia(5), uris \-> {
    // Callback is invoked after the user selects media items or closes the
    // photo picker.
    if (!uris.isEmpty()) {
        Log.d("PhotoPicker", "Number of items selected: " + uris.size());
    } else {
        Log.d("PhotoPicker", "No media selected");
    }
});

// For this example, launch the photo picker and let the user choose images
// and videos. If you want the user to select a specific type of media file,
// use the overloaded versions of launch(), as shown in the section about how
// to [select a single media item](#select-single-item).
pickMultipleMedia.launch(new PickVisualMediaRequest.Builder()
        .setMediaType(PickVisualMedia.ImageAndVideo.INSTANCE)
        .build());

### 写邮件

// Registers a photo picker activity launcher in multi-select mode.
// In this example, the app lets the user select up to 5 media files.
val pickMultipleMedia \=
        rememberLauncherForActivityResult(PickMultipleVisualMedia(5)) { uris \-\>
    // Callback is invoked after the user selects media items or closes the
    // photo picker.
    if (uris.isNotEmpty()) {
        Log.d("PhotoPicker", "Number of items selected: ${uris.size}")
    } else {
        Log.d("PhotoPicker", "No media selected")
    }
}

// For this example, launch the photo picker and let the user choose images
// and videos. If you want the user to select a specific type of media file,
// use the overloaded versions of launch(), as shown in the section about how
// to [select a single media item](#select-single-item).
pickMultipleMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))

平台会限制您可以让用户在照片选择器中选择的文件数量上限。如需访问此限制，请调用 [`getPickImagesMaxLimit()`](https://developer.android.com/reference/android/provider/MediaStore?hl=zh-cn#getPickImagesMaxLimit\(\))。 在不支持照片选择器的设备上，系统会忽略此上限。

**注意：**如果照片选择器不可用，且支持库调用 `ACTION_OPEN_DOCUMENT` intent 操作，则系统会忽略指定的可选媒体文件数量上限。

## 适用的设备

照片选择器适用于符合以下条件的设备：

-   搭载 Android 11（API 级别 30）或更高版本
-   通过 [Google 系统更新](https://support.google.com/product-documentation/answer/11412553?hl=zh-cn)接收对[模块化系统组件](https://source.android.com/devices/architecture/modular-system?hl=zh-cn)的更改

搭载 Android 4.4（API 级别 19）到 Android 10（API 级别 29）的旧款设备，以及搭载 Android 11 或 12 且支持 Google Play 服务的 Android Go 设备，都可以安装向后移植的照片选择器版本。如需通过 Google Play 服务自动安装向后移植的照片选择器模块，请将以下条目添加到应用清单文件的 `<application>` 标记中：

```
<!-- Trigger Google Play services to install the backported photo picker module. -->
<service android:name="com.google.android.gms.metadata.ModuleDependencies"
         android:enabled="false"
         android:exported="false"
         tools:ignore="MissingClass">
    <intent-filter>
        <action android:name="com.google.android.gms.metadata.MODULE_DEPENDENCIES" />
    </intent-filter>
    <meta-data android:name="photopicker_activity:0:required" android:value="" />
</service>
```

## 保留媒体文件访问权限

默认情况下，系统会授予应用对媒体文件的访问权限，直到设备重启或应用停止运行。如果您的应用执行长时间运行的工作（例如在后台上传大型文件），您可能需要将此访问权限保留更长时间。为此，请调用 [`takePersistableUriPermission()`](https://developer.android.com/reference/android/content/ContentResolver?hl=zh-cn#takePersistableUriPermission\(android.net.Uri,%20int\)) 方法：

### Kotlin

val flag \= Intent.FLAG\_GRANT\_READ\_URI\_PERMISSION
context.contentResolver.takePersistableUriPermission(uri, flag)

### Java

int flag \= Intent.FLAG\_GRANT\_READ\_URI\_PERMISSION;
context.contentResolver.takePersistableUriPermission(uri, flag);

**注意**： 一个应用在任何时间最多只能有 5,000 个媒体授权。在选择 5,000 张照片或视频后，每授予一次额外的照片或视频访问权限，系统都会移除列表中的第一个授予权限，以满足此请求。

## 通过转码处理 HDR 视频

Android 13（API 33）引入了拍摄 **高动态范围 (HDR) 视频**的功能。虽然 HDR 可提供更丰富的视觉体验，但某些旧版应用可能无法处理这些新格式，从而导致播放期间出现色彩渲染不自然等问题（例如面部带有绿色色调）。为了弥合这一兼容性差距，照片选择器提供了一项转码功能，可在将 HDR 视频提供给请求的应用之前，自动将其转换为**标准动态范围 (SDR)** 格式。

照片选择器转码的主要目标是确保在更广泛的应用（即使是那些尚未明确支持 HDR 的应用）中提供一致且视觉上准确的媒体体验。通过将 HDR 视频转码为 SDR，照片选择器旨在提高应用兼容性并提供顺畅的用户体验。

### 照片选择器转码的运作方式

照片选择器 HDR 转码功能默认未启用。如需启用此功能，您的应用需要在启动照片选择器时明确声明其媒体格式处理功能。

您的应用向照片选择器提供其媒体处理功能。通过向 `PickVisualMediaRequest.Builder` 添加 `mediaCapabilities`，在使用 AndroidX Activity 库启动照片选择器时完成此操作。`PickVisualMediaRequest.Builder` 中添加了一个新 API `setMediaCapabilitiesForTranscoding(capabilities: MediaCapabilities?)`，以实现此目的。

您可以使用 `MediaCapabilities` 类控制 HDR 转码行为。提供一个 `MediaCapabilities` 对象，用于准确指定您的应用支持哪些 HDR 类型（例如，`TYPE_HLG10`、`TYPE_HDR10`、`TYPE_HDR10_PLUS`、`TYPE_DOLBY_VISION`）。

如需完全停用转码，请为 `MediaCapabilities` 传递 `null`。您提供的功能中未明确列出的任何 HDR 类型都将被视为不受支持。此 API 在 **Android 13（API 级别 33）及更高版本**中受支持，并使用 `@RequiresApi(Build.VERSION_CODES.TIRAMISU)` 进行注释。

```
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.RequiresApi
import android.os.Build
import android.util.Log
import android.provider.MediaStore

// Registers a photo picker activity launcher.
val pickMedia = registerForActivityResult(PickVisualMedia()) { uri ->
    // Callback invoked after media selected or picker activity closed.
    if (uri != null) {
        Log.d("photo picker", "Selected URI: $uri")
    } else {
        Log.d("photo picker", "No media selected")
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun launchPhotoPickerWithTranscodingSupport() {
    val mediaCapabilities = MediaCapabilities.Builder()
        .addSupportedHdrType(MediaCapabilities.HdrType.TYPE_HLG10)
        .build()

    // Launch the photo picker and let the user choose only videos with
    // transcoding enabled.
    pickMedia.launch(PickVisualMediaRequest.Builder()
        .setMediaType(PickVisualMedia.VideoOnly)
        .setMediaCapabilitiesForTranscoding(mediaCapabilities)
        .build())
}
```

照片选择器进行的转码取决于应用的媒体功能和所选视频。如果执行了转码，则返回转码后的视频的 URI。

### 有关 HDR 转码的重要注意事项

-   **性能和存储空间**：转码需要处理时间，并且会创建新文件，从而占用存储空间。
-   **视频时长限制**：为了平衡用户体验和存储空间限制，视频时长限制为 1 分钟。
-   **缓存文件管理**：在空闲维护期间，系统会定期清除缓存的转码文件，以防止过度使用存储空间用量。
-   **设备可用性**：**Android 13（API 级别 33）及更高版本**支持照片选择器转码。
-   **AndroidX activity 集成**：请确保您使用的是 AndroidX Activity 库的 1.11.0-alpha01 版或更高版本的 alpha/beta/RC/稳定版，因为这些版本包含必需的 `setMediaCapabilitiesForTranscoding` API。
