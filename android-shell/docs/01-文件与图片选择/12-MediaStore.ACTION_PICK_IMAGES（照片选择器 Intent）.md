# MediaStore.ACTION_PICK_IMAGES（照片选择器 Intent）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/reference/android/provider/MediaStore?hl=zh-cn
> 页面标题：MediaStore.ACTION_PICK_IMAGES（照片选择器 Intent）
> 快照时间：2026-09-10

---
Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

Summary: [Nested Classes](#nestedclasses) | [Constants](#constants) | [Fields](#lfields) | [Ctors](#pubctors) | [Methods](#pubmethods) | [Inherited Methods](#inhmethods)

# MediaStore

* * *

[Kotlin](/reference/kotlin/android/provider/MediaStore "View this page in Kotlin") |Java

`public final class MediaStore`  
`extends [Object](/reference/java/lang/Object)`

<table class="jd-inheritance-table"><tbody><tr><td colspan="2" class="jd-inheritance-class-cell"><a href="/reference/java/lang/Object">java.lang.Object</a></td></tr><tr><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="1" class="jd-inheritance-class-cell">android.provider.MediaStore</td></tr></tbody></table>

  

* * *

The contract between the media provider and applications. Contains definitions for the supported URIs and columns.

The media provider provides an indexed collection of common media types, such as `[Audio](/reference/android/provider/MediaStore.Audio)`, `[Video](/reference/android/provider/MediaStore.Video)`, and `[Images](/reference/android/provider/MediaStore.Images)`, from any attached storage devices. Each collection is organized based on the primary MIME type of the underlying content; for example, `image/*` content is indexed under `[Images](/reference/android/provider/MediaStore.Images)`. The `[Files](/reference/android/provider/MediaStore.Files)` collection provides a broad view across all collections, and does not filter by MIME type.

## Summary

| 
### Nested classes

 |
| --- |
| `class` | `[MediaStore.Audio](/reference/android/provider/MediaStore.Audio)`

Collection of all media with MIME type of `audio/*`. 

 |
| `class` | `[MediaStore.DeletedFiles](/reference/android/provider/MediaStore.DeletedFiles)`

Media provider table containing records of deleted media items. 

 |
| `interface` | `[MediaStore.DownloadColumns](/reference/android/provider/MediaStore.DownloadColumns)`

Download metadata columns. 

 |
| `class` | `[MediaStore.Downloads](/reference/android/provider/MediaStore.Downloads)`

Collection of downloaded items. 

 |
| `class` | `[MediaStore.Files](/reference/android/provider/MediaStore.Files)`

Media provider table containing an index of all files in the media storage, including non-media files. 

 |
| `class` | `[MediaStore.Images](/reference/android/provider/MediaStore.Images)`

Collection of all media with MIME type of `image/*`. 

 |
| `interface` | `[MediaStore.MediaColumns](/reference/android/provider/MediaStore.MediaColumns)`

Common media metadata columns. 

 |
| `class` | `[MediaStore.PickerMediaColumns](/reference/android/provider/MediaStore.PickerMediaColumns)`

Photo picker metadata columns. 

 |
| `class` | `[MediaStore.Video](/reference/android/provider/MediaStore.Video)`

Collection of all media with MIME type of `video/*`. 

 |

| 
### Constants

 |
| --- |
| `[String](/reference/java/lang/String)` | `[ACCESS_MEDIA_OWNER_PACKAGE_NAME_PERMISSION](/reference/android/provider/MediaStore#ACCESS_MEDIA_OWNER_PACKAGE_NAME_PERMISSION)`

Permission that grants access to `[MediaColumns.OWNER_PACKAGE_NAME](/reference/android/provider/MediaStore.MediaColumns#OWNER_PACKAGE_NAME)` of every accessible media file.

 |
| `[String](/reference/java/lang/String)` | `[ACCESS_OEM_METADATA_PERMISSION](/reference/android/provider/MediaStore#ACCESS_OEM_METADATA_PERMISSION)`

Permission that grants access to `[MediaColumns.OEM_METADATA](/reference/android/provider/MediaStore.MediaColumns#OEM_METADATA)` of every accessible media file.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_IMAGE_CAPTURE](/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE)`

Standard Intent action that can be sent to have the camera application capture an image and return it.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_IMAGE_CAPTURE_SECURE](/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE_SECURE)`

Intent action that can be sent to have the camera application capture an image and return it when the device is secured (e.g. with a pin, password, pattern, or face unlock).

 |
| `[String](/reference/java/lang/String)` | `[ACTION_MOTION_PHOTO_CAPTURE](/reference/android/provider/MediaStore#ACTION_MOTION_PHOTO_CAPTURE)`

Standard Intent action that can be sent to have the camera application capture a [motion photo](/media/platform/motion-photo-format) and return it.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_MOTION_PHOTO_CAPTURE_SECURE](/reference/android/provider/MediaStore#ACTION_MOTION_PHOTO_CAPTURE_SECURE)`

Intent action that can be sent to have the camera application capture a [motion photo](/media/platform/motion-photo-format) and return it when the device is secured (e.g. with a pin, password, pattern, or face unlock).

 |
| `[String](/reference/java/lang/String)` | `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

Activity Action: Allow the user to select images or videos provided by system and return it.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_PICK_IMAGES_SETTINGS](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES_SETTINGS)`

Activity Action: Launch settings controlling images or videos selection with `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)`

Standard action that can be sent to review the given media file.

 |
| `[String](/reference/java/lang/String)` | `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)`

Standard action that can be sent to review the given media file when the device is secured (e.g. with a pin, password, pattern, or face unlock).

 |
| `[String](/reference/java/lang/String)` | `[ACTION_VIDEO_CAPTURE](/reference/android/provider/MediaStore#ACTION_VIDEO_CAPTURE)`

Standard Intent action that can be sent to have the camera application capture a video and return it.

 |
| `[String](/reference/java/lang/String)` | `[AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`

The authority for the media provider

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT](/reference/android/provider/MediaStore#EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT)`

/\*\* Specify that the caller wants to receive the original media format without transcoding.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_BRIGHTNESS](/reference/android/provider/MediaStore#EXTRA_BRIGHTNESS)`

When defined, the launched application is requested to set the given brightness value via `[WindowManager.LayoutParams.screenBrightness](/reference/android/view/WindowManager.LayoutParams#screenBrightness)` to help ensure a smooth transition when launching `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)` intents.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_DURATION_LIMIT](/reference/android/provider/MediaStore#EXTRA_DURATION_LIMIT)`

Specify the maximum allowed recording duration in seconds.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_FINISH_ON_COMPLETION](/reference/android/provider/MediaStore#EXTRA_FINISH_ON_COMPLETION)`

The name of the Intent-extra used to control the onCompletion behavior of a MovieView.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_FULL_SCREEN](/reference/android/provider/MediaStore#EXTRA_FULL_SCREEN)`

The name of an Intent-extra used to control the UI of a ViewImage.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_ALBUM](/reference/android/provider/MediaStore#EXTRA_MEDIA_ALBUM)`

The name of the Intent-extra used to define the album

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_ARTIST](/reference/android/provider/MediaStore#EXTRA_MEDIA_ARTIST)`

The name of the Intent-extra used to define the artist

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_CAPABILITIES](/reference/android/provider/MediaStore#EXTRA_MEDIA_CAPABILITIES)`

Specify the `[ApplicationMediaCapabilities](/reference/android/media/ApplicationMediaCapabilities)` that should be used while opening a media or picking media files.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_CAPABILITIES_UID](/reference/android/provider/MediaStore#EXTRA_MEDIA_CAPABILITIES_UID)`

Specify the UID of the app that should be used to determine supported media capabilities while opening a media.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_FOCUS](/reference/android/provider/MediaStore#EXTRA_MEDIA_FOCUS)`

The name of the Intent-extra used to define the search focus.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_GENRE](/reference/android/provider/MediaStore#EXTRA_MEDIA_GENRE)`

The name of the Intent-extra used to define the genre.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_PLAYLIST](/reference/android/provider/MediaStore#EXTRA_MEDIA_PLAYLIST)`

_This constant was deprecated in API level 31. Android playlists are now deprecated. We will keep the current functionality for compatibility resons, but we will no longer take feature request. We do not advise adding new usages of Android Playlists. M3U files can be used as an alternative._

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_RADIO_CHANNEL](/reference/android/provider/MediaStore#EXTRA_MEDIA_RADIO_CHANNEL)`

The name of the Intent-extra used to define the radio channel.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_MEDIA_TITLE](/reference/android/provider/MediaStore#EXTRA_MEDIA_TITLE)`

The name of the Intent-extra used to define the song title

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`

The name of the Intent-extra used to indicate a content resolver Uri to be used to store the requested image or video.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICKER_PRE_SELECTION_URIS](/reference/android/provider/MediaStore#EXTRA_PICKER_PRE_SELECTION_URIS)`

The name of an optional intent-extra used to specify URIs for pre-selection in photo picker opened with `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` in multi-select mode.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_ACCENT_COLOR](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_ACCENT_COLOR)`

The name of an optional intent-extra used to allow apps to specify the picker accent color.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

The name of an optional intent-extra used to allow apps to highlight media results of a photopicker album in the photopicker UI whenever feasible based on the given input album in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)`.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)`

The name of an optional intent-extra used to allow apps to highlight search results in the photopicker UI whenever feasible based on the given input text query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_IN_ORDER](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_IN_ORDER)`

The name of an optional intent-extra used to allow ordered selection of items.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_LAUNCH_TAB](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_LAUNCH_TAB)`

The name of an optional intent-extra used to allow apps to specify the tab the picker should open with.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)`

The name of an optional intent-extra used to allow multiple selection of items and constrain maximum number of items that can be returned by `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`, action may still return nothing (0 items) if the user chooses to cancel.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_SELECTION_PARAMS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_SELECTION_PARAMS)`

The name of an optional intent-extra used to pass `[PhotoPickerSelectionParams](/reference/android/widget/photopicker/PhotoPickerSelectionParams)` to the photo picker.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS)`

The name of an optional intent-extra used to set the ui customization options in the PhotoPicker.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_REQUEST_LOCATION_METADATA_ACCESS](/reference/android/provider/MediaStore#EXTRA_REQUEST_LOCATION_METADATA_ACCESS)`

The name of an optional intent-extra used to allow apps to request access to the location metadata of the media items selected by the user and returned by `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_SCREEN_ORIENTATION](/reference/android/provider/MediaStore#EXTRA_SCREEN_ORIENTATION)`

The name of the Intent-extra used to control the orientation of a ViewImage or a MovieView.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_SHOW_ACTION_ICONS](/reference/android/provider/MediaStore#EXTRA_SHOW_ACTION_ICONS)`

The name of an Intent-extra used to control the UI of a ViewImage.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_SIZE_LIMIT](/reference/android/provider/MediaStore#EXTRA_SIZE_LIMIT)`

Specify the maximum allowed size.

 |
| `[String](/reference/java/lang/String)` | `[EXTRA_VIDEO_QUALITY](/reference/android/provider/MediaStore#EXTRA_VIDEO_QUALITY)`

The name of the Intent-extra used to control the quality of a recorded video.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH](/reference/android/provider/MediaStore#INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)`

An intent to perform a search for music media and automatically play content from the result when possible.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_MEDIA_SEARCH](/reference/android/provider/MediaStore#INTENT_ACTION_MEDIA_SEARCH)`

Activity Action: Perform a search for media.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_MUSIC_PLAYER](/reference/android/provider/MediaStore#INTENT_ACTION_MUSIC_PLAYER)`

_This constant was deprecated in API level 15. Use `[Intent.CATEGORY_APP_MUSIC](/reference/android/content/Intent#CATEGORY_APP_MUSIC)` instead._

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_STILL_IMAGE_CAMERA](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA)`

The name of the Intent action used to launch a camera in still image mode.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)`

