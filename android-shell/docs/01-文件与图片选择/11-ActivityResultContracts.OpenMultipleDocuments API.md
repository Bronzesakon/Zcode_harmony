# ActivityResultContracts.OpenMultipleDocuments API

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments?hl=zh-cn
> 页面标题：ActivityResultContracts.OpenMultipleDocuments API
> 快照时间：2026-09-10

---
# ActivityResultContracts.OpenMultipleDocuments

Artifact: [androidx.activity:activity](/jetpack/androidx/releases/activity)

[View Source](https://cs.android.com/search?q=file:androidx/activity/result/contract/ActivityResultContracts.kt+class:androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments)

Added in [1.2.0](/jetpack/androidx/releases/activity#1.2.0)

* * *

[Kotlin](/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments "View this page in Kotlin") |Java

public class [ActivityResultContracts.OpenMultipleDocuments](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments) extends [ActivityResultContract](/reference/androidx/activity/result/contract/ActivityResultContract)

<table class="jd-inheritance-table"><tbody><tr><td colspan="3"><a href="https://developer.android.com/reference/java/lang/Object.html">java.lang.Object</a></td></tr><tr><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="2"><a href="/reference/androidx/activity/result/contract/ActivityResultContract">androidx.activity.result.contract.ActivityResultContract</a></td></tr><tr><td>&nbsp;</td><td class="jd-inheritance-space">&nbsp;&nbsp;&nbsp;↳</td><td colspan="1"><a href="/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments">androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments</a></td></tr></tbody></table>

* * *

An `[ActivityResultContract](/reference/androidx/activity/result/contract/ActivityResultContract)` to prompt the user to open (possibly multiple) documents, receiving their contents as `file:/http:/content:` `[Uri](https://developer.android.com/reference/android/net/Uri.html)`s.

The input is the mime types to filter by, e.g. `image/\*`.

This can be extended to override `[createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#createIntent\(android.content.Context,kotlin.Array\))` if you wish to pass additional extras to the Intent created by `super.createIntent()`.

 
| See also |
| --- |
| `[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract.html)` |  |

## Summary

 
| 
### Public constructors

 |
| --- |
| 

`[OpenMultipleDocuments](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#OpenMultipleDocuments\(\))()`

 |

 
| 
### Public methods

 |
| --- |
| `@[NonNull](/reference/androidx/annotation/NonNull) [Intent](https://developer.android.com/reference/android/content/Intent.html)` | 

`@[CallSuper](/reference/androidx/annotation/CallSuper)   [createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#createIntent\(android.content.Context,kotlin.Array\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context, @[NonNull](/reference/androidx/annotation/NonNull) String[] input)`

Create an intent that can be used for `[android.app.Activity.startActivityForResult](<https://developer.android.com/reference/android/app/Activity.html#startActivityForResult\(android.content.Intent, int\)>)`.

 |
| `final [ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<@[NonNull](/reference/androidx/annotation/NonNull) [List](https://developer.android.com/reference/java/util/List.html)<@[NonNull](/reference/androidx/annotation/NonNull) [Uri](https://developer.android.com/reference/android/net/Uri.html)>>` | 

`[getSynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#getSynchronousResult\(android.content.Context,kotlin.Array\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context, @[NonNull](/reference/androidx/annotation/NonNull) String[] input)`

An optional method you can implement that can be used to potentially provide a result in lieu of starting an activity.

 |
| `final @[NonNull](/reference/androidx/annotation/NonNull) [List](https://developer.android.com/reference/java/util/List.html)<@[NonNull](/reference/androidx/annotation/NonNull) [Uri](https://developer.android.com/reference/android/net/Uri.html)>` | 

`[parseResult](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#parseResult\(kotlin.Int,android.content.Intent\))(int resultCode, [Intent](https://developer.android.com/reference/android/content/Intent.html) intent)`

Convert result obtained from `[android.app.Activity.onActivityResult](<https://developer.android.com/reference/android/app/Activity.html#onActivityResult\(int, int, android.content.Intent\)>)` to `[O](/reference/androidx/activity/result/contract/ActivityResultContract)`.

 |

## Public constructors

### OpenMultipleDocuments

Added in [1.2.0](/jetpack/androidx/releases/activity#1.2.0)

public [OpenMultipleDocuments](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#OpenMultipleDocuments\(\))()

## Public methods

### createIntent

Added in [1.2.0](/jetpack/androidx/releases/activity#1.2.0)

@[CallSuper](/reference/androidx/annotation/CallSuper)  
public @[NonNull](/reference/androidx/annotation/NonNull) [Intent](https://developer.android.com/reference/android/content/Intent.html) [createIntent](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#createIntent\(android.content.Context,kotlin.Array\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context, @[NonNull](/reference/androidx/annotation/NonNull) String\[\] input)

Create an intent that can be used for `[android.app.Activity.startActivityForResult](<https://developer.android.com/reference/android/app/Activity.html#startActivityForResult\(android.content.Intent, int\)>)`.

### getSynchronousResult

Added in [1.2.0](/jetpack/androidx/releases/activity#1.2.0)

public final [ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<@[NonNull](/reference/androidx/annotation/NonNull) [List](https://developer.android.com/reference/java/util/List.html)<@[NonNull](/reference/androidx/annotation/NonNull) [Uri](https://developer.android.com/reference/android/net/Uri.html)\>> [getSynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#getSynchronousResult\(android.content.Context,kotlin.Array\))(@[NonNull](/reference/androidx/annotation/NonNull) [Context](https://developer.android.com/reference/android/content/Context.html) context, @[NonNull](/reference/androidx/annotation/NonNull) String\[\] input)

An optional method you can implement that can be used to potentially provide a result in lieu of starting an activity.

 
| Returns |
| --- |
| `[ActivityResultContract.SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)<@[NonNull](/reference/androidx/annotation/NonNull) [List](https://developer.android.com/reference/java/util/List.html)<@[NonNull](/reference/androidx/annotation/NonNull) [Uri](https://developer.android.com/reference/android/net/Uri.html)>>` | 
the result wrapped in a `[SynchronousResult](/reference/androidx/activity/result/contract/ActivityResultContract.SynchronousResult)` or `null` if the call should proceed to start an activity.

 |

### parseResult

public final @[NonNull](/reference/androidx/annotation/NonNull) [List](https://developer.android.com/reference/java/util/List.html)<@[NonNull](/reference/androidx/annotation/NonNull) [Uri](https://developer.android.com/reference/android/net/Uri.html)\> [parseResult](/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments#parseResult\(kotlin.Int,android.content.Intent\))(int resultCode, [Intent](https://developer.android.com/reference/android/content/Intent.html) intent)

Convert result obtained from `[android.app.Activity.onActivityResult](<https://developer.android.com/reference/android/app/Activity.html#onActivityResult\(int, int, android.content.Intent\)>)` to `[O](/reference/androidx/activity/result/contract/ActivityResultContract)`.
