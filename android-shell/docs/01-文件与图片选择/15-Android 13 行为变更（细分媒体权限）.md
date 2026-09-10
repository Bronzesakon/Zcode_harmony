# Android 13 行为变更（细分媒体权限）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/about/versions/13/behavior-changes-13?hl=zh-cn
> 页面标题：行为变更：以 Android 13 或更高版本为目标平台的应用

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
与早期版本一样，Android 13 包含一些行为变更，这些变更可能会影响您的应用。以下行为变更仅影响以 Android 13 或更高版本为目标平台的应用。如果您的应用以 Android 13 或更高版本为目标平台，您应该修改自己的应用以适当地支持这些行为（如果适用）。

此外，请务必查看[对 Android 13 上运行的所有应用都有影响的行为变更](https://developer.android.com/about/versions/13/behavior-changes-all?hl=zh-cn)列表。

## 隐私权

### 通知权限会影响前台服务的显示

如果用户拒绝授予 [通知权限](https://developer.android.com/guide/topics/ui/notifiers/notification-permission?hl=zh-cn)， 就不会在 [抽屉式通知栏](https://developer.android.com/guide/topics/ui/notifiers/notifications?hl=zh-cn#appearances)中看到与前台服务相关的通知。 不过，无论是否授予通知权限，用户都会在 [任务管理器](https://developer.android.com/about/versions/13/changes/fgs-manager?hl=zh-cn)中看到与前台服务相关的通知 。

### 针对附近 Wi-Fi 设备的新运行时权限

在以前的 Android 版本中，用户需要向您的应用授予 [`ACCESS_FINE_LOCATION`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#ACCESS_FINE_LOCATION) 权限，应用才能完成一些常见的 Wi-Fi 用例。

由于用户很难将位置信息权限与 Wi-Fi 功能相关联，因此 Android 13（API 级别 33）在 [`NEARBY_DEVICES`](https://developer.android.com/reference/android/Manifest.permission_group?hl=zh-cn#NEARBY_DEVICES) 权限组中引入了运行时权限，适用于管理设备与附近 Wi-Fi 接入点连接情况的应用。此权限 ([`NEARBY_WIFI_DEVICES`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#NEARBY_WIFI_DEVICES)) 可满足以下 Wi-Fi 用例：

-   查找或连接到附近的设备，如打印机或媒体投射设备。通过该工作流，您的应用可以完成以下类型的任务：
    -   通过带外方式（例如通过 BLE）接收 AP 信息。
    -   使用仅限本地使用的热点，通过 Wi-Fi 感知和连接功能发现并连接到设备。
    -   通过 Wi-Fi 直连发现和连接到设备。
-   发起与已知 SSID（例如汽车或智能家居设备）的连接。
-   开启仅限本地使用的热点。
-   连接到附近的 Wi-Fi 感知设备。

只要您的应用不会通过 Wi-Fi API 推导物理位置，那么当您以 Android 13 或更高版本为目标平台并使用 Wi-Fi API 时，就可以请求 `NEARBY_WIFI_DEVICES` 而不是 `ACCESS_FINE_LOCATION`。当您声明 `NEARBY_WIFI_DEVICES` 权限时，请强烈断言您的应用绝不会通过 Wi-Fi API 推导物理位置信息。为此，请将 `android:usesPermissionFlags` 属性设置为 `neverForLocation`。此过程类似于您在 Android 12（API 级别 31）及更高版本中[声明绝不会将蓝牙设备信息用于获取位置信息](https://developer.android.com/guide/topics/connectivity/bluetooth/permissions?hl=zh-cn#assert-never-for-location)的过程。

**注意**：仅当您调用 Wi-Fi API 时，此更改才会影响您的应用。请查看[受影响的 API 列表](https://developer.android.com/guide/topics/connectivity/wifi-permissions?hl=zh-cn#check-for-apis-that-require-permission)。

详细了解如何[请求获取访问附近 Wi-Fi 设备的权限](https://developer.android.com/guide/topics/connectivity/wifi-permissions?hl=zh-cn)。

### 细化的媒体权限

![该对话框的两个按钮，从上至下分别为“Allow”和“Don&#39;t allow”](https://developer.android.com/static/images/about/versions/13/storage-permission-split.svg?hl=zh-cn)

**图 1.** 您在请求 `READ_MEDIA_AUDIO` 权限时向用户显示的系统权限对话框。

如果您的应用以 Android 13 或更高版本为目标平台，并且需要[访问其他应用已经创建的媒体文件](https://developer.android.com/training/data-storage/shared/media?hl=zh-cn#storage-permission)，您必须请求以下一项或多项细化的媒体权限，而不是[`READ_EXTERNAL_STORAGE`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_EXTERNAL_STORAGE) 权限：

| 媒体类型 | 请求权限 |
| --- | --- |
| 图片和照片 | [`READ_MEDIA_IMAGES`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_IMAGES) |
| 视频 | [`READ_MEDIA_VIDEO`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_VIDEO) |
| 音频文件 | [`READ_MEDIA_AUDIO`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_MEDIA_AUDIO) |

在访问其他应用的媒体文件之前，请验证用户是否已向您的应用授予适当的细化媒体权限。

图 1 显示了一个请求 `READ_MEDIA_AUDIO` 权限的应用。

如果您同时请求 `READ_MEDIA_IMAGES` 权限和 `READ_MEDIA_VIDEO` 权限，系统只会显示一个系统权限对话框。

如果您的应用之前已获授 [`READ_EXTERNAL_STORAGE`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#READ_EXTERNAL_STORAGE) 权限，那么在升级时，系统会自动授予任何请求的 `READ_MEDIA_*` 权限。您可以使用以下 ADB 命令查看升级后的权限：

adb shell cmd appops get --uid PACKAGE\_NAME

**\*\*注意\*\*： 如果您的应用只需要访问图片、照片和视频，请考虑 使用[照片选择器](https://developer.android.com/about/versions/13/features/photopicker?hl=zh-cn)，而不是 声明 `READ_MEDIA_IMAGES` 和 `READ_MEDIA_VIDEO` 权限。**

### 在后台使用身体传感器需要新的权限

Android 13 中引入了“在使用时”访问身体传感器（例如心率、体温和血氧饱和度）的概念。此访问模式与 [Android 10（API 级别 29）系统为位置信息](https://developer.android.com/about/versions/10/privacy/changes?hl=zh-cn#app-access-device-location)引入的模式非常相似。

如果您的应用以 Android 13 为目标平台，并且在后台运行时需要访问身体传感器信息，那么除了现有的 [`BODY_SENSORS`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#BODY_SENSORS) 权限外，您还必须声明新的 [`BODY_SENSORS_BACKGROUND`](https://developer.android.com/reference/android/Manifest.permission?hl=zh-cn#BODY_SENSORS_BACKGROUND) 权限。

**注意**：这是受到“硬性限制”的权限，除非设备的安装程序针对您的应用将该权限列入了许可名单，否则您的应用将无法获得此权限。如需了解详情，请参阅有关[受限权限](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/permission/Permissions.md#restricted-permissions)的指南。

## 性能和电池

### 电池资源利用率

如果用户因后台电池用量过高而将您的以 Android 13 为目标平台的应用置于[“受限”状态](https://developer.android.com/topic/performance/background-optimization?hl=zh-cn#bg-restrict)，除非应用因其他原因启动，否则系统不会传送 `BOOT_COMPLETED` 广播或 `LOCKED_BOOT_COMPLETED` 广播。

## 用户体验

### 派生自 `PlaybackState` 的媒体控件

对于以 Android 13（API 级别 33）及更高版本为目标平台的应用，系统会从 [`PlaybackState`](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn) 操作派生媒体控件。这样，系统便可以显示一组功能更丰富的控件，这些控件在技术上是可以在手机和平板电脑设备之间保持一致的，同时还与媒体控件在 Android Auto 和 Android TV 等其他 Android 平台上的呈现方式保持一致。

图 2 分别展示了其在手机和平板电脑设备上的显示效果。

![媒体控件在手机和平板电脑设备上的显示方式，用一首示例歌曲演示按钮可能的显示效果](https://developer.android.com/static/images/about/versions/13/media-controls-phone-tablet.png?hl=zh-cn)

**图 2** ：手机和平板电脑设备上的媒体控件

在 Android 13 之前，系统会按照[添加](https://developer.android.com/reference/android/app/Notification.Builder?hl=zh-cn#addAction\(android.app.Notification.Action\))顺序显示 `MediaStyle` 通知中的最多五项操作。在紧凑模式下（例如，在收起的快捷设置中），系统会显示最多三项操作（通过 [`setShowActionsInCompactView()`](https://developer.android.com/reference/androidx/media/app/NotificationCompat.MediaStyle?hl=zh-cn#setShowActionsInCompactView\(int...\)) 指定）。

从 Android 13 开始，系统会根据 `PlaybackState` 显示最多五个操作按钮，如下表所述。在紧凑模式下，将只显示前三个操作槽位。对于未以 Android 13 为目标平台的应用或不包含 `PlaybackState` 的应用，系统将根据添加到 `MediaStyle` 通知中的 `Action` 列表显示控件，如前一段所述。

| 槽位 | 操作 | 条件 |
| --- | --- | --- |
| 1 | 播放 | `PlaybackState` 的当前[状态](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getState\(\))是以下状态之一：
-   `STATE_NONE`
-   `STATE_STOPPED`
-   `STATE_PAUSED`
-   `STATE_ERROR`

 |
| 加载旋转图标 | `PlaybackState` 的当前[状态](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getState\(\))是以下状态之一：

-   `STATE_CONNECTING`
-   `STATE_BUFFERING`

 |
| 暂停 | `PlaybackState` 的当前[状态](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getState\(\))不是以上任何状态。 |
| 2 | 上一首 | `PlaybackState` [操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getActions\(\))包含 `ACTION_SKIP_TO_PREVIOUS`。 |
| 自定义 | `PlaybackState` [操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getActions\(\))不包含 `ACTION_SKIP_TO_PREVIOUS`，并且 `PlaybackState` [自定义操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getCustomActions\(\))包含尚未执行的自定义操作。 |
| 空 | `PlaybackState` [extra](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getExtras\(\)) 包含键 [`SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV`](https://developer.android.com/reference/androidx/media/utils/MediaConstants?hl=zh-cn#SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV) 的 `true` 布尔值。 |
| 3 | 下一首 | `PlaybackState` [操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getActions\(\))包括 `ACTION_SKIP_TO_NEXT`。 |
| 自定义 | `PlaybackState` [操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getActions\(\))不包含 `ACTION_SKIP_TO_NEXT`，并且 `PlaybackState` [自定义操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getCustomActions\(\))包含尚未执行的自定义操作。 |
| 空 | `PlaybackState` [extra](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getExtras\(\)) 包含键 [`SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT`](https://developer.android.com/reference/androidx/media/utils/MediaConstants?hl=zh-cn#SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT) 的 `true` 布尔值。 |
| 4 | 自定义 | `PlaybackState` [自定义操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getCustomActions\(\))包含尚未执行的自定义操作。 |
| 5 | 自定义 | `PlaybackState` [自定义操作](https://developer.android.com/reference/android/media/session/PlaybackState?hl=zh-cn#getCustomActions\(\))包含尚未执行的自定义操作。 |

自定义操作会按照添加到 `PlaybackState` 中的顺序执行。

### 应用颜色主题会自动应用于 WebView 内容

对于以 Android 13（API 级别 33）或更高版本为目标平台的应用，[`setForceDark()`](https://developer.android.com/reference/android/webkit/WebSettings?hl=zh-cn#setForceDark\(int\)) 方法已废弃，因此调用该方法会导致空操作。

相反，WebView 现在始终会根据应用的主题属性 [`isLightTheme`](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#Theme_isLightTheme) 来设置媒体查询 `prefers-color-scheme`。换句话说，如果 `isLightTheme` 为 `true` 或未指定，则 `prefers-color-scheme` 为 `light`；否则为 `dark`。此行为意味着，系统会自动应用 Web 内容的浅色或深色样式（如果相应内容支持应用主题）。

对于大多数应用，新行为应自动应用适当的应用样式，不过，您应测试应用以检查是否存在可能已手动控制深色模式设置的情况。

如果您仍需要自定义应用的颜色主题行为，请改用 [`setAlgorithmicDarkeningAllowed()`](https://developer.android.com/reference/android/webkit/WebSettings?hl=zh-cn#setAlgorithmicDarkeningAllowed\(boolean\)) 方法。为了向后兼容以前的 Android 版本，我们建议使用 AndroidX 中的等效 [`setAlgorithmicDarkeningAllowed()`](https://developer.android.com/reference/androidx/webkit/WebSettingsCompat?hl=zh-cn#setAlgorithmicDarkeningAllowed\(android.webkit.WebSettings,%20boolean\)) 方法。

如需详细了解根据应用的 [`targetSdkVersion`](https://developer.android.com/guide/topics/manifest/uses-sdk-element?hl=zh-cn#target)和主题 设置，应用可能会出现哪些行为，请参阅该方法的文档。

## 连接

### BluetoothAdapter#enable() 和 BluetoothAdapter#disable() 已废弃

对于以 Android 13（API 级别 33）或更高版本为目标平台的应用， [`BluetoothAdapter#enable()`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter?hl=zh-cn#enable\(\)) 和 [`BluetoothAdapter#disable()`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter?hl=zh-cn#disable\(\)) 方法已废弃，并且始终 返回 `false`。

以下类型的应用不受这些变更的影响：

-   设备所有者应用
-   个人资料所有者应用
-   系统应用

## Google Play 服务

### 广告 ID 需要权限

使用 Google Play 服务[广告 ID](https://support.google.com/googleplay/android-developer/answer/6048248?hl=zh-cn) 且以 Android 13（API 级别 33）及更高版本为目标平台的应用必须在其清单文件中声明常规 [`AD_ID`](https://developers.google.com/android/reference/com/google/android/gms/ads/identifier/AdvertisingIdClient.Info?hl=zh-cn#public-string-getid)权限，如下所示：

```
<manifest ...>
    <!-- Required only if your app targets Android 13 or higher. -->
    <uses-permission android:name="com.google.android.gms.permission.AD_ID"/>

    <application ...>
        ...
    </application>
</manifest>
```

如果您的应用以 Android 13 或更高版本为目标平台且未声明此权限，系统会自动移除广告 ID 并将其替换为一串零。

如果您的应用使用的 SDK 在库的清单中声明了 `AD_ID` 权限，则默认情况下，该权限会与应用的清单文件合并。在这种情况下，您无需在应用的清单文件中声明该权限。

如需了解详情，请参阅 Play 管理中心帮助中的[广告 ID](https://support.google.com/googleplay/android-developer/answer/6048248?hl=zh-cn)。

## 更新后的非 SDK 限制

Android 13 包含更新后的受限制非 SDK 接口列表（基于与 Android 开发者之间的协作以及最新的内部测试）。在限制使用非 SDK 接口之前，我们会尽可能确保有可用的公开替代方案。

如果您的应用并非以 Android 13 为目标平台，其中一些变更可能不会立即对您产生影响。然而，虽然您目前仍可以使用一些非 SDK 接口（[具体取决于应用的目标 API 级别](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces?hl=zh-cn#list-names)），但只要您使用任何非 SDK 方法或字段，终归存在导致应用出问题的显著风险。

如果您不确定自己的应用是否使用了非 SDK 接口，则可以[测试您的应用](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces?hl=zh-cn#test-for-non-sdk)来进行确认。如果您的应用依赖于非 SDK 接口，您应该开始计划迁移到 SDK 替代方案。然而，我们知道某些应用具有使用非 SDK 接口的有效用例。如果您无法为应用中的某项功能找到使用非 SDK 接口的替代方案，应[请求新的公共 API](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces?hl=zh-cn#feature-request)。

如需详细了解此 Android 版本中的变更，请参阅 [Android 13 中有关限制非 SDK 接口的更新](https://developer.android.com/about/versions/13/changes/non-sdk-13?hl=zh-cn)。 如需全面了解有关非 SDK 接口的详细信息，请参阅[对非 SDK 接口的限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces?hl=zh-cn)。