The name of the Intent action used to launch a camera in still image mode for use when the device is secured (e.g. with a pin, password, pattern, or face unlock).

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_TEXT_OPEN_FROM_SEARCH](/reference/android/provider/MediaStore#INTENT_ACTION_TEXT_OPEN_FROM_SEARCH)`

An intent to perform a search for readable media and automatically play content from the result when possible.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_VIDEO_CAMERA](/reference/android/provider/MediaStore#INTENT_ACTION_VIDEO_CAMERA)`

The name of the Intent action used to launch a camera in video mode.

 |
| `[String](/reference/java/lang/String)` | `[INTENT_ACTION_VIDEO_PLAY_FROM_SEARCH](/reference/android/provider/MediaStore#INTENT_ACTION_VIDEO_PLAY_FROM_SEARCH)`

An intent to perform a search for video media and automatically play content from the result when possible.

 |
| `[String](/reference/java/lang/String)` | `[KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)`

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` specifying the photopicker album for which the app can request to show highlighted media results in the photopicker.

 |
| `[String](/reference/java/lang/String)` | `[KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)`

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` specifying the input query for which the app can request to show highlighted media results in the photopicker.

 |
| `[String](/reference/java/lang/String)` | `[KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)`

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` and `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` to specify the highlight type i.e.

 |
| `int` | `[MATCH_DEFAULT](/reference/android/provider/MediaStore#MATCH_DEFAULT)`

Value indicating that the default matching behavior should be used, as defined by the key documentation.

 |
| `int` | `[MATCH_EXCLUDE](/reference/android/provider/MediaStore#MATCH_EXCLUDE)`

Value indicating that operations should exclude items matching the criteria defined by this key.

 |
| `int` | `[MATCH_INCLUDE](/reference/android/provider/MediaStore#MATCH_INCLUDE)`

Value indicating that operations should include items matching the criteria defined by this key.

 |
| `int` | `[MATCH_ONLY](/reference/android/provider/MediaStore#MATCH_ONLY)`

Value indicating that operations should only operate on items explicitly matching the criteria defined by this key.

 |
| `[String](/reference/java/lang/String)` | `[MEDIA_IGNORE_FILENAME](/reference/android/provider/MediaStore#MEDIA_IGNORE_FILENAME)`

Name of the file signaling the media scanner to ignore media in the containing directory and its subdirectories.

 |
| `[String](/reference/java/lang/String)` | `[MEDIA_SCANNER_VOLUME](/reference/android/provider/MediaStore#MEDIA_SCANNER_VOLUME)`

Name of current volume being scanned by the media scanner.

 |
| `[String](/reference/java/lang/String)` | `[META_DATA_REVIEW_GALLERY_PREWARM_SERVICE](/reference/android/provider/MediaStore#META_DATA_REVIEW_GALLERY_PREWARM_SERVICE)`

Name under which an activity handling `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)` publishes the service name for its prewarm service.

 |
| `[String](/reference/java/lang/String)` | `[META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE](/reference/android/provider/MediaStore#META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE)`

Name under which an activity handling `[INTENT_ACTION_STILL_IMAGE_CAMERA](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA)` or `[INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)` publishes the service name for its prewarm service.

 |
| `[String](/reference/java/lang/String)` | `[PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA)`

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Camera album.

 |
| `[String](/reference/java/lang/String)` | `[PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS)`

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Downloads album.

 |
| `[String](/reference/java/lang/String)` | `[PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES)`

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Favorites album.

 |
| `[String](/reference/java/lang/String)` | `[PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS)`

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from from an album, in this case, the Screenshots album.

 |
| `[String](/reference/java/lang/String)` | `[PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS)`

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Videos album.

 |
| `int` | `[PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)`

One of the permitted values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` to highlight media results as a highlighted media section in the photopicker based on the given input query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` or the given input photopicker album in MediaStore#KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID}.

 |
| `int` | `[PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)`

One of the permitted values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` to show a highlighted media results grid based on the given input query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` or the given input photopicker album in MediaStore#KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID}.

 |
| `int` | `[PICK_IMAGES_TAB_ALBUMS](/reference/android/provider/MediaStore#PICK_IMAGES_TAB_ALBUMS)`

One of the permitted values for `[MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_LAUNCH_TAB)` to open the picker with albums tab.

 |
| `int` | `[PICK_IMAGES_TAB_IMAGES](/reference/android/provider/MediaStore#PICK_IMAGES_TAB_IMAGES)`

One of the permitted values for `[MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_LAUNCH_TAB)` to open the picker with photos tab.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES](/reference/android/provider/MediaStore#QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES)`

Flag that requests `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))` to include content from recently unmounted volumes.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_LATEST_SELECTION_ONLY](/reference/android/provider/MediaStore#QUERY_ARG_LATEST_SELECTION_ONLY)`

Flag that indicates if only the latest selection in the photoPicker for the calling app should be returned.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_MATCH_FAVORITE](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_FAVORITE)`

Specify how `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_MATCH_PENDING](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING)`

Specify how `[MediaColumns.IS_PENDING](/reference/android/provider/MediaStore.MediaColumns#IS_PENDING)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_MATCH_TRASHED](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_TRASHED)`

Specify how `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_MEDIA_STANDARD_SORT_ORDER](/reference/android/provider/MediaStore#QUERY_ARG_MEDIA_STANDARD_SORT_ORDER)`

Flag that requests `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))` to sort the result in descending order based on `[MediaColumns.INFERRED_DATE](/reference/android/provider/MediaStore.MediaColumns#INFERRED_DATE)`.

 |
| `[String](/reference/java/lang/String)` | `[QUERY_ARG_RELATED_URI](/reference/android/provider/MediaStore#QUERY_ARG_RELATED_URI)`

Specify a `[Uri](/reference/android/net/Uri)` that is "related" to the current operation being performed.

 |
| `[String](/reference/java/lang/String)` | `[UNKNOWN_STRING](/reference/android/provider/MediaStore#UNKNOWN_STRING)`

The string that is used when a media attribute is not known.

 |
| `[String](/reference/java/lang/String)` | `[VOLUME_EXTERNAL](/reference/android/provider/MediaStore#VOLUME_EXTERNAL)`

Synthetic volume name that provides a view of all content across the "external" storage of the device.

 |
| `[String](/reference/java/lang/String)` | `[VOLUME_EXTERNAL_PRIMARY](/reference/android/provider/MediaStore#VOLUME_EXTERNAL_PRIMARY)`

Specific volume name that represents the primary external storage device at `[Environment.getExternalStorageDirectory()](/reference/android/os/Environment#getExternalStorageDirectory\(\))`.

 |
| `[String](/reference/java/lang/String)` | `[VOLUME_INTERNAL](/reference/android/provider/MediaStore#VOLUME_INTERNAL)`

Synthetic volume name that provides a view of all content across the "internal" storage of the device.

 |

| 
### Fields

 |
| --- |
| `public static final [Uri](/reference/android/net/Uri)` | `[AUTHORITY_URI](/reference/android/provider/MediaStore#AUTHORITY_URI)`

A content:// style uri to the authority for the media provider

 |

| 
### Public constructors

 |
| --- |
| `[MediaStore](/reference/android/provider/MediaStore#MediaStore\(\))()` |

| 
### Public methods

 |
| --- |
| `static boolean` | `[canManageMedia](/reference/android/provider/MediaStore#canManageMedia\(android.content.Context\))([Context](/reference/android/content/Context) context)`

Returns whether the calling app is granted `[Manifest.permission.MANAGE_MEDIA](/reference/android/Manifest.permission#MANAGE_MEDIA)` or not.

 |
| `static [PendingIntent](/reference/android/app/PendingIntent)` | `[createDeleteRequest](/reference/android/provider/MediaStore#createDeleteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)> uris)`

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to permanently delete the requested media items.

 |
| `static [PendingIntent](/reference/android/app/PendingIntent)` | `[createFavoriteRequest](/reference/android/provider/MediaStore#createFavoriteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)> uris, boolean value)`

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to favorite the requested media items.

 |
| `static [PendingIntent](/reference/android/app/PendingIntent)` | `[createTrashRequest](/reference/android/provider/MediaStore#createTrashRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)> uris, boolean value)`

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to trash the requested media items.

 |
| `static [PendingIntent](/reference/android/app/PendingIntent)` | `[createWriteRequest](/reference/android/provider/MediaStore#createWriteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)> uris)`

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to grant your app write access for the requested media items.

 |
| `static [Uri](/reference/android/net/Uri)` | `[getDocumentUri](/reference/android/provider/MediaStore#getDocumentUri\(android.content.Context,%20android.net.Uri\))([Context](/reference/android/content/Context) context, [Uri](/reference/android/net/Uri) mediaUri)`

Return a `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri that is an equivalent to the given `[MediaStore](/reference/android/provider/MediaStore)` Uri.

 |
| `static [Set](/reference/java/util/Set)<[String](/reference/java/lang/String)>` | `[getExternalVolumeNames](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))([Context](/reference/android/content/Context) context)`

Return list of all specific volume names that make up `[VOLUME_EXTERNAL](/reference/android/provider/MediaStore#VOLUME_EXTERNAL)`.

 |
| `static long` | `[getGeneration](/reference/android/provider/MediaStore#getGeneration\(android.content.Context,%20java.lang.String\))([Context](/reference/android/content/Context) context, [String](/reference/java/lang/String) volumeName)`

Return the latest generation value for the given volume.

 |
| `static [Uri](/reference/android/net/Uri)` | `[getMediaScannerUri](/reference/android/provider/MediaStore#getMediaScannerUri\(\))()`

Uri for querying the state of the media scanner.

 |
| `static [Uri](/reference/android/net/Uri)` | `[getMediaUri](/reference/android/provider/MediaStore#getMediaUri\(android.content.Context,%20android.net.Uri\))([Context](/reference/android/content/Context) context, [Uri](/reference/android/net/Uri) documentUri)`

Return a `[MediaStore](/reference/android/provider/MediaStore)` Uri that is an equivalent to the given `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri.

 |
| `static [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` | `[getOriginalMediaFormatFileDescriptor](/reference/android/provider/MediaStore#getOriginalMediaFormatFileDescriptor\(android.content.Context,%20android.os.ParcelFileDescriptor\))([Context](/reference/android/content/Context) context, [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor) fileDescriptor)`

Returns `[ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` representing the original media file format for `fileDescriptor`.

 |
| `static [String](/reference/java/lang/String)` | `[getPackageForSearchMediaService](/reference/android/provider/MediaStore#getPackageForSearchMediaService\(android.content.ContentResolver\))([ContentResolver](/reference/android/content/ContentResolver) resolver)`

Gets the package name of the `[ERROR(SearchMediaService/android.provider.SearchMediaService SearchMediaService)](/)` that client apps use to connect.

 |
| `static int` | `[getPickImagesMaxLimit](/reference/android/provider/MediaStore#getPickImagesMaxLimit\(\))()`

The maximum limit for the number of items that can be selected using `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` when launched in multiple selection mode.

 |
| `static [Set](/reference/java/util/Set)<[String](/reference/java/lang/String)>` | `[getRecentExternalVolumeNames](/reference/android/provider/MediaStore#getRecentExternalVolumeNames\(android.content.Context\))([Context](/reference/android/content/Context) context)`

Return list of all recent volume names that have been part of `[VOLUME_EXTERNAL](/reference/android/provider/MediaStore#VOLUME_EXTERNAL)`.

 |
| `static [Uri](/reference/android/net/Uri)` | `[getRedactedUri](/reference/android/provider/MediaStore#getRedactedUri\(android.content.ContentResolver,%20android.net.Uri\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Uri](/reference/android/net/Uri) uri)`

Returns an EXIF redacted version of `uri` i.e.

 |
| `static [List](/reference/java/util/List)<[Uri](/reference/android/net/Uri)>` | `[getRedactedUri](/reference/android/provider/MediaStore#getRedactedUri\(android.content.ContentResolver,%20java.util.List\<android.net.Uri\>\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [List](/reference/java/util/List)<[Uri](/reference/android/net/Uri)> uris)`

Returns a list of EXIF redacted version of `uris` i.e.

 |
| `static boolean` | `[getRequireOriginal](/reference/android/provider/MediaStore#getRequireOriginal\(android.net.Uri\))([Uri](/reference/android/net/Uri) uri)`

Return if the caller requires the original file contents when calling `[ContentResolver.openFileDescriptor(Uri,String)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String\))`.

 |
| `static [String](/reference/java/lang/String)` | `[getVersion](/reference/android/provider/MediaStore#getVersion\(android.content.Context,%20java.lang.String\))([Context](/reference/android/content/Context) context, [String](/reference/java/lang/String) volumeName)`

Return an opaque version string describing the `[MediaStore](/reference/android/provider/MediaStore)` state.

 |
| `static [String](/reference/java/lang/String)` | `[getVersion](/reference/android/provider/MediaStore#getVersion\(android.content.Context\))([Context](/reference/android/content/Context) context)`

Return an opaque version string describing the `[MediaStore](/reference/android/provider/MediaStore)` state.

 |
| `static [String](/reference/java/lang/String)` | `[getVolumeName](/reference/android/provider/MediaStore#getVolumeName\(android.net.Uri\))([Uri](/reference/android/net/Uri) uri)`

Return the volume name that the given `[Uri](/reference/android/net/Uri)` references.

 |
| `static boolean` | `[isCurrentCloudMediaProviderAuthority](/reference/android/provider/MediaStore#isCurrentCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [String](/reference/java/lang/String) authority)`

Returns `true` if and only if the caller with `authority` is the currently enabled `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`.

 |
| `static boolean` | `[isCurrentSystemGallery](/reference/android/provider/MediaStore#isCurrentSystemGallery\(android.content.ContentResolver,%20int,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, int uid, [String](/reference/java/lang/String) packageName)`

Returns true if the given application is the current system gallery of the device.

 |
| `static boolean` | `[isSupportedCloudMediaProviderAuthority](/reference/android/provider/MediaStore#isSupportedCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [String](/reference/java/lang/String) authority)`

Returns `true` if and only if the caller with `authority` is a supported `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`.

 |
| `static void` | `[markIsFavoriteStatus](/reference/android/provider/MediaStore#markIsFavoriteStatus\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)> uris, boolean areFavorites)`

Sets the media isFavorite status if the calling app has wider read permission on media files for given type.

 |
| `static void` | `[notifyCloudMediaChangedEvent](/reference/android/provider/MediaStore#notifyCloudMediaChangedEvent\(android.content.ContentResolver,%20java.lang.String,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [String](/reference/java/lang/String) authority, [String](/reference/java/lang/String) currentMediaCollectionId)`

Notifies the OS about a cloud media event requiring a full or incremental media collection sync for the currently enabled cloud provider, `authority`.

 |
| `static [AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor)` | `[openAssetFileDescriptor](/reference/android/provider/MediaStore#openAssetFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Uri](/reference/android/net/Uri) uri, [String](/reference/java/lang/String) mode, [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)`

Works exactly the same as `[ContentResolver.openAssetFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

 |
| `static [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` | `[openFileDescriptor](/reference/android/provider/MediaStore#openFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Uri](/reference/android/net/Uri) uri, [String](/reference/java/lang/String) mode, [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)`

Works exactly the same as `[ContentResolver.openFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

 |
| `static [AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor)` | `[openTypedAssetFileDescriptor](/reference/android/provider/MediaStore#openTypedAssetFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Uri](/reference/android/net/Uri) uri, [String](/reference/java/lang/String) mimeType, [Bundle](/reference/android/os/Bundle) opts, [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)`

Works exactly the same as `[ContentResolver.openTypedAssetFileDescriptor(Uri,String,Bundle,CancellationSignal)](/reference/android/content/ContentResolver#openTypedAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

 |
| `static [Cursor](/reference/android/database/Cursor)` | `[queryDeletedFiles](/reference/android/provider/MediaStore#queryDeletedFiles\(android.content.ContentResolver,%20android.os.Bundle,%20android.os.CancellationSignal\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [Bundle](/reference/android/os/Bundle) queryArgs, [CancellationSignal](/reference/android/os/CancellationSignal) signal)`

Returns a cursor of deleted items matching the given query arguments.

 |
| `static [String](/reference/java/lang/String)` | `[restoreFileFromTrash](/reference/android/provider/MediaStore#restoreFileFromTrash\(android.content.ContentResolver,%20java.lang.String,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [String](/reference/java/lang/String) path, [String](/reference/java/lang/String) targetPath)`

Restores a file or directory from a trashed state.

 |
| `static [Uri](/reference/android/net/Uri)` | `[setIncludePending](/reference/android/provider/MediaStore#setIncludePending\(android.net.Uri\))([Uri](/reference/android/net/Uri) uri)`

_This method was deprecated in API level 30. consider migrating to `[QUERY_ARG_MATCH_PENDING](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING)` which is more expressive._

 |
| `static [Uri](/reference/android/net/Uri)` | `[setRequireOriginal](/reference/android/provider/MediaStore#setRequireOriginal\(android.net.Uri\))([Uri](/reference/android/net/Uri) uri)`

Update the given `[Uri](/reference/android/net/Uri)` to indicate that the caller requires the original file contents when calling `[ContentResolver.openFileDescriptor(Uri,String)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String\))`.

 |
| `static [String](/reference/java/lang/String)` | `[trashFile](/reference/android/provider/MediaStore#trashFile\(android.content.ContentResolver,%20java.lang.String\))([ContentResolver](/reference/android/content/ContentResolver) resolver, [String](/reference/java/lang/String) path)`

Moves a file or directory to a trashed state.

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

### ACCESS\_MEDIA\_OWNER\_PACKAGE\_NAME\_PERMISSION

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACCESS\_MEDIA\_OWNER\_PACKAGE\_NAME\_PERMISSION

Permission that grants access to `[MediaColumns.OWNER_PACKAGE_NAME](/reference/android/provider/MediaStore.MediaColumns#OWNER_PACKAGE_NAME)` of every accessible media file.

Constant Value: "com.android.providers.media.permission.ACCESS\_MEDIA\_OWNER\_PACKAGE\_NAME"

### ACCESS\_OEM\_METADATA\_PERMISSION

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [T Extensions 15](/sdkExtensions)

public static final [String](/reference/java/lang/String) ACCESS\_OEM\_METADATA\_PERMISSION

Permission that grants access to `[MediaColumns.OEM_METADATA](/reference/android/provider/MediaStore.MediaColumns#OEM_METADATA)` of every accessible media file.

Constant Value: "com.android.providers.media.permission.ACCESS\_OEM\_METADATA"

### ACTION\_IMAGE\_CAPTURE

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_IMAGE\_CAPTURE

Standard Intent action that can be sent to have the camera application capture an image and return it.

The caller may pass an extra EXTRA\_OUTPUT to control where this image will be written. If the EXTRA\_OUTPUT is not present, then a small sized image is returned as a Bitmap object in the extra field. This is useful for applications that only need a small image. If the EXTRA\_OUTPUT is present, then the full-sized image will be written to the Uri value of EXTRA\_OUTPUT. As of `[Build.VERSION_CODES.LOLLIPOP](/reference/android/os/Build.VERSION_CODES#LOLLIPOP)`, this uri can also be supplied through `[android.content.Intent.setClipData(ClipData)](/reference/android/content/Intent#setClipData\(android.content.ClipData\))`. If using this approach, you still must supply the uri through the EXTRA\_OUTPUT field for compatibility with old applications. If you don't set a ClipData, it will be copied there for you when calling `[Context.startActivity(Intent)](/reference/android/content/Context#startActivity\(android.content.Intent\))`.

Regardless of whether or not EXTRA\_OUTPUT is present, when an image is captured via this intent, `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)` won't be broadcasted.

Note: if you app targets `[M](/reference/android/os/Build.VERSION_CODES#M)` and above and declares as using the `[Manifest.permission.CAMERA](/reference/android/Manifest.permission#CAMERA)` permission which is not granted, then attempting to use this action will result in a `[SecurityException](/reference/java/lang/SecurityException)`.

**See also:**

-   `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`
-   `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)`

Constant Value: "android.media.action.IMAGE\_CAPTURE"

### ACTION\_IMAGE\_CAPTURE\_SECURE

Added in [API level 17](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_IMAGE\_CAPTURE\_SECURE

Intent action that can be sent to have the camera application capture an image and return it when the device is secured (e.g. with a pin, password, pattern, or face unlock). Applications responding to this intent must not expose any personal content like existing photos or videos on the device. The applications should be careful not to share any photo or video with other applications or Internet. The activity should use `[Activity.setShowWhenLocked](/reference/android/app/Activity#setShowWhenLocked\(boolean\))` to display on top of the lock screen while secured. There is no activity stack when this flag is used, so launching more than one activity is strongly discouraged.

The caller may pass an extra EXTRA\_OUTPUT to control where this image will be written. If the EXTRA\_OUTPUT is not present, then a small sized image is returned as a Bitmap object in the extra field. This is useful for applications that only need a small image. If the EXTRA\_OUTPUT is present, then the full-sized image will be written to the Uri value of EXTRA\_OUTPUT. As of `[Build.VERSION_CODES.LOLLIPOP](/reference/android/os/Build.VERSION_CODES#LOLLIPOP)`, this uri can also be supplied through `[android.content.Intent.setClipData(ClipData)](/reference/android/content/Intent#setClipData\(android.content.ClipData\))`. If using this approach, you still must supply the uri through the EXTRA\_OUTPUT field for compatibility with old applications. If you don't set a ClipData, it will be copied there for you when calling `[Context.startActivity(Intent)](/reference/android/content/Context#startActivity\(android.content.Intent\))`.

Regardless of whether or not EXTRA\_OUTPUT is present, when an image is captured via this intent, `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)` won't be broadcasted.

**See also:**

-   `[ACTION_IMAGE_CAPTURE](/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE)`
-   `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`
-   `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)`

Constant Value: "android.media.action.IMAGE\_CAPTURE\_SECURE"

### ACTION\_MOTION\_PHOTO\_CAPTURE

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_MOTION\_PHOTO\_CAPTURE

Standard Intent action that can be sent to have the camera application capture a [motion photo](/media/platform/motion-photo-format) and return it.

The caller must either pass an extra EXTRA\_OUTPUT to control where the image will be written, or a uri through `[android.content.Intent.setClipData(ClipData)](/reference/android/content/Intent#setClipData\(android.content.ClipData\))`. If you don't set a ClipData, it will be copied there for you when calling `[Context.startActivity(Intent)](/reference/android/content/Context#startActivity\(android.content.Intent\))`.

When an image is captured via this intent, `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)` won't be broadcasted.

Note: If your app declares as using the `[Manifest.permission.CAMERA](/reference/android/Manifest.permission#CAMERA)` permission which is not granted, then attempting to use this action will result in a `[SecurityException](/reference/java/lang/SecurityException)`.

**See also:**

-   `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`

Constant Value: "android.provider.action.MOTION\_PHOTO\_CAPTURE"

### ACTION\_MOTION\_PHOTO\_CAPTURE\_SECURE

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_MOTION\_PHOTO\_CAPTURE\_SECURE

Intent action that can be sent to have the camera application capture a [motion photo](/media/platform/motion-photo-format) and return it when the device is secured (e.g. with a pin, password, pattern, or face unlock). Applications responding to this intent must not expose any personal content like existing photos or videos on the device. The applications should be careful not to share any photo or video with other applications or Internet. The activity should use `[Activity.setShowWhenLocked](/reference/android/app/Activity#setShowWhenLocked\(boolean\))` to display on top of the lock screen while secured. There is no activity stack when this flag is used, so launching more than one activity is strongly discouraged.

The caller must either pass an extra EXTRA\_OUTPUT to control where the image will be written, or a uri through `[android.content.Intent.setClipData(ClipData)](/reference/android/content/Intent#setClipData\(android.content.ClipData\))`. If you don't set a ClipData, it will be copied there for you when calling `[Context.startActivity(Intent)](/reference/android/content/Context#startActivity\(android.content.Intent\))`.

When an image is captured via this intent, `[Camera.ACTION_NEW_PICTURE](/reference/android/hardware/Camera#ACTION_NEW_PICTURE)` won't be broadcasted.

**See also:**

-   `[ACTION_MOTION_PHOTO_CAPTURE](/reference/android/provider/MediaStore#ACTION_MOTION_PHOTO_CAPTURE)`
-   `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`

Constant Value: "android.provider.action.MOTION\_PHOTO\_CAPTURE\_SECURE"

### ACTION\_PICK\_IMAGES

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 2](/sdkExtensions)

public static final [String](/reference/java/lang/String) ACTION\_PICK\_IMAGES

Activity Action: Allow the user to select images or videos provided by system and return it. This is different than `[Intent.ACTION_PICK](/reference/android/content/Intent#ACTION_PICK)` and `[Intent.ACTION_GET_CONTENT](/reference/android/content/Intent#ACTION_GET_CONTENT)` in that

-   the data for this action is provided by the system
-   this action is only used for picking images and videos
-   caller gets read access to user picked items even without storage permissions

Callers can optionally specify MIME type (such as `image/*` or `video/*`), resulting in a range of content selection that the caller is interested in. The optional MIME type can be requested with `[Intent.setType(String)](/reference/android/content/Intent#setType\(java.lang.String\))`.

If the caller needs multiple returned items (or caller wants to allow multiple selection), then it can specify `[MediaStore.EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)` to indicate this.

When the caller requests multiple selection, the value of `[MediaStore.EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)` must be a positive integer greater than 1 and less than or equal to `[MediaStore.getPickImagesMaxLimit](/reference/android/provider/MediaStore#getPickImagesMaxLimit\(\))`, otherwise `[Activity.RESULT_CANCELED](/reference/android/app/Activity#RESULT_CANCELED)` is returned. Use `[MediaStore.EXTRA_PICK_IMAGES_IN_ORDER](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_IN_ORDER)` in multiple selection mode to allow the user to pick images in order.

If the caller needs to specify the `[ApplicationMediaCapabilities](/reference/android/media/ApplicationMediaCapabilities)` that should be used while picking video files, use `[MediaStore.EXTRA_MEDIA_CAPABILITIES](/reference/android/provider/MediaStore#EXTRA_MEDIA_CAPABILITIES)` to indicate this.

When the requested file format does not match the capabilities specified by caller and the video duration is within the range that the system can handle, it will get transcoded to a default supported format, otherwise, the caller will receive the original file.

Callers may use `[Intent.EXTRA_LOCAL_ONLY](/reference/android/content/Intent#EXTRA_LOCAL_ONLY)` to limit content selection to local data.

For system stability, it is preferred to open the URIs obtained from using this action by calling `[MediaStore.openFileDescriptor(ContentResolver,Uri,String,CancellationSignal)](/reference/android/provider/MediaStore#openFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))`, `[MediaStore.openAssetFileDescriptor(ContentResolver,Uri,String,CancellationSignal)](/reference/android/provider/MediaStore#openAssetFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))` or `[MediaStore.openTypedAssetFileDescriptor(ContentResolver,Uri,String,Bundle,CancellationSignal)](/reference/android/provider/MediaStore#openTypedAssetFileDescriptor\(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))` instead of `[ContentResolver](/reference/android/content/ContentResolver)` open APIs.

Output: MediaStore content URI(s) of the item(s) that was picked. Unlike other MediaStore URIs, these are referred to as 'picker' URIs and expose a limited set of read-only operations. Specifically, picker URIs can only be opened for read and queried for columns in `[PickerMediaColumns](/reference/android/provider/MediaStore.PickerMediaColumns)`.

Before this API, apps could use `[Intent.ACTION_GET_CONTENT](/reference/android/content/Intent#ACTION_GET_CONTENT)`. However, `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` is now the recommended option for images and videos, since it offers a better user experience.

Constant Value: "android.provider.action.PICK\_IMAGES"

### ACTION\_PICK\_IMAGES\_SETTINGS

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 3](/sdkExtensions)

public static final [String](/reference/java/lang/String) ACTION\_PICK\_IMAGES\_SETTINGS

Activity Action: Launch settings controlling images or videos selection with `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`. The settings page allows a user to change the enabled `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)` on the device and other media selection configurations.

**See also:**

-   `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`
-   `[isCurrentCloudMediaProviderAuthority(ContentResolver,String)](/reference/android/provider/MediaStore#isCurrentCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))`

Constant Value: "android.provider.action.PICK\_IMAGES\_SETTINGS"

### ACTION\_REVIEW

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_REVIEW

Standard action that can be sent to review the given media file.

The launched application is expected to provide a large-scale view of the given media file, while allowing the user to quickly access other recently captured media files.

Input: `[Intent.getData](/reference/android/content/Intent#getData\(\))` is URI of the primary media item to initially display.

**See also:**

-   `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)`
-   `[EXTRA_BRIGHTNESS](/reference/android/provider/MediaStore#EXTRA_BRIGHTNESS)`

Constant Value: "android.provider.action.REVIEW"

### ACTION\_REVIEW\_SECURE

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_REVIEW\_SECURE

Standard action that can be sent to review the given media file when the device is secured (e.g. with a pin, password, pattern, or face unlock). The applications should be careful not to share any media with other applications or Internet. The activity should use `[Activity.setShowWhenLocked](/reference/android/app/Activity#setShowWhenLocked\(boolean\))` to display on top of the lock screen while secured. There is no activity stack when this flag is used, so launching more than one activity is strongly discouraged.

The launched application is expected to provide a large-scale view of the given primary media file, while only allowing the user to quickly access other media from an explicit secondary list.

Input: `[Intent.getData](/reference/android/content/Intent#getData\(\))` is URI of the primary media item to initially display. `[Intent.getClipData](/reference/android/content/Intent#getClipData\(\))` is the limited list of secondary media items that the user is allowed to review. If `[Intent.getClipData](/reference/android/content/Intent#getClipData\(\))` is undefined, then no other media access should be allowed.

**See also:**

-   `[EXTRA_BRIGHTNESS](/reference/android/provider/MediaStore#EXTRA_BRIGHTNESS)`

Constant Value: "android.provider.action.REVIEW\_SECURE"

### ACTION\_VIDEO\_CAPTURE

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) ACTION\_VIDEO\_CAPTURE

Standard Intent action that can be sent to have the camera application capture a video and return it.

The caller may pass in an extra EXTRA\_VIDEO\_QUALITY to control the video quality.

The caller may pass in an extra EXTRA\_OUTPUT to control where the video is written.

-   If EXTRA\_OUTPUT is not present, the video will be written to the standard location for videos, and the Uri of that location will be returned in the data field of the Uri. `[Camera.ACTION_NEW_VIDEO](/reference/android/hardware/Camera#ACTION_NEW_VIDEO)` will also be broadcasted when the video is recorded.
-   If EXTRA\_OUTPUT is assigned a Uri value, no `[Camera.ACTION_NEW_VIDEO](/reference/android/hardware/Camera#ACTION_NEW_VIDEO)` will be broadcasted. As of `[Build.VERSION_CODES.LOLLIPOP](/reference/android/os/Build.VERSION_CODES#LOLLIPOP)`, this uri can also be supplied through `[android.content.Intent.setClipData(ClipData)](/reference/android/content/Intent#setClipData\(android.content.ClipData\))`. If using this approach, you still must supply the uri through the EXTRA\_OUTPUT field for compatibility with old applications. If you don't set a ClipData, it will be copied there for you when calling `[Context.startActivity(Intent)](/reference/android/content/Context#startActivity\(android.content.Intent\))`.

Note: if you app targets `[M](/reference/android/os/Build.VERSION_CODES#M)` and above and declares as using the `[Manifest.permission.CAMERA](/reference/android/Manifest.permission#CAMERA)` permission which is not granted, then atempting to use this action will result in a `[SecurityException](/reference/java/lang/SecurityException)`.

**See also:**

-   `[EXTRA_OUTPUT](/reference/android/provider/MediaStore#EXTRA_OUTPUT)`
-   `[EXTRA_VIDEO_QUALITY](/reference/android/provider/MediaStore#EXTRA_VIDEO_QUALITY)`
-   `[EXTRA_SIZE_LIMIT](/reference/android/provider/MediaStore#EXTRA_SIZE_LIMIT)`
-   `[EXTRA_DURATION_LIMIT](/reference/android/provider/MediaStore#EXTRA_DURATION_LIMIT)`
-   `[Camera.ACTION_NEW_VIDEO](/reference/android/hardware/Camera#ACTION_NEW_VIDEO)`

Constant Value: "android.media.action.VIDEO\_CAPTURE"

### AUTHORITY

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) AUTHORITY

The authority for the media provider

Constant Value: "media"

### EXTRA\_ACCEPT\_ORIGINAL\_MEDIA\_FORMAT

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_ACCEPT\_ORIGINAL\_MEDIA\_FORMAT

/\*\* Specify that the caller wants to receive the original media format without transcoding. **Caution: using this flag can cause app compatibility issues whenever Android adds support for new media formats.** Clients should instead specify their supported media capabilities explicitly in their manifest or with the `[EXTRA_MEDIA_CAPABILITIES](/reference/android/provider/MediaStore#EXTRA_MEDIA_CAPABILITIES)` `open` flag. This option is useful for apps that don't attempt to parse the actual byte contents of media files, such as playback using `[MediaPlayer](/reference/android/media/MediaPlayer)` or for off-device backup. Note that the `[Manifest.permission.ACCESS_MEDIA_LOCATION](/reference/android/Manifest.permission#ACCESS_MEDIA_LOCATION)` permission will still be required to avoid sensitive metadata redaction, similar to `[setRequireOriginal(Uri)](/reference/android/provider/MediaStore#setRequireOriginal\(android.net.Uri\))`. Note that this flag overrides any explicitly declared `media_capabilities.xml` or `[ApplicationMediaCapabilities](/reference/android/media/ApplicationMediaCapabilities)` extras specified in the same `open` request.

This option can be added to the `opts` `[Bundle](/reference/android/os/Bundle)` in various `[ContentResolver](/reference/android/content/ContentResolver)` `open` methods.

**See also:**

-   `[ContentResolver.openTypedAssetFileDescriptor(Uri,String,Bundle)](/reference/android/content/ContentResolver#openTypedAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.Bundle\))`
-   `[ContentResolver.openTypedAssetFile(Uri,String,Bundle,CancellationSignal)](/reference/android/content/ContentResolver#openTypedAssetFile\(android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))`
-   `[setRequireOriginal(Uri)](/reference/android/provider/MediaStore#setRequireOriginal\(android.net.Uri\))`
-   `[MediaStore.getOriginalMediaFormatFileDescriptor(Context,ParcelFileDescriptor)](/reference/android/provider/MediaStore#getOriginalMediaFormatFileDescriptor\(android.content.Context,%20android.os.ParcelFileDescriptor\))`

Constant Value: "android.provider.extra.ACCEPT\_ORIGINAL\_MEDIA\_FORMAT"

### EXTRA\_BRIGHTNESS

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_BRIGHTNESS

When defined, the launched application is requested to set the given brightness value via `[WindowManager.LayoutParams.screenBrightness](/reference/android/view/WindowManager.LayoutParams#screenBrightness)` to help ensure a smooth transition when launching `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)` intents.

Constant Value: "android.provider.extra.BRIGHTNESS"

### EXTRA\_DURATION\_LIMIT

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_DURATION\_LIMIT

Specify the maximum allowed recording duration in seconds.

Constant Value: "android.intent.extra.durationLimit"

### EXTRA\_FINISH\_ON\_COMPLETION

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_FINISH\_ON\_COMPLETION

The name of the Intent-extra used to control the onCompletion behavior of a MovieView. This is a boolean property that specifies whether or not to finish the MovieView activity when the movie completes playing. The default value is true, which means to automatically exit the movie player activity when the movie completes playing.

Constant Value: "android.intent.extra.finishOnCompletion"

### EXTRA\_FULL\_SCREEN

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_FULL\_SCREEN

The name of an Intent-extra used to control the UI of a ViewImage. This is a boolean property that overrides the activity's default fullscreen state.

Constant Value: "android.intent.extra.fullScreen"

### EXTRA\_MEDIA\_ALBUM

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_ALBUM

The name of the Intent-extra used to define the album

Constant Value: "android.intent.extra.album"

### EXTRA\_MEDIA\_ARTIST

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_ARTIST

The name of the Intent-extra used to define the artist

Constant Value: "android.intent.extra.artist"

### EXTRA\_MEDIA\_CAPABILITIES

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_CAPABILITIES

Specify the `[ApplicationMediaCapabilities](/reference/android/media/ApplicationMediaCapabilities)` that should be used while opening a media or picking media files.

If the capabilities specified matches the format of the original file, the app will receive the original file, otherwise, it will get transcoded to a default supported format.

When used while opening a media, add this option to the `opts` `[Bundle](/reference/android/os/Bundle)` in various `[ContentResolver](/reference/android/content/ContentResolver)` `open` methods. This flag takes higher precedence over the applications declared `media_capabilities.xml` and is useful for apps that want to have more granular control over their supported media capabilities.

When used while picking media files, add this option to the intent-extra of `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

**See also:**

-   `[ContentResolver.openTypedAssetFileDescriptor(Uri,String,Bundle)](/reference/android/content/ContentResolver#openTypedAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.Bundle\))`
-   `[ContentResolver.openTypedAssetFile(Uri,String,Bundle,CancellationSignal)](/reference/android/content/ContentResolver#openTypedAssetFile\(android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))`
-   `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

Constant Value: "android.provider.extra.MEDIA\_CAPABILITIES"

### EXTRA\_MEDIA\_CAPABILITIES\_UID

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_CAPABILITIES\_UID

Specify the UID of the app that should be used to determine supported media capabilities while opening a media. If this specified UID is found to be capable of handling the original media file format, the app will receive the original file, otherwise, the file will get transcoded to a default format supported by the specified UID.

Constant Value: "android.provider.extra.MEDIA\_CAPABILITIES\_UID"

### EXTRA\_MEDIA\_FOCUS

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_FOCUS

The name of the Intent-extra used to define the search focus. The search focus indicates whether the search should be for things related to the artist, album or song that is identified by the other extras.

Constant Value: "android.intent.extra.focus"

### EXTRA\_MEDIA\_GENRE

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_GENRE

The name of the Intent-extra used to define the genre.

Constant Value: "android.intent.extra.genre"

### EXTRA\_MEDIA\_PLAYLIST

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_PLAYLIST

**This constant was deprecated in API level 31.**  
Android playlists are now deprecated. We will keep the current functionality for compatibility resons, but we will no longer take feature request. We do not advise adding new usages of Android Playlists. M3U files can be used as an alternative.

The name of the Intent-extra used to define the playlist.

Constant Value: "android.intent.extra.playlist"

### EXTRA\_MEDIA\_RADIO\_CHANNEL

Added in [API level 21](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_RADIO\_CHANNEL

The name of the Intent-extra used to define the radio channel.

Constant Value: "android.intent.extra.radio\_channel"

### EXTRA\_MEDIA\_TITLE

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_MEDIA\_TITLE

The name of the Intent-extra used to define the song title

Constant Value: "android.intent.extra.title"

### EXTRA\_OUTPUT

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_OUTPUT

The name of the Intent-extra used to indicate a content resolver Uri to be used to store the requested image or video.

Constant Value: "output"

### EXTRA\_PICKER\_PRE\_SELECTION\_URIS

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 15](/sdkExtensions)

public static final [String](/reference/java/lang/String) EXTRA\_PICKER\_PRE\_SELECTION\_URIS

The name of an optional intent-extra used to specify URIs for pre-selection in photo picker opened with `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` in multi-select mode.

Only MediaStore content URI(s) of the item(s) received as a result of `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` action are accepted. The value of this intent-extra should be an ArrayList of type URIs. Default value is null. Maximum number of URIs that can be accepted is limited by the value passed in `[MediaStore.EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)` as part of the `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` intent. In case the count of input URIs is greater than the limit then `IllegalArgumentException` is thrown.

The provided list will be checked for permissions and authority. Any URI that is inaccessible, doesn't match the current authorities(local or cloud) or is invalid will be filtered out. Additionally, items that are disabled based on the provided `[MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_SELECTION_PARAMS)` will also be filtered out.

The remaining items will appear selected when the photo picker is opened, provided they satisfy the aggregate constraints such as `[MediaStore.EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)` and `PhotoPickerSelectionParams.getMaxSelectionBatchSizeInBytes()`. In the case of `[MediaStore.EXTRA_PICK_IMAGES_IN_ORDER](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_IN_ORDER)` the chronological order of the input list will be used for ordered selection of the pre-selected items.

This is not a mechanism to revoke permissions for items, i.e. de-selection of a pre-selected item by the user will not result in revocation of the grant.

Constant Value: "android.provider.extra.PICKER\_PRE\_SELECTION\_URIS"

### EXTRA\_PICK\_IMAGES\_ACCENT\_COLOR

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 12](/sdkExtensions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_ACCENT\_COLOR

The name of an optional intent-extra used to allow apps to specify the picker accent color. The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`. The accent color will be used for various primary elements in the PhotoPicker view. All other colors will be set based on android material guidelines.

The value of this intent extra should be a long color value. The alpha component of the given color is not taken into account while setting the accent color. We assume full color opacity. Only colors with luminance(can also be understood as brightness) greater than 0.05 and less than 0.9 are permitted. Luminance of a color is determined using: luminance = Color.luminance(color) where color is the input accent color to be set. Check `[ERROR(/Color)](/)` docs for more details on color luminance and long color values. In case the luminance of the input color is unacceptable, picker colors will be set based on the colors of the device android theme. In case of an invalid input color value i.e. the input color cannot be parsed, `IllegalArgumentException` is thrown.

Constant Value: "android.provider.extra.PICK\_IMAGES\_ACCENT\_COLOR"

### EXTRA\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM

Added in [version 36.1](/topic/libraries/support-library/revisions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM

The name of an optional intent-extra used to allow apps to highlight media results of a photopicker album in the photopicker UI whenever feasible based on the given input album in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)`. The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

This intent extra will accept a `[Bundle](/reference/android/os/Bundle)` object. The bundle object should have two keys specified. The first key `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` will specify the type of media highlight the app wants to opt for and should be one of `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)` for a highlighted media section to be shown when the photopicker launches or `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)` to open the photopicker to show a highlighted media results grid based on the given input album. Any other value for this key will result in throwing `IllegalArgumentException`. The second bundle key `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` will specify the album to be highlighted and its value should be one of: `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES)` for the Favorites album, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA)` for the Camera album, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS)` for the Screenshots album, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS)` for the Videos album and `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS)` for the Downloads album. Any other value for this key will result in throwing `IllegalArgumentException`. Highlighting media results based on an input string text query is also supported. See `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` for more details. Only one of `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` or `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` should be used at any given time. Specifying both will result in `IllegalArgumentException` to be thrown.

Constant Value: "android.provider.extra.PICK\_IMAGES\_HIGHLIGHT\_ALBUM"

### EXTRA\_PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_RESULTS

Added in [version 36.1](/topic/libraries/support-library/revisions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_RESULTS

The name of an optional intent-extra used to allow apps to highlight search results in the photopicker UI whenever feasible based on the given input text query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

This intent extra will accept a `[Bundle](/reference/android/os/Bundle)` object. The bundle object should have two keys specified. The first key `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` will specify the type of media highlight the app wants to opt for and should be one of `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)` for a highlighted media section to be shown when the photopicker launches or `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)` to open the photopicker to show a highlighted media results grid based on the given input query. Any other value for this key will result in throwing `IllegalArgumentException`. The second bundle key `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` will accept a string value specifying the query for highlighting media results. If this input value is empty, the request to highlight media will be ignored and no highlight will be shown at all. In case of null input query, `IllegalArgumentException` is thrown. Highlighting media results of a photopicker album is also supported. See `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` for more details. Only one of `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` or `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` should be used at any given time. Specifying both will result in `IllegalArgumentException` to be thrown.

Constant Value: "android.provider.extra.PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_RESULTS"

### EXTRA\_PICK\_IMAGES\_IN\_ORDER

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 12](/sdkExtensions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_IN\_ORDER

The name of an optional intent-extra used to allow ordered selection of items. Set this extra to true to allow the user to see the order of their selected items. The result returned to the caller will be the same as the user selected order. This extra is only allowed via the `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

The value of this intent-extra should be a boolean. Default value is false.

**See also:**

-   `[ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

Constant Value: "android.provider.extra.PICK\_IMAGES\_IN\_ORDER"

### EXTRA\_PICK\_IMAGES\_LAUNCH\_TAB

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 12](/sdkExtensions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_LAUNCH\_TAB

The name of an optional intent-extra used to allow apps to specify the tab the picker should open with. The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

The value of this intent-extra must be one of: `[MediaStore.PICK_IMAGES_TAB_ALBUMS](/reference/android/provider/MediaStore#PICK_IMAGES_TAB_ALBUMS)` for the albums tab and `[MediaStore.PICK_IMAGES_TAB_IMAGES](/reference/android/provider/MediaStore#PICK_IMAGES_TAB_IMAGES)` for the photos tab. The system will decide which tab to open by default and in most cases, it is `[MediaStore.PICK_IMAGES_TAB_IMAGES](/reference/android/provider/MediaStore#PICK_IMAGES_TAB_IMAGES)` i.e. the photos tab.

Constant Value: "android.provider.extra.PICK\_IMAGES\_LAUNCH\_TAB"

### EXTRA\_PICK\_IMAGES\_MAX

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 2](/sdkExtensions)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_MAX

The name of an optional intent-extra used to allow multiple selection of items and constrain maximum number of items that can be returned by `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`, action may still return nothing (0 items) if the user chooses to cancel.

The value of this intent-extra should be a positive integer greater than 1 and less than or equal to `[MediaStore.getPickImagesMaxLimit](/reference/android/provider/MediaStore#getPickImagesMaxLimit\(\))`, otherwise `[Activity.RESULT_CANCELED](/reference/android/app/Activity#RESULT_CANCELED)` is returned.

If `[MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS](/reference/android/provider/MediaStore#EXTRA_PICKER_PRE_SELECTION_URIS)` is also provided, the number of URIs in that list must not exceed this value, otherwise an `IllegalIntentExtraException` will be thrown.

Constant Value: "android.provider.extra.PICK\_IMAGES\_MAX"

### EXTRA\_PICK\_IMAGES\_SELECTION\_PARAMS

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_SELECTION\_PARAMS

The name of an optional intent-extra used to pass `[PhotoPickerSelectionParams](/reference/android/widget/photopicker/PhotoPickerSelectionParams)` to the photo picker. This extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

The `[PhotoPickerSelectionParams](/reference/android/widget/photopicker/PhotoPickerSelectionParams)` object allows the calling app to set constraints on the media items that can be selected by the user. Media items that fail to satisfy these constraints will be disabled for selection.

Not passing this EXTRA, means the photo picker will not apply any restrictions on what media items users can select (except for the MIME type specified in the `[android.content.Intent.setType(String)](/reference/android/content/Intent#setType\(java.lang.String\))` extra).

To use this key, calling apps should construct a `[PhotoPickerSelectionParams](/reference/android/widget/photopicker/PhotoPickerSelectionParams)` object using its `[PhotoPickerSelectionParams.Builder](/reference/android/widget/photopicker/PhotoPickerSelectionParams.Builder)` and pass it as the value.  
Example: If the calling app wants to allow selection of only those media items that have a maximum size of 10,000 bytes and a minimum resolution of 500 pixels:

PhotoPickerSelectionParams selectionParams = new PhotoPickerSelectionParams.Builder()
    .setMaxMediaItemSizeInBytes(10000L)
    .setMinMediaItemResolutionInPixels(500L)
    .build();
Intent intent = new Intent(MediaStore.ACTION\_PICK\_IMAGES);
intent.putExtra(MediaStore.EXTRA\_PICK\_IMAGES\_SELECTION\_PARAMS, selectionParams);

Constant Value: "android.provider.extra.PICK\_IMAGES\_SELECTION\_PARAMS"

### EXTRA\_PICK\_IMAGES\_UI\_CUSTOMIZATION\_PARAMS

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_PICK\_IMAGES\_UI\_CUSTOMIZATION\_PARAMS

The name of an optional intent-extra used to set the ui customization options in the PhotoPicker.

The value of this intent-extra should be a `[PhotoPickerUiCustomizationParams](/reference/android/widget/photopicker/PhotoPickerUiCustomizationParams)` object. The extra can only be specified in `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

Not passing this EXTRA, means the photo picker will use its default UI (e.g. rendering the media items grid in 1:1 aspect ratio).

To use this key, calling apps should construct a `[PhotoPickerUiCustomizationParams](/reference/android/widget/photopicker/PhotoPickerUiCustomizationParams)` object using its `[PhotoPickerUiCustomizationParams.Builder](/reference/android/widget/photopicker/PhotoPickerUiCustomizationParams.Builder)` and pass it as the value.  
Example: If the calling app wants to allow the Photo Picker to use a 9:16 aspect ratio for the thumbnails:

PhotoPickerUiCustomizationParams params = new PhotoPickerUiCustomizationParams.Builder()
    .setAspectRatio(PhotoPickerUiCustomizationParams.ASPECT\_RATIO\_PORTRAIT\_9\_16)
    .build();
Intent intent = new Intent(MediaStore.ACTION\_PICK\_IMAGES);
intent.putExtra(MediaStore.EXTRA\_PICK\_IMAGES\_UI\_CUSTOMIZATION\_PARAMS, params);

**See also:**

-   `[PhotoPickerUiCustomizationParams](/reference/android/widget/photopicker/PhotoPickerUiCustomizationParams)`

Constant Value: "android.provider.extra.PICK\_IMAGES\_UI\_CUSTOMIZATION\_PARAMS"

### EXTRA\_REQUEST\_LOCATION\_METADATA\_ACCESS

Added in [version 37.1](/topic/libraries/support-library/revisions)

public static final [String](/reference/java/lang/String) EXTRA\_REQUEST\_LOCATION\_METADATA\_ACCESS

The name of an optional intent-extra used to allow apps to request access to the location metadata of the media items selected by the user and returned by `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`.

This is a boolean intent extra which when set to `true` informs the photopicker that the app is requesting location information for the media items selected by the user. The default value for this extra will always be `false` i.e. not sharing the location metadata of the selected media items with the calling app.

**For `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`:** This extra is always required to request location metadata. If excluded, location metadata is redacted by default.

**Note on User Choice:** For `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`, using this intent extra does not guarantee that the calling app will get the location information. The photopicker reserves the right to inform the user of this request via the UI. The user's choice to allow or deny location sharing in the picker UI is **final**.

If location access is ultimately granted, calling apps can extract this metadata when the selected media files are opened using the returned picker URIs.

Constant Value: "android.provider.extra.REQUEST\_LOCATION\_METADATA\_ACCESS"

### EXTRA\_SCREEN\_ORIENTATION

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_SCREEN\_ORIENTATION

The name of the Intent-extra used to control the orientation of a ViewImage or a MovieView. This is an int property that overrides the activity's requestedOrientation.

**See also:**

-   `[ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED](/reference/android/content/pm/ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED)`

Constant Value: "android.intent.extra.screenOrientation"

### EXTRA\_SHOW\_ACTION\_ICONS

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_SHOW\_ACTION\_ICONS

The name of an Intent-extra used to control the UI of a ViewImage. This is a boolean property that specifies whether or not to show action icons.

Constant Value: "android.intent.extra.showActionIcons"

### EXTRA\_SIZE\_LIMIT

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_SIZE\_LIMIT

Specify the maximum allowed size.

Constant Value: "android.intent.extra.sizeLimit"

### EXTRA\_VIDEO\_QUALITY

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) EXTRA\_VIDEO\_QUALITY

The name of the Intent-extra used to control the quality of a recorded video. This is an integer property. Currently value 0 means low quality, suitable for MMS messages, and value 1 means high quality. In the future other quality levels may be added.

Constant Value: "android.intent.extra.videoQuality"

### INTENT\_ACTION\_MEDIA\_PLAY\_FROM\_SEARCH

Added in [API level 9](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_MEDIA\_PLAY\_FROM\_SEARCH

An intent to perform a search for music media and automatically play content from the result when possible. This can be fired, for example, by the result of a voice recognition command to listen to music.

This intent always includes the `[EXTRA_MEDIA_FOCUS](/reference/android/provider/MediaStore#EXTRA_MEDIA_FOCUS)` and `[SearchManager.QUERY](/reference/android/app/SearchManager#QUERY)` extras. The `[EXTRA_MEDIA_FOCUS](/reference/android/provider/MediaStore#EXTRA_MEDIA_FOCUS)` extra determines the search mode, and the value of the `[SearchManager.QUERY](/reference/android/app/SearchManager#QUERY)` extra depends on the search mode. For more information about the search modes for this intent, see [Play music based on a search query](/guide/components/intents-common#PlaySearch) in [Common Intents](/guide/components/intents-common).

This intent makes the most sense for apps that can support large-scale search of music, such as services connected to an online database of music which can be streamed and played on the device.

Constant Value: "android.media.action.MEDIA\_PLAY\_FROM\_SEARCH"

### INTENT\_ACTION\_MEDIA\_SEARCH

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_MEDIA\_SEARCH

Activity Action: Perform a search for media. Contains at least the `[SearchManager.QUERY](/reference/android/app/SearchManager#QUERY)` extra. May also contain any combination of the following extras: EXTRA\_MEDIA\_ARTIST, EXTRA\_MEDIA\_ALBUM, EXTRA\_MEDIA\_TITLE, EXTRA\_MEDIA\_FOCUS

**See also:**

-   `[EXTRA_MEDIA_ARTIST](/reference/android/provider/MediaStore#EXTRA_MEDIA_ARTIST)`
-   `[EXTRA_MEDIA_ALBUM](/reference/android/provider/MediaStore#EXTRA_MEDIA_ALBUM)`
-   `[EXTRA_MEDIA_TITLE](/reference/android/provider/MediaStore#EXTRA_MEDIA_TITLE)`
-   `[EXTRA_MEDIA_FOCUS](/reference/android/provider/MediaStore#EXTRA_MEDIA_FOCUS)`

Constant Value: "android.intent.action.MEDIA\_SEARCH"

### INTENT\_ACTION\_MUSIC\_PLAYER

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 15](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_MUSIC\_PLAYER

**This constant was deprecated in API level 15.**  
Use `[Intent.CATEGORY_APP_MUSIC](/reference/android/content/Intent#CATEGORY_APP_MUSIC)` instead.

Activity Action: Launch a music player. The activity should be able to play, browse, or manipulate music files stored on the device.

Constant Value: "android.intent.action.MUSIC\_PLAYER"

### INTENT\_ACTION\_STILL\_IMAGE\_CAMERA

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_STILL\_IMAGE\_CAMERA

The name of the Intent action used to launch a camera in still image mode.

Constant Value: "android.media.action.STILL\_IMAGE\_CAMERA"

### INTENT\_ACTION\_STILL\_IMAGE\_CAMERA\_SECURE

Added in [API level 17](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_STILL\_IMAGE\_CAMERA\_SECURE

The name of the Intent action used to launch a camera in still image mode for use when the device is secured (e.g. with a pin, password, pattern, or face unlock). Applications responding to this intent must not expose any personal content like existing photos or videos on the device. The applications should be careful not to share any photo or video with other applications or internet. The activity should use `[Activity.setShowWhenLocked](/reference/android/app/Activity#setShowWhenLocked\(boolean\))` to display on top of the lock screen while secured. There is no activity stack when this flag is used, so launching more than one activity is strongly discouraged.

Constant Value: "android.media.action.STILL\_IMAGE\_CAMERA\_SECURE"

### INTENT\_ACTION\_TEXT\_OPEN\_FROM\_SEARCH

Added in [API level 17](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_TEXT\_OPEN\_FROM\_SEARCH

An intent to perform a search for readable media and automatically play content from the result when possible. This can be fired, for example, by the result of a voice recognition command to read a book or magazine.

Contains the `[SearchManager.QUERY](/reference/android/app/SearchManager#QUERY)` extra, which is a string that can contain any type of unstructured text search, like the name of a book or magazine, an author a genre, a publisher, or any combination of these.

Because this intent includes an open-ended unstructured search string, it makes the most sense for apps that can support large-scale search of text media, such as services connected to an online database of books and/or magazines which can be read on the device.

Constant Value: "android.media.action.TEXT\_OPEN\_FROM\_SEARCH"

### INTENT\_ACTION\_VIDEO\_CAMERA

Added in [API level 3](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_VIDEO\_CAMERA

The name of the Intent action used to launch a camera in video mode.

Constant Value: "android.media.action.VIDEO\_CAMERA"

### INTENT\_ACTION\_VIDEO\_PLAY\_FROM\_SEARCH

Added in [API level 17](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) INTENT\_ACTION\_VIDEO\_PLAY\_FROM\_SEARCH

An intent to perform a search for video media and automatically play content from the result when possible. This can be fired, for example, by the result of a voice recognition command to play movies.

Contains the `[SearchManager.QUERY](/reference/android/app/SearchManager#QUERY)` extra, which is a string that can contain any type of unstructured video search, like the name of a movie, one or more actors, a genre, or any combination of these.

Because this intent includes an open-ended unstructured search string, it makes the most sense for apps that can support large-scale search of video, such as services connected to an online database of videos which can be streamed and played on the device.

Constant Value: "android.media.action.VIDEO\_PLAY\_FROM\_SEARCH"

### KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID

Added in [version 36.1](/topic/libraries/support-library/revisions)

public static final [String](/reference/java/lang/String) KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` specifying the photopicker album for which the app can request to show highlighted media results in the photopicker. The value of this key should be one of `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES)`, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA)`, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS)`, `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS)` or `[MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS)`. Any other value for this key will result in throwing `IllegalArgumentException`. See: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.key.PICK\_IMAGES\_HIGHLIGHT\_MEDIA\_ALBUM\_ID"

### KEY\_PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_TEXT\_QUERY

Added in [version 36.1](/topic/libraries/support-library/revisions)

public static final [String](/reference/java/lang/String) KEY\_PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_TEXT\_QUERY

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` specifying the input query for which the app can request to show highlighted media results in the photopicker. The photopicker will trigger a search based on this input value to show highlighted media results. The value of this key can be any string value for which the app wants to show highlighted results. In case the input text query is null, `IllegalArgumentException` is thrown. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)`

Constant Value: "android.provider.media.key.PICK\_IMAGES\_HIGHLIGHT\_SEARCH\_TEXT\_QUERY"

### KEY\_PICK\_IMAGES\_HIGHLIGHT\_TYPE

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) KEY\_PICK\_IMAGES\_HIGHLIGHT\_TYPE

One of the `[Bundle](/reference/android/os/Bundle)` keys for `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` and `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)` to specify the highlight type i.e. the way the highlighted media results will be shown in the photopicker. The value can be one of `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)` to show a highlight media section in the photopicker or `[MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED](/reference/android/provider/MediaStore#PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)` to show a highlighted media results grid. Any other value for this key will result in throwing `IllegalArgumentException`. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)`

Constant Value: "android.provider.media.key.PICK\_IMAGES\_HIGHLIGHT\_TYPE"

### MATCH\_DEFAULT

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MATCH\_DEFAULT

Value indicating that the default matching behavior should be used, as defined by the key documentation.

Constant Value: 0 (0x00000000)

### MATCH\_EXCLUDE

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MATCH\_EXCLUDE

Value indicating that operations should exclude items matching the criteria defined by this key.

Constant Value: 2 (0x00000002)

### MATCH\_INCLUDE

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MATCH\_INCLUDE

Value indicating that operations should include items matching the criteria defined by this key.

Note that items _not_ matching the criteria _may_ also be included depending on the default behavior documented by the key. If you want to operate exclusively on matching items, use `[MATCH_ONLY](/reference/android/provider/MediaStore#MATCH_ONLY)`.

Constant Value: 1 (0x00000001)

### MATCH\_ONLY

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int MATCH\_ONLY

Value indicating that operations should only operate on items explicitly matching the criteria defined by this key.

Constant Value: 3 (0x00000003)

### MEDIA\_IGNORE\_FILENAME

Added in [API level 9](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) MEDIA\_IGNORE\_FILENAME

Name of the file signaling the media scanner to ignore media in the containing directory and its subdirectories. Developers should use this to avoid application graphics showing up in the Gallery and likewise prevent application sounds and music from showing up in the Music app.

Constant Value: ".nomedia"

### MEDIA\_SCANNER\_VOLUME

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) MEDIA\_SCANNER\_VOLUME

Name of current volume being scanned by the media scanner.

Constant Value: "volume"

### META\_DATA\_REVIEW\_GALLERY\_PREWARM\_SERVICE

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) META\_DATA\_REVIEW\_GALLERY\_PREWARM\_SERVICE

Name under which an activity handling `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)` publishes the service name for its prewarm service.

This meta-data should reference the fully qualified class name of the prewarm service

The prewarm service can be bound before starting `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)`. An application implementing this prewarm service should do the absolute minimum amount of work to initialize its resources to efficiently handle an `[ACTION_REVIEW](/reference/android/provider/MediaStore#ACTION_REVIEW)` or `[ACTION_REVIEW_SECURE](/reference/android/provider/MediaStore#ACTION_REVIEW_SECURE)` in the near future.

Constant Value: "android.media.review\_gallery\_prewarm\_service"

### META\_DATA\_STILL\_IMAGE\_CAMERA\_PREWARM\_SERVICE

Added in [API level 23](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) META\_DATA\_STILL\_IMAGE\_CAMERA\_PREWARM\_SERVICE

Name under which an activity handling `[INTENT_ACTION_STILL_IMAGE_CAMERA](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA)` or `[INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE](/reference/android/provider/MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)` publishes the service name for its prewarm service.

This meta-data should reference the fully qualified class name of the prewarm service extending `CameraPrewarmService`.

The prewarm service will get bound and receive a prewarm signal `CameraPrewarmService#onPrewarm()` when a camera launch intent fire might be imminent. An application implementing a prewarm service should do the absolute minimum amount of work to initialize the camera in order to reduce startup time in likely case that shortly after a camera launch intent would be sent.

Constant Value: "android.media.still\_image\_camera\_preview\_service"

### PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_CAMERA

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_CAMERA

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Camera album. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_CAMERA"

### PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_DOWNLOADS

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_DOWNLOADS

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Downloads album. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_DOWNLOADS"

### PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_FAVORITES

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_FAVORITES

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Favorites album. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_FAVORITES"

### PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_SCREENSHOTS

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_SCREENSHOTS

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from from an album, in this case, the Screenshots album. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_SCREENSHOTS"

### PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_VIDEOS

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final [String](/reference/java/lang/String) PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_VIDEOS

One of the possible album highlight values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID)` in case an app chooses to highlight media results from an album, in this case, the Videos album. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: "android.provider.media.PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_VIDEOS"

### PICK\_IMAGES\_HIGHLIGHT\_TYPE\_COLLAPSED

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final int PICK\_IMAGES\_HIGHLIGHT\_TYPE\_COLLAPSED

One of the permitted values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` to highlight media results as a highlighted media section in the photopicker based on the given input query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` or the given input photopicker album in MediaStore#KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID}. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` and `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: 0 (0x00000000)

### PICK\_IMAGES\_HIGHLIGHT\_TYPE\_EXPANDED

Added in [version 36.1](/topic/libraries/support-library/revisions)  
Also in [T Extensions 19](/sdkExtensions)

public static final int PICK\_IMAGES\_HIGHLIGHT\_TYPE\_EXPANDED

One of the permitted values for `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_TYPE)` to show a highlighted media results grid based on the given input query in `[MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY](/reference/android/provider/MediaStore#KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY)` or the given input photopicker album in MediaStore#KEY\_PICK\_IMAGES\_HIGHLIGHT\_ALBUM\_ID}. Read more: `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS)` and `[MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM)`

Constant Value: 1 (0x00000001)

### PICK\_IMAGES\_TAB\_ALBUMS

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int PICK\_IMAGES\_TAB\_ALBUMS

One of the permitted values for `[MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_LAUNCH_TAB)` to open the picker with albums tab.

Constant Value: 0 (0x00000000)

### PICK\_IMAGES\_TAB\_IMAGES

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final int PICK\_IMAGES\_TAB\_IMAGES

One of the permitted values for `[MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_LAUNCH_TAB)` to open the picker with photos tab.

Constant Value: 1 (0x00000001)

### QUERY\_ARG\_INCLUDE\_RECENTLY\_UNMOUNTED\_VOLUMES

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_INCLUDE\_RECENTLY\_UNMOUNTED\_VOLUMES

Flag that requests `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))` to include content from recently unmounted volumes.

When the flag is set, `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))` will return content from all volumes(i.e., both mounted and recently unmounted volume whose content is still held by MediaProvider).

Note that the query result doesn't provide any hint for content from unmounted volume. It's strongly recommended to use default query to avoid accessing/operating on the content that are not available on the device.

The flag is useful for apps which manage their own database and query MediaStore in order to synchronize between MediaStore database and their own database.

Constant Value: "android:query-arg-recently-unmounted-volumes"

### QUERY\_ARG\_LATEST\_SELECTION\_ONLY

Added in [API level 35](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [U Extensions 12](/sdkExtensions)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_LATEST\_SELECTION\_ONLY

Flag that indicates if only the latest selection in the photoPicker for the calling app should be returned. If set to true, all items that were granted to the calling app in the last selection are returned.

Selection in this scenario refers to when the user selects items in **the permission prompt photo picker**. The access for these items is granted to the calling app and these grants are persisted unless the user deselects a granted item explicitly.

The result excludes items owned by the calling app unless they are explicitly selected by the user.

Note: If there has been no user selections after the introduction of this feature then all the granted items will be returned.

This key can be placed in a `[Bundle](/reference/android/os/Bundle)` of extras and passed to `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`.

**See also:**

-   `[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED](/reference/android/Manifest.permission#READ_MEDIA_VISUAL_USER_SELECTED)`

Constant Value: "android:query-arg-latest-selection-only"

### QUERY\_ARG\_MATCH\_FAVORITE

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_MATCH\_FAVORITE

Specify how `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

This key can be placed in a `[Bundle](/reference/android/os/Bundle)` of extras and passed to `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`, `[ContentResolver.update](/reference/android/content/ContentResolver#update\(android.net.Uri,%20android.content.ContentValues,%20android.os.Bundle\))`, or `[ContentResolver.delete](/reference/android/content/ContentResolver#delete\(android.net.Uri,%20android.os.Bundle\))`.

By default, favorite items are _not_ filtered away from operations.  
Value is either `0` or a combination of the following:

-   `[MATCH_DEFAULT](/reference/android/provider/MediaStore#MATCH_DEFAULT)`
-   `[MATCH_INCLUDE](/reference/android/provider/MediaStore#MATCH_INCLUDE)`
-   `[MATCH_EXCLUDE](/reference/android/provider/MediaStore#MATCH_EXCLUDE)`
-   `[MATCH_ONLY](/reference/android/provider/MediaStore#MATCH_ONLY)`

**See also:**

-   `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)`
-   `[MediaStore.QUERY_ARG_MATCH_FAVORITE](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_FAVORITE)`
-   `[MediaStore.createFavoriteRequest](/reference/android/provider/MediaStore#createFavoriteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))`

Constant Value: "android:query-arg-match-favorite"

### QUERY\_ARG\_MATCH\_PENDING

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_MATCH\_PENDING

Specify how `[MediaColumns.IS_PENDING](/reference/android/provider/MediaStore.MediaColumns#IS_PENDING)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

This key can be placed in a `[Bundle](/reference/android/os/Bundle)` of extras and passed to `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`, `[ContentResolver.update](/reference/android/content/ContentResolver#update\(android.net.Uri,%20android.content.ContentValues,%20android.os.Bundle\))`, or `[ContentResolver.delete](/reference/android/content/ContentResolver#delete\(android.net.Uri,%20android.os.Bundle\))`.

By default, pending items are filtered away from operations.  
Value is either `0` or a combination of the following:

-   `[MATCH_DEFAULT](/reference/android/provider/MediaStore#MATCH_DEFAULT)`
-   `[MATCH_INCLUDE](/reference/android/provider/MediaStore#MATCH_INCLUDE)`
-   `[MATCH_EXCLUDE](/reference/android/provider/MediaStore#MATCH_EXCLUDE)`
-   `[MATCH_ONLY](/reference/android/provider/MediaStore#MATCH_ONLY)`

Constant Value: "android:query-arg-match-pending"

### QUERY\_ARG\_MATCH\_TRASHED

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_MATCH\_TRASHED

Specify how `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)` items should be filtered when performing a `[MediaStore](/reference/android/provider/MediaStore)` operation.

This key can be placed in a `[Bundle](/reference/android/os/Bundle)` of extras and passed to `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`, `[ContentResolver.update](/reference/android/content/ContentResolver#update\(android.net.Uri,%20android.content.ContentValues,%20android.os.Bundle\))`, or `[ContentResolver.delete](/reference/android/content/ContentResolver#delete\(android.net.Uri,%20android.os.Bundle\))`.

By default, trashed items are filtered away from operations.  
Value is either `0` or a combination of the following:

-   `[MATCH_DEFAULT](/reference/android/provider/MediaStore#MATCH_DEFAULT)`
-   `[MATCH_INCLUDE](/reference/android/provider/MediaStore#MATCH_INCLUDE)`
-   `[MATCH_EXCLUDE](/reference/android/provider/MediaStore#MATCH_EXCLUDE)`
-   `[MATCH_ONLY](/reference/android/provider/MediaStore#MATCH_ONLY)`

**See also:**

-   `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)`
-   `[MediaStore.QUERY_ARG_MATCH_TRASHED](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_TRASHED)`
-   `[MediaStore.createTrashRequest](/reference/android/provider/MediaStore#createTrashRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))`

Constant Value: "android:query-arg-match-trashed"

### QUERY\_ARG\_MEDIA\_STANDARD\_SORT\_ORDER

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 15](/sdkExtensions)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_MEDIA\_STANDARD\_SORT\_ORDER

Flag that requests `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))` to sort the result in descending order based on `[MediaColumns.INFERRED_DATE](/reference/android/provider/MediaStore.MediaColumns#INFERRED_DATE)`.

When this flag is used as an extra in a `[Bundle](/reference/android/os/Bundle)` passed to `[ContentResolver.query](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`, all other sorting options such as `[ContentResolver.QUERY_ARG_SORT_COLUMNS](/reference/android/content/ContentResolver#QUERY_ARG_SORT_COLUMNS)` or `[ContentResolver.QUERY_ARG_SQL_SORT_ORDER](/reference/android/content/ContentResolver#QUERY_ARG_SQL_SORT_ORDER)` are disregarded.

Constant Value: "android:query-arg-media-standard-sort-order"

### QUERY\_ARG\_RELATED\_URI

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) QUERY\_ARG\_RELATED\_URI

Specify a `[Uri](/reference/android/net/Uri)` that is "related" to the current operation being performed.

This is typically used to allow an operation that may normally be rejected, such as making a copy of a pre-existing image located under a `[MediaColumns.RELATIVE_PATH](/reference/android/provider/MediaStore.MediaColumns#RELATIVE_PATH)` where new images are not allowed.

It's strongly recommended that when making a copy of pre-existing content that you define the "original document ID" GUID as defined by the _XMP Media Management_ standard.

This key can be placed in a `[Bundle](/reference/android/os/Bundle)` of extras and passed to `[ContentResolver.insert](/reference/android/content/ContentResolver#insert\(android.net.Uri,%20android.content.ContentValues\))`.

Constant Value: "android:query-arg-related-uri"

### UNKNOWN\_STRING

Added in [API level 8](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) UNKNOWN\_STRING

The string that is used when a media attribute is not known. For example, if an audio file does not have any meta data, the artist and album columns will be set to this value.

Constant Value: ""

### VOLUME\_EXTERNAL

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) VOLUME\_EXTERNAL

Synthetic volume name that provides a view of all content across the "external" storage of the device.

This synthetic volume provides a merged view of all media across all currently attached external storage devices.

Because this is a synthetic volume, you can't insert new content into this volume. Instead, you can insert content into a specific storage volume obtained from `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))`.

Constant Value: "external"

### VOLUME\_EXTERNAL\_PRIMARY

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) VOLUME\_EXTERNAL\_PRIMARY

Specific volume name that represents the primary external storage device at `[Environment.getExternalStorageDirectory()](/reference/android/os/Environment#getExternalStorageDirectory\(\))`.

This volume may not always be available, such as when the user has ejected the device. You can find a list of all specific volume names using `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))`.

Constant Value: "external\_primary"

### VOLUME\_INTERNAL

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [String](/reference/java/lang/String) VOLUME\_INTERNAL

Synthetic volume name that provides a view of all content across the "internal" storage of the device.

This synthetic volume provides a merged view of all media distributed with the device, such as built-in ringtones and wallpapers.

Because this is a synthetic volume, you can't insert new content into this volume.

Constant Value: "internal"

## Fields

### AUTHORITY\_URI

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static final [Uri](/reference/android/net/Uri) AUTHORITY\_URI

A content:// style uri to the authority for the media provider

## Public constructors

### MediaStore

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public MediaStore ()

## Public methods

### canManageMedia

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static boolean canManageMedia ([Context](/reference/android/content/Context) context)

Returns whether the calling app is granted `[Manifest.permission.MANAGE_MEDIA](/reference/android/Manifest.permission#MANAGE_MEDIA)` or not.

Declaring the permission `[Manifest.permission.MANAGE_MEDIA](/reference/android/Manifest.permission#MANAGE_MEDIA)` isn't enough to gain the access.

To request access, use `[Settings.ACTION_REQUEST_MANAGE_MEDIA](/reference/android/provider/Settings#ACTION_REQUEST_MANAGE_MEDIA)`.

| Parameters |
| --- |
| `context` | `Context`: the request context.  
This value cannot be `null`.
 |

| Returns |
| --- |
| `boolean` | true, the calling app is granted the permission. Otherwise, false
 |

**See also:**

-   `[Manifest.permission.MANAGE_MEDIA](/reference/android/Manifest.permission#MANAGE_MEDIA)`
-   `[Settings.ACTION_REQUEST_MANAGE_MEDIA](/reference/android/provider/Settings#ACTION_REQUEST_MANAGE_MEDIA)`
-   `[createDeleteRequest(ContentResolver,Collection)](/reference/android/provider/MediaStore#createDeleteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>\))`
-   `[createTrashRequest(ContentResolver,Collection,boolean)](/reference/android/provider/MediaStore#createTrashRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>,%20boolean\))`
-   `[createWriteRequest(ContentResolver,Collection)](/reference/android/provider/MediaStore#createWriteRequest\(android.content.ContentResolver,%20java.util.Collection\<android.net.Uri\>\))`

### createDeleteRequest

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [PendingIntent](/reference/android/app/PendingIntent) createDeleteRequest ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)\> uris)

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to permanently delete the requested media items. When the user approves this request, `[ContentResolver.delete](/reference/android/content/ContentResolver#delete\(android.net.Uri,%20android.os.Bundle\))` will be called on these items.

This call only generates the request for a prompt; to display the prompt, call `[Activity.startIntentSenderForResult](/reference/android/app/Activity#startIntentSenderForResult\(android.content.IntentSender,%20int,%20android.content.Intent,%20int,%20int,%20int\))` with `[PendingIntent.getIntentSender()](/reference/android/app/PendingIntent#getIntentSender\(\))`. You can then determine if the user granted your request by testing for `[Activity.RESULT_OK](/reference/android/app/Activity#RESULT_OK)` in `[Activity.onActivityResult](/reference/android/app/Activity#onActivityResult\(int,%20int,%20android.content.Intent\))`. The requested operation will have completely finished before this activity result is delivered.

The displayed prompt will reflect all the media items you're requesting, including those for which you already hold write access. If you want to determine if you already hold write access before requesting access, use `[Context.checkUriPermission(Uri,int,int,int)](/reference/android/content/Context#checkUriPermission\(android.net.Uri,%20int,%20int,%20int\))` with `[Intent.FLAG_GRANT_WRITE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION)`.

Note: if your app targets `[Build.VERSION_CODES.BAKLAVA](/reference/android/os/Build.VERSION_CODES#BAKLAVA)` and above, you can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris will result in a `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: Used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`, but if you need more explicit lifecycle controls, you can obtain a `[ContentProviderClient](/reference/android/content/ContentProviderClient)` and wrap it using `[ContentResolver.wrap(ContentProviderClient)](/reference/android/content/ContentResolver#wrap\(android.content.ContentProviderClient\))`.  
This value cannot be `null`.
 |
| `uris` | `Collection`: The set of media items to include in this request. Each item must be hosted by `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` and must reference a specific media item by `[BaseColumns._ID](/reference/android/provider/BaseColumns#_ID)`.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[PendingIntent](/reference/android/app/PendingIntent)` | This value cannot be `null`.
 |

### createFavoriteRequest

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [PendingIntent](/reference/android/app/PendingIntent) createFavoriteRequest ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)\> uris, 
                boolean value)

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to favorite the requested media items. When the user approves this request, `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)` is set on these items.

This call only generates the request for a prompt; to display the prompt, call `[Activity.startIntentSenderForResult](/reference/android/app/Activity#startIntentSenderForResult\(android.content.IntentSender,%20int,%20android.content.Intent,%20int,%20int,%20int\))` with `[PendingIntent.getIntentSender()](/reference/android/app/PendingIntent#getIntentSender\(\))`. You can then determine if the user granted your request by testing for `[Activity.RESULT_OK](/reference/android/app/Activity#RESULT_OK)` in `[Activity.onActivityResult](/reference/android/app/Activity#onActivityResult\(int,%20int,%20android.content.Intent\))`. The requested operation will have completely finished before this activity result is delivered.

The displayed prompt will reflect all the media items you're requesting, including those for which you already hold write access. If you want to determine if you already hold write access before requesting access, use `[Context.checkUriPermission(Uri,int,int,int)](/reference/android/content/Context#checkUriPermission\(android.net.Uri,%20int,%20int,%20int\))` with `[Intent.FLAG_GRANT_WRITE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION)`.

Note: if your app targets `[Build.VERSION_CODES.BAKLAVA](/reference/android/os/Build.VERSION_CODES#BAKLAVA)` and above, you can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris will result in a `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: Used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`, but if you need more explicit lifecycle controls, you can obtain a `[ContentProviderClient](/reference/android/content/ContentProviderClient)` and wrap it using `[ContentResolver.wrap(ContentProviderClient)](/reference/android/content/ContentResolver#wrap\(android.content.ContentProviderClient\))`.  
This value cannot be `null`.
 |
| `uris` | `Collection`: The set of media items to include in this request. Each item must be hosted by `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` and must reference a specific media item by `[BaseColumns._ID](/reference/android/provider/BaseColumns#_ID)`.  
This value cannot be `null`.

 |
| `value` | `boolean`: The `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)` value to apply.

 |

| Returns |
| --- |
| `[PendingIntent](/reference/android/app/PendingIntent)` | This value cannot be `null`.
 |

**See also:**

-   `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)`
-   `[MediaStore.QUERY_ARG_MATCH_FAVORITE](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_FAVORITE)`

### createTrashRequest

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [PendingIntent](/reference/android/app/PendingIntent) createTrashRequest ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)\> uris, 
                boolean value)

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to trash the requested media items. When the user approves this request, `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)` is set on these items.

This call only generates the request for a prompt; to display the prompt, call `[Activity.startIntentSenderForResult](/reference/android/app/Activity#startIntentSenderForResult\(android.content.IntentSender,%20int,%20android.content.Intent,%20int,%20int,%20int\))` with `[PendingIntent.getIntentSender()](/reference/android/app/PendingIntent#getIntentSender\(\))`. You can then determine if the user granted your request by testing for `[Activity.RESULT_OK](/reference/android/app/Activity#RESULT_OK)` in `[Activity.onActivityResult](/reference/android/app/Activity#onActivityResult\(int,%20int,%20android.content.Intent\))`. The requested operation will have completely finished before this activity result is delivered.

The displayed prompt will reflect all the media items you're requesting, including those for which you already hold write access. If you want to determine if you already hold write access before requesting access, use `[Context.checkUriPermission(Uri,int,int,int)](/reference/android/content/Context#checkUriPermission\(android.net.Uri,%20int,%20int,%20int\))` with `[Intent.FLAG_GRANT_WRITE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION)`.

Note: if your app targets `[Build.VERSION_CODES.BAKLAVA](/reference/android/os/Build.VERSION_CODES#BAKLAVA)` and above, you can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris will result in a `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: Used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`, but if you need more explicit lifecycle controls, you can obtain a `[ContentProviderClient](/reference/android/content/ContentProviderClient)` and wrap it using `[ContentResolver.wrap(ContentProviderClient)](/reference/android/content/ContentResolver#wrap\(android.content.ContentProviderClient\))`.  
This value cannot be `null`.
 |
| `uris` | `Collection`: The set of media items to include in this request. Each item must be hosted by `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` and must reference a specific media item by `[BaseColumns._ID](/reference/android/provider/BaseColumns#_ID)`.  
This value cannot be `null`.

 |
| `value` | `boolean`: The `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)` value to apply.

 |

| Returns |
| --- |
| `[PendingIntent](/reference/android/app/PendingIntent)` | This value cannot be `null`.
 |

**See also:**

-   `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)`
-   `[MediaStore.QUERY_ARG_MATCH_TRASHED](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_TRASHED)`

### createWriteRequest

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [PendingIntent](/reference/android/app/PendingIntent) createWriteRequest ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)\> uris)

Create a `[PendingIntent](/reference/android/app/PendingIntent)` that will prompt the user to grant your app write access for the requested media items.

This call only generates the request for a prompt; to display the prompt, call `[Activity.startIntentSenderForResult](/reference/android/app/Activity#startIntentSenderForResult\(android.content.IntentSender,%20int,%20android.content.Intent,%20int,%20int,%20int\))` with `[PendingIntent.getIntentSender()](/reference/android/app/PendingIntent#getIntentSender\(\))`. You can then determine if the user granted your request by testing for `[Activity.RESULT_OK](/reference/android/app/Activity#RESULT_OK)` in `[Activity.onActivityResult](/reference/android/app/Activity#onActivityResult\(int,%20int,%20android.content.Intent\))`. The requested operation will have completely finished before this activity result is delivered.

Permissions granted through this mechanism are tied to the lifecycle of the `[Activity](/reference/android/app/Activity)` that requests them. If you need to retain longer-term access for background actions, you can place items into a `[ClipData](/reference/android/content/ClipData)` or `[Intent](/reference/android/content/Intent)` which can then be passed to `[Context.startService](/reference/android/content/Context#startService\(android.content.Intent\))` or `[JobInfo.Builder.setClipData(ClipData, int)](/reference/android/app/job/JobInfo.Builder#setClipData\(android.content.ClipData,%20int\))`. Be sure to include any relevant access modes you want to retain, such as `[Intent.FLAG_GRANT_WRITE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION)`.

The displayed prompt will reflect all the media items you're requesting, including those for which you already hold write access. If you want to determine if you already hold write access before requesting access, use `[Context.checkUriPermission(Uri,int,int,int)](/reference/android/content/Context#checkUriPermission\(android.net.Uri,%20int,%20int,%20int\))` with `[Intent.FLAG_GRANT_WRITE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION)`.

For security and performance reasons this method does not support `[Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_PERSISTABLE_URI_PERMISSION)` or `[Intent.FLAG_GRANT_PREFIX_URI_PERMISSION](/reference/android/content/Intent#FLAG_GRANT_PREFIX_URI_PERMISSION)`.

The write access granted through this request is general-purpose, and once obtained you can directly `[ContentResolver.update](/reference/android/content/ContentResolver#update\(android.net.Uri,%20android.content.ContentValues,%20android.os.Bundle\))` columns like `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)`, `[MediaColumns.IS_TRASHED](/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED)`, or `[ContentResolver.delete](/reference/android/content/ContentResolver#delete\(android.net.Uri,%20android.os.Bundle\))`.

Note: if your app targets `[Build.VERSION_CODES.BAKLAVA](/reference/android/os/Build.VERSION_CODES#BAKLAVA)` and above, you can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris will result in a `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: Used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`, but if you need more explicit lifecycle controls, you can obtain a `[ContentProviderClient](/reference/android/content/ContentProviderClient)` and wrap it using `[ContentResolver.wrap(ContentProviderClient)](/reference/android/content/ContentResolver#wrap\(android.content.ContentProviderClient\))`.  
This value cannot be `null`.
 |
| `uris` | `Collection`: The set of media items to include in this request. Each item must be hosted by `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` and must reference a specific media item by `[BaseColumns._ID](/reference/android/provider/BaseColumns#_ID)`.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[PendingIntent](/reference/android/app/PendingIntent)` | This value cannot be `null`.
 |

### getDocumentUri

Added in [API level 26](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) getDocumentUri ([Context](/reference/android/content/Context) context, 
                [Uri](/reference/android/net/Uri) mediaUri)

Return a `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri that is an equivalent to the given `[MediaStore](/reference/android/provider/MediaStore)` Uri.

This allows apps with Storage Access Framework permissions to convert between `[MediaStore](/reference/android/provider/MediaStore)` and `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uris that refer to the same underlying item. Note that this method doesn't grant any new permissions; callers must already hold permissions obtained with `[Intent.ACTION_OPEN_DOCUMENT](/reference/android/content/Intent#ACTION_OPEN_DOCUMENT)` or related APIs.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |
| `mediaUri` | `Uri`: The `[MediaStore](/reference/android/provider/MediaStore)` Uri to convert.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | An equivalent `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri. Returns `null` if no equivalent was found.
 |

**See also:**

-   `[getMediaUri(Context,Uri)](/reference/android/provider/MediaStore#getMediaUri\(android.content.Context,%20android.net.Uri\))`

### getExternalVolumeNames

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Set](/reference/java/util/Set)<[String](/reference/java/lang/String)\> getExternalVolumeNames ([Context](/reference/android/content/Context) context)

Return list of all specific volume names that make up `[VOLUME_EXTERNAL](/reference/android/provider/MediaStore#VOLUME_EXTERNAL)`. This includes a unique volume name for each shared storage device that is currently attached, which typically includes `[MediaStore.VOLUME_EXTERNAL_PRIMARY](/reference/android/provider/MediaStore#VOLUME_EXTERNAL_PRIMARY)`.

Each specific volume name can be passed to APIs like `[MediaStore.Images.Media.getContentUri(String)](/reference/android/provider/MediaStore.Images.Media#getContentUri\(java.lang.String\))` to interact with media on that storage device.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[Set](/reference/java/util/Set)<[String](/reference/java/lang/String)>` | This value cannot be `null`.
 |

### getGeneration

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 1](/sdkExtensions)

public static long getGeneration ([Context](/reference/android/content/Context) context, 
                [String](/reference/java/lang/String) volumeName)

Return the latest generation value for the given volume.

Generation numbers are useful for apps that are attempting to quickly identify exactly which media items have been added or changed since a previous point in time. Generation numbers are monotonically increasing over time, and can be safely arithmetically compared.

Detecting media changes using generation numbers is more robust than using `[MediaColumns.DATE_ADDED](/reference/android/provider/MediaStore.MediaColumns#DATE_ADDED)` or `[MediaColumns.DATE_MODIFIED](/reference/android/provider/MediaStore.MediaColumns#DATE_MODIFIED)`, since those values may change in unexpected ways when apps use `[File.setLastModified(long)](/reference/java/io/File#setLastModified\(long\))` or when the system clock is set incorrectly.

Note that before comparing these detailed generation values, you should first confirm that the overall version hasn't changed by checking `[MediaStore.getVersion(Context,String)](/reference/android/provider/MediaStore#getVersion\(android.content.Context,%20java.lang.String\))`, since that indicates when a more radical change has occurred. If the overall version changes, you should assume that generation numbers have been reset and perform a full synchronization pass.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |
| `volumeName` | `String`: specific volume to obtain an generation value for. Must be one of the values returned from `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))`.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `long` | 
 |

**See also:**

-   `[MediaColumns.GENERATION_ADDED](/reference/android/provider/MediaStore.MediaColumns#GENERATION_ADDED)`
-   `[MediaColumns.GENERATION_MODIFIED](/reference/android/provider/MediaStore.MediaColumns#GENERATION_MODIFIED)`

### getMediaScannerUri

Added in [API level 1](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) getMediaScannerUri ()

Uri for querying the state of the media scanner.

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | 
 |

### getMediaUri

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) getMediaUri ([Context](/reference/android/content/Context) context, 
                [Uri](/reference/android/net/Uri) documentUri)

Return a `[MediaStore](/reference/android/provider/MediaStore)` Uri that is an equivalent to the given `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri. This only supports `ExternalStorageProvider` and `MediaDocumentsProvider` Uris.

This allows apps with Storage Access Framework permissions to convert between `[MediaStore](/reference/android/provider/MediaStore)` and `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uris that refer to the same underlying item. Note that this method doesn't grant any new permissions, but it grants the same access to the Media Store Uri as the caller has to the given DocumentsProvider Uri; callers must already hold permissions for documentUri obtained with `[Intent.ACTION_OPEN_DOCUMENT](/reference/android/content/Intent#ACTION_OPEN_DOCUMENT)` or related APIs.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |
| `documentUri` | `Uri`: The `[DocumentsProvider](/reference/android/provider/DocumentsProvider)` Uri to convert.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | An equivalent `[MediaStore](/reference/android/provider/MediaStore)` Uri. Returns `null` if no equivalent was found.
 |

**See also:**

-   `[getDocumentUri(Context,Uri)](/reference/android/provider/MediaStore#getDocumentUri\(android.content.Context,%20android.net.Uri\))`

### getOriginalMediaFormatFileDescriptor

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor) getOriginalMediaFormatFileDescriptor ([Context](/reference/android/content/Context) context, 
                [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor) fileDescriptor)

Returns `[ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` representing the original media file format for `fileDescriptor`.

Media files may get transcoded based on an application's media capabilities requirements. However, in various cases, when the application needs access to the original media file, or doesn't attempt to parse the actual byte contents of media files, such as playback using `[MediaPlayer](/reference/android/media/MediaPlayer)` or for off-device backup, this method can be useful.

This method is applicable only for media files managed by `[MediaStore](/reference/android/provider/MediaStore)`.

The method returns the original file descriptor with the same permission that the caller has for the input file descriptor.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |
| `fileDescriptor` | `ParcelFileDescriptor`: This value cannot be `null`.

 |

| Returns |
| --- |
| `[ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` | This value cannot be `null`.
 |

| Throws |
| --- |
| `[IOException](/reference/java/io/IOException)` | if the given `[ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` could not be converted |

**See also:**

-   `[MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT](/reference/android/provider/MediaStore#EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT)`

### getPackageForSearchMediaService

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [T Extensions 22](/sdkExtensions)

public static [String](/reference/java/lang/String) getPackageForSearchMediaService ([ContentResolver](/reference/android/content/ContentResolver) resolver)

Gets the package name of the `[ERROR(SearchMediaService/android.provider.SearchMediaService SearchMediaService)](/)` that client apps use to connect. If the package name is an empty string, it indicates that search service is not supported or there are no valid implementations of the service.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | This value cannot be `null`.
 |

### getPickImagesMaxLimit

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 2](/sdkExtensions)

public static int getPickImagesMaxLimit ()

The maximum limit for the number of items that can be selected using `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)` when launched in multiple selection mode. This can be used as a constant value for `[MediaStore.EXTRA_PICK_IMAGES_MAX](/reference/android/provider/MediaStore#EXTRA_PICK_IMAGES_MAX)`.

| Returns |
| --- |
| `int` | 
 |

### getRecentExternalVolumeNames

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Set](/reference/java/util/Set)<[String](/reference/java/lang/String)\> getRecentExternalVolumeNames ([Context](/reference/android/content/Context) context)

Return list of all recent volume names that have been part of `[VOLUME_EXTERNAL](/reference/android/provider/MediaStore#VOLUME_EXTERNAL)`.

These volume names are not currently mounted, but they're likely to reappear in the future, so apps are encouraged to preserve any indexed metadata related to these volumes to optimize user experiences.

Each specific volume name can be passed to APIs like `[MediaStore.Images.Media.getContentUri(String)](/reference/android/provider/MediaStore.Images.Media#getContentUri\(java.lang.String\))` to interact with media on that storage device.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[Set](/reference/java/util/Set)<[String](/reference/java/lang/String)>` | This value cannot be `null`.
 |

### getRedactedUri

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) getRedactedUri ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Uri](/reference/android/net/Uri) uri)

Returns an EXIF redacted version of `uri` i.e. a `[Uri](/reference/android/net/Uri)` with metadata such as location, GPS datestamp etc. redacted from the EXIF headers.

A redacted Uri can be used to share a file with another application wherein exposing sensitive information in EXIF headers is not desirable. Note: 1. Redacted uris cannot be granted write access and can neither be used to perform any kind of write operations. 2. To get a redacted uri the caller must hold read permission to `uri`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is gotten from `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`  
This value cannot be `null`.
 |
| `uri` | `Uri`: the `[Uri](/reference/android/net/Uri)` Uri to convert.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | redacted version of the `uri`. Returns `null` when the given `[Uri](/reference/android/net/Uri)` could not be found or is unsupported
 |

| Throws |
| --- |
| `[SecurityException](/reference/java/lang/SecurityException)` | if the caller doesn't have the read access to `uri` |

**See also:**

-   `[getRedactedUri(ContentResolver,List)](/reference/android/provider/MediaStore#getRedactedUri\(android.content.ContentResolver,%20java.util.List\<android.net.Uri\>\))`

### getRedactedUri

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [List](/reference/java/util/List)<[Uri](/reference/android/net/Uri)\> getRedactedUri ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [List](/reference/java/util/List)<[Uri](/reference/android/net/Uri)\> uris)

Returns a list of EXIF redacted version of `uris` i.e. a `[Uri](/reference/android/net/Uri)` with metadata such as location, GPS datestamp etc. redacted from the EXIF headers.

A redacted Uri can be used to share a file with another application wherein exposing sensitive information in EXIF headers is not desirable. Note: 1. Order of the returned uris follow the order of the `uris`. 2. Redacted uris cannot be granted write access and can neither be used to perform any kind of write operations. 3. To get a redacted uri the caller must hold read permission to its corresponding uri.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is gotten from `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`  
This value cannot be `null`.
 |
| `uris` | `List`: the list of `[Uri](/reference/android/net/Uri)` Uri to convert.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[List](/reference/java/util/List)<[Uri](/reference/android/net/Uri)>` | a list with redacted version of `uris`, in the same order. Returns `null` when the corresponding `[Uri](/reference/android/net/Uri)` could not be found or is unsupported
 |

| Throws |
| --- |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | if all the uris in `uris` don't belong to same user id |
| `[SecurityException](/reference/java/lang/SecurityException)` | if the caller doesn't have the read access to all the elements in `uris` |

**See also:**

-   `[getRedactedUri(ContentResolver,Uri)](/reference/android/provider/MediaStore#getRedactedUri\(android.content.ContentResolver,%20android.net.Uri\))`

### getRequireOriginal

Added in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static boolean getRequireOriginal ([Uri](/reference/android/net/Uri) uri)

Return if the caller requires the original file contents when calling `[ContentResolver.openFileDescriptor(Uri,String)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String\))`.

| Parameters |
| --- |
| `uri` | `Uri`: This value cannot be `null`.
 |

| Returns |
| --- |
| `boolean` | 
 |

**See also:**

-   `[MediaStore.setRequireOriginal(Uri)](/reference/android/provider/MediaStore#setRequireOriginal\(android.net.Uri\))`

### getVersion

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [String](/reference/java/lang/String) getVersion ([Context](/reference/android/content/Context) context, 
                [String](/reference/java/lang/String) volumeName)

Return an opaque version string describing the `[MediaStore](/reference/android/provider/MediaStore)` state.

Applications that import data from `[MediaStore](/reference/android/provider/MediaStore)` into their own caches can use this to detect that `[MediaStore](/reference/android/provider/MediaStore)` has undergone substantial changes, and that data should be rescanned.

No other assumptions should be made about the meaning of the version. It can return null if requested volume is not mounted. Apps should check for volume to be present in `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))` before using this API.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |
| `volumeName` | `String`: specific volume to obtain an opaque version string for. Must be one of the values returned from `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))`.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | 
 |

### getVersion

Added in [API level 12](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [String](/reference/java/lang/String) getVersion ([Context](/reference/android/content/Context) context)

Return an opaque version string describing the `[MediaStore](/reference/android/provider/MediaStore)` state.

Applications that import data from `[MediaStore](/reference/android/provider/MediaStore)` into their own caches can use this to detect that `[MediaStore](/reference/android/provider/MediaStore)` has undergone substantial changes, and that data should be rescanned.

No other assumptions should be made about the meaning of the version. It can return null if external primary volume is not mounted. Apps should check for volume to be present in `[getExternalVolumeNames(Context)](/reference/android/provider/MediaStore#getExternalVolumeNames\(android.content.Context\))` before using this API.

This method returns the version for `[MediaStore.VOLUME_EXTERNAL_PRIMARY](/reference/android/provider/MediaStore#VOLUME_EXTERNAL_PRIMARY)`; to obtain a version for a different volume, use `[getVersion(Context,String)](/reference/android/provider/MediaStore#getVersion\(android.content.Context,%20java.lang.String\))`.

| Parameters |
| --- |
| `context` | `Context`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | 
 |

### getVolumeName

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [String](/reference/java/lang/String) getVolumeName ([Uri](/reference/android/net/Uri) uri)

Return the volume name that the given `[Uri](/reference/android/net/Uri)` references.

| Parameters |
| --- |
| `uri` | `Uri`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | This value cannot be `null`.
 |

### isCurrentCloudMediaProviderAuthority

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 3](/sdkExtensions)

public static boolean isCurrentCloudMediaProviderAuthority ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [String](/reference/java/lang/String) authority)

Returns `true` if and only if the caller with `authority` is the currently enabled `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`. More specifically, `false` is also returned if the calling uid doesn't match the uid of the `authority`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: This value cannot be `null`.
 |
| `authority` | `String`: This value cannot be `null`.

 |

| Returns |
| --- |
| `boolean` | 
 |

**See also:**

-   `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`
-   `[isSupportedCloudMediaProviderAuthority(ContentResolver,String)](/reference/android/provider/MediaStore#isSupportedCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))`

### isCurrentSystemGallery

Added in [API level 31](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static boolean isCurrentSystemGallery ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                int uid, 
                [String](/reference/java/lang/String) packageName)

Returns true if the given application is the current system gallery of the device.

The system gallery is one app chosen by the OEM that has read & write access to all photos and videos on the device and control over folders in media collections.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`.  
This value cannot be `null`.
 |
| `uid` | `int`: The uid to be checked if it is the current system gallery.

 |
| `packageName` | `String`: The package name to be checked if it is the current system gallery.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `boolean` | 
 |

### isSupportedCloudMediaProviderAuthority

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 3](/sdkExtensions)

public static boolean isSupportedCloudMediaProviderAuthority ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [String](/reference/java/lang/String) authority)

Returns `true` if and only if the caller with `authority` is a supported `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`. More specifically, `false` is also returned if the calling uid doesn't match the uid of the `authority`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: This value cannot be `null`.
 |
| `authority` | `String`: This value cannot be `null`.

 |

| Returns |
| --- |
| `boolean` | 
 |

**See also:**

-   `[CloudMediaProvider](/reference/android/provider/CloudMediaProvider)`
-   `[isCurrentCloudMediaProviderAuthority(ContentResolver,String)](/reference/android/provider/MediaStore#isCurrentCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))`

### markIsFavoriteStatus

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 16](/sdkExtensions)

public static void markIsFavoriteStatus ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Collection](/reference/java/util/Collection)<[Uri](/reference/android/net/Uri)\> uris, 
                boolean areFavorites)

Sets the media isFavorite status if the calling app has wider read permission on media files for given type. Calling app should have one of READ\_EXTERNAL\_STORAGE or WRITE\_EXTERNAL\_STORAGE if target sdk <= T. For target sdk > T, it should have READ\_MEDIA\_IMAGES for images, READ\_MEDIA\_VIDEOS for videos or READ\_MEDIA\_AUDIO for audio files or MANAGE\_EXTERNAL\_STORAGE permission.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`  
This value cannot be `null`.
 |
| `uris` | `Collection`: a collection of media items to include in this request. Each item must be hosted by `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` and must reference a specific media item by `[BaseColumns._ID](/reference/android/provider/BaseColumns#_ID)` sample uri - content://media/external\_primary/images/media/24.  
This value cannot be `null`.

 |
| `areFavorites` | `boolean`: the `[MediaColumns.IS_FAVORITE](/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE)` value to apply.

 |

### notifyCloudMediaChangedEvent

Added in [API level 33](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 3](/sdkExtensions)

public static void notifyCloudMediaChangedEvent ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [String](/reference/java/lang/String) authority, 
                [String](/reference/java/lang/String) currentMediaCollectionId)

Notifies the OS about a cloud media event requiring a full or incremental media collection sync for the currently enabled cloud provider, `authority`. The OS will schedule the sync in the background and will attempt to batch frequent notifications into a single sync event. If the caller is not the currently enabled cloud provider as returned by `[isCurrentCloudMediaProviderAuthority(ContentResolver,String)](/reference/android/provider/MediaStore#isCurrentCloudMediaProviderAuthority\(android.content.ContentResolver,%20java.lang.String\))`, the request will be unsuccessful.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: This value cannot be `null`.
 |
| `authority` | `String`: This value cannot be `null`.

 |
| `currentMediaCollectionId` | `String`: This value cannot be `null`.

 |

| Throws |
| --- |
| `[SecurityException](/reference/java/lang/SecurityException)` | if the request was unsuccessful. |

### openAssetFileDescriptor

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 15](/sdkExtensions)

public static [AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor) openAssetFileDescriptor ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Uri](/reference/android/net/Uri) uri, 
                [String](/reference/java/lang/String) mode, 
                [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)

Works exactly the same as `[ContentResolver.openAssetFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

This API is preferred over `[ContentResolver.openAssetFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))` when opening media Uri for ensuring system stability especially when opening URIs returned as a result of using `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is gotten from `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`  
This value cannot be `null`.
 |
| `uri` | `Uri`: The desired URI to open.  
This value cannot be `null`.

 |
| `mode` | `String`: The string representation of the file mode. Can be "r", "w", "wt", "wa", "rw" or "rwt". Please note the exact implementation of these may differ for each Provider implementation - for example, "w" may or may not truncate.  
This value cannot be `null`.

 |
| `cancellationSignal` | `CancellationSignal`: This value may be `null`.

 |

| Returns |
| --- |
| `[AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor)` | a new ParcelFileDescriptor pointing to the file or `null` if the provider recently crashed. You own this descriptor and are responsible for closing it when done.
 |

| Throws |
| --- |
| `[FileNotFoundException](/reference/java/io/FileNotFoundException)` | if no file exists under the URI. |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | if The URI is not for `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` |

### openFileDescriptor

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 15](/sdkExtensions)

public static [ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor) openFileDescriptor ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Uri](/reference/android/net/Uri) uri, 
                [String](/reference/java/lang/String) mode, 
                [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)

Works exactly the same as `[ContentResolver.openFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

This API is preferred over `[ContentResolver.openFileDescriptor(Uri,String,CancellationSignal)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal\))` when opening media Uri for ensuring system stability especially when opening URIs returned as a result of using `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is gotten from `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`  
This value cannot be `null`.
 |
| `uri` | `Uri`: The desired URI to open.  
This value cannot be `null`.

 |
| `mode` | `String`: The string representation of the file mode. Can be "r", "w", "wt", "wa", "rw" or "rwt". Please note the exact implementation of these may differ for each Provider implementation - for example, "w" may or may not truncate.  
This value cannot be `null`.

 |
| `cancellationSignal` | `CancellationSignal`: A signal to cancel the operation in progress, or null if none. If the operation is canceled, then `[OperationCanceledException](/reference/android/os/OperationCanceledException)` will be thrown.

 |

| Returns |
| --- |
| `[ParcelFileDescriptor](/reference/android/os/ParcelFileDescriptor)` | a new ParcelFileDescriptor pointing to the file or `null` if the provider recently crashed. You own this descriptor and are responsible for closing it when done.
 |

| Throws |
| --- |
| `[FileNotFoundException](/reference/java/io/FileNotFoundException)` | if no file exists under the URI. |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | if The URI is not for `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` |

### openTypedAssetFileDescriptor

Added in [API level 36](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [R Extensions 15](/sdkExtensions)

public static [AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor) openTypedAssetFileDescriptor ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Uri](/reference/android/net/Uri) uri, 
                [String](/reference/java/lang/String) mimeType, 
                [Bundle](/reference/android/os/Bundle) opts, 
                [CancellationSignal](/reference/android/os/CancellationSignal) cancellationSignal)

Works exactly the same as `[ContentResolver.openTypedAssetFileDescriptor(Uri,String,Bundle,CancellationSignal)](/reference/android/content/ContentResolver#openTypedAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))`, but only works for `[Uri](/reference/android/net/Uri)` whose scheme is `[ContentResolver.SCHEME_CONTENT](/reference/android/content/ContentResolver#SCHEME_CONTENT)` and its authority is `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`.

This API is preferred over `[ContentResolver.openTypedAssetFileDescriptor(Uri,String,Bundle,CancellationSignal)](/reference/android/content/ContentResolver#openTypedAssetFileDescriptor\(android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.os.CancellationSignal\))` when opening media Uri for ensuring system stability especially when opening URIs returned as a result of using `[MediaStore.ACTION_PICK_IMAGES](/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)`

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` used to connect with `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)`. Typically this value is gotten from `[Context.getContentResolver()](/reference/android/content/Context#getContentResolver\(\))`  
This value cannot be `null`.
 |
| `uri` | `Uri`: The desired URI to open.  
This value cannot be `null`.

 |
| `mimeType` | `String`: The desired MIME type of the returned data. This can be a pattern such as \*/\*, which will allow the content provider to select a type, though there is no way for you to determine what type it is returning.  
This value cannot be `null`.

 |
| `opts` | `Bundle`: Additional provider-dependent options.  
This value may be `null`.

 |
| `cancellationSignal` | `CancellationSignal`: This value may be `null`.

 |

| Returns |
| --- |
| `[AssetFileDescriptor](/reference/android/content/res/AssetFileDescriptor)` | a new ParcelFileDescriptor from which you can read the data stream from the provider or `null` if the provider recently crashed. Note that this may be a pipe, meaning you can't seek in it. The only seek you should do is if the AssetFileDescriptor contains an offset, to move to that offset before reading. You own this descriptor and are responsible for closing it when done.
 |

| Throws |
| --- |
| `[FileNotFoundException](/reference/java/io/FileNotFoundException)` | if no data of the desired type exists under the URI. |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | if The URI is not for `[MediaStore.AUTHORITY](/reference/android/provider/MediaStore#AUTHORITY)` |

### queryDeletedFiles

Added in [version 37.1](/topic/libraries/support-library/revisions)  
Also in [S Extensions 23](/sdkExtensions)

public static [Cursor](/reference/android/database/Cursor) queryDeletedFiles ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [Bundle](/reference/android/os/Bundle) queryArgs, 
                [CancellationSignal](/reference/android/os/CancellationSignal) signal)

Returns a cursor of deleted items matching the given query arguments.

**Note:** This method only returns deleted items for external volumes. Items deleted from the internal volume are not tracked or returned by this API.

**Note:** If an external volume (such as an SD card) is removed from the device, its associated deleted items are no longer tracked or returned by this API.

The returned cursor includes columns such as `[DeletedFiles.DeletedFilesColumns._ID](/reference/android/provider/BaseColumns#_ID)`, `[DeletedFiles.DeletedFilesColumns.MEDIA_TYPE](/reference/android/provider/MediaStore.DeletedFiles.DeletedFilesColumns#MEDIA_TYPE)`, `[DeletedFiles.DeletedFilesColumns.DELETED_GENERATION](/reference/android/provider/MediaStore.DeletedFiles.DeletedFilesColumns#DELETED_GENERATION)`, and `[DeletedFiles.DeletedFilesColumns.VOLUME_NAME](/reference/android/provider/MediaStore.DeletedFiles.DeletedFilesColumns#VOLUME_NAME)`.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The ContentResolver to use for the call.  
This value cannot be `null`.
 |
| `queryArgs` | `Bundle`: Arguments for the query. Supported arguments include `[ContentResolver.QUERY_ARG_SQL_SELECTION](/reference/android/content/ContentResolver#QUERY_ARG_SQL_SELECTION)`, `[ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS](/reference/android/content/ContentResolver#QUERY_ARG_SQL_SELECTION_ARGS)`, `[ContentResolver.QUERY_ARG_SQL_SORT_ORDER](/reference/android/content/ContentResolver#QUERY_ARG_SQL_SORT_ORDER)`, `[ContentResolver.QUERY_ARG_LIMIT](/reference/android/content/ContentResolver#QUERY_ARG_LIMIT)`, and `[ContentResolver.QUERY_ARG_OFFSET](/reference/android/content/ContentResolver#QUERY_ARG_OFFSET)`. The selection and sort order can filter and sort on any of the columns defined in `[DeletedFiles.DeletedFilesColumns](/reference/android/provider/MediaStore.DeletedFiles.DeletedFilesColumns)`.  
This value may be `null`.

 |
| `signal` | `CancellationSignal`: A cancellation signal to cancel the operation in progress.  
This value may be `null`.

 |

| Returns |
| --- |
| `[Cursor](/reference/android/database/Cursor)` | A cursor of deleted items, or null if the operation failed.
 |

**See also:**

-   `[DeletedFiles.DeletedFilesColumns](/reference/android/provider/MediaStore.DeletedFiles.DeletedFilesColumns)`

### restoreFileFromTrash

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [S Extensions 22](/sdkExtensions)

public static [String](/reference/java/lang/String) restoreFileFromTrash ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [String](/reference/java/lang/String) path, 
                [String](/reference/java/lang/String) targetPath)

Restores a file or directory from a trashed state.

The item will be restored from the system's trash to its original location by default. If the original parent directories do not exist, they will be created as part of the restoration process. Optionally, a new `targetPath` can be specified to restore the item to a different location. If the specified `path` refers to a trashed directory, all its trashed contents will also be restored. If restoring a file would create a name conflict with an existing file, the system resolves this by appending a numerical suffix. For example, if `image.jpeg` exists, the restored file will be named `image (1).jpeg`.

Apps using this API must declare the `[Manifest.permission.MANAGE_EXTERNAL_STORAGE](/reference/android/Manifest.permission#MANAGE_EXTERNAL_STORAGE)` permission in their manifest and be granted it by the user.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` to use for the call.  
This value cannot be `null`.
 |
| `path` | `String`: The absolute path of the trashed file or directory to be restored. This path should be the path of the item as it exists in the trash.  
This value cannot be `null`.

 |
| `targetPath` | `String`: An optional absolute path where the item should be restored. If `null`, the item will be restored to its original location prior to being trashed.

 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | The absolute path of the item after it has been restored.  
This value cannot be `null`.
 |

| Throws |
| --- |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | If the provided `path` is null or empty, or if the `targetPath` (if provided) is invalid. |
| `[IllegalStateException](/reference/java/lang/IllegalStateException)` | If the restoration operation fails due to an unexpected internal system error, such as the system being unable to restore the file |
| `[SecurityException](/reference/java/lang/SecurityException)` | if the caller lacks the `[Manifest.permission.MANAGE_EXTERNAL_STORAGE](/reference/android/Manifest.permission#MANAGE_EXTERNAL_STORAGE)` permission. |

**See also:**

-   `[trashFile(ContentResolver,String)](/reference/android/provider/MediaStore#trashFile\(android.content.ContentResolver,%20java.lang.String\))`

### setIncludePending

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Deprecated in [API level 30](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) setIncludePending ([Uri](/reference/android/net/Uri) uri)

**This method was deprecated in API level 30.**  
consider migrating to `[QUERY_ARG_MATCH_PENDING](/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING)` which is more expressive.

Update the given `[Uri](/reference/android/net/Uri)` to also include any pending media items from calls such as `[ContentResolver.query(Uri,String[],Bundle,CancellationSignal)](/reference/android/content/ContentResolver#query\(android.net.Uri,%20java.lang.String[],%20android.os.Bundle,%20android.os.CancellationSignal\))`. By default no pending items are returned.

| Parameters |
| --- |
| `uri` | `Uri`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | This value cannot be `null`.
 |

**See also:**

-   `[MediaColumns.IS_PENDING](/reference/android/provider/MediaStore.MediaColumns#IS_PENDING)`

### setRequireOriginal

Added in [API level 29](/guide/topics/manifest/uses-sdk-element#ApiLevels)

public static [Uri](/reference/android/net/Uri) setRequireOriginal ([Uri](/reference/android/net/Uri) uri)

Update the given `[Uri](/reference/android/net/Uri)` to indicate that the caller requires the original file contents when calling `[ContentResolver.openFileDescriptor(Uri,String)](/reference/android/content/ContentResolver#openFileDescriptor\(android.net.Uri,%20java.lang.String\))`.

This can be useful when the caller wants to ensure they're backing up the exact bytes of the underlying media, without any Exif redaction being performed.

If the original file contents cannot be provided, a `[UnsupportedOperationException](/reference/java/lang/UnsupportedOperationException)` will be thrown when the returned `[Uri](/reference/android/net/Uri)` is used, such as when the caller doesn't hold `[Manifest.permission.ACCESS_MEDIA_LOCATION](/reference/android/Manifest.permission#ACCESS_MEDIA_LOCATION)`.

| Parameters |
| --- |
| `uri` | `Uri`: This value cannot be `null`.
 |

| Returns |
| --- |
| `[Uri](/reference/android/net/Uri)` | This value cannot be `null`.
 |

**See also:**

-   `[MediaStore.getRequireOriginal(Uri)](/reference/android/provider/MediaStore#getRequireOriginal\(android.net.Uri\))`

### trashFile

Added in [API level 37](/guide/topics/manifest/uses-sdk-element#ApiLevels)  
Also in [S Extensions 22](/sdkExtensions)

public static [String](/reference/java/lang/String) trashFile ([ContentResolver](/reference/android/content/ContentResolver) resolver, 
                [String](/reference/java/lang/String) path)

Moves a file or directory to a trashed state.

If the specified `path` is a directory, all its contents (files and subdirectories) will also be trashed recursively.

Apps using this API must declare the `[Manifest.permission.MANAGE_EXTERNAL_STORAGE](/reference/android/Manifest.permission#MANAGE_EXTERNAL_STORAGE)` permission in their manifest and be granted it by the user.

| Parameters |
| --- |
| `resolver` | `ContentResolver`: The `[ContentResolver](/reference/android/content/ContentResolver)` to use for the call.  
This value cannot be `null`.
 |
| `path` | `String`: The absolute path of the file or directory to be trashed.  
This value cannot be `null`.

 |

| Returns |
| --- |
| `[String](/reference/java/lang/String)` | The path of the item that was trashed, which may differ from the input path if the system modifies it for trash management.  
This value cannot be `null`.
 |

| Throws |
| --- |
| `[IllegalArgumentException](/reference/java/lang/IllegalArgumentException)` | if the file path is null or empty. |
| `[IllegalStateException](/reference/java/lang/IllegalStateException)` | if the trash directory cannot be created or if the file cannot be trashed. |
| `[SecurityException](/reference/java/lang/SecurityException)` | if the caller lacks the `[Manifest.permission.MANAGE_EXTERNAL_STORAGE](/reference/android/Manifest.permission#MANAGE_EXTERNAL_STORAGE)` permission. |

**See also:**

-   `[restoreFileFromTrash(ContentResolver,String,String)](/reference/android/provider/MediaStore#restoreFileFromTrash\(android.content.ContentResolver,%20java.lang.String,%20java.lang.String\))`
