# 存储访问框架 SAF（打开文件）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/guide/topics/providers/document-provider?hl=zh-cn
> 页面标题：使用存储访问框架打开文件

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
Android 4.4（API 级别 19）引入了存储访问框架 (SAF)。借助 SAF，用户可以浏览和打开各种文档、图片及其他文件，而不用管这些文件来自其首选文档存储提供程序中的哪一个。用户可通过易用的标准界面，跨所有应用和提供程序以统一的方式浏览文件并访问最近用过的文件。

云存储服务或本地存储服务可实现用于封装其服务的 `[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider?hl=zh-cn)`，从而加入此生态系统。客户端应用如需访问提供程序中的文档，只需几行代码即可与 SAF 集成。

SAF 包含以下元素：

-   **文档提供程序**：一种内容提供程序，可让存储服务（如 Google 云端硬盘）提供其管理的文件。文档提供程序以 `[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider?hl=zh-cn)` 类的子类形式实现。文档提供程序的架构基于传统的文件层次结构，但其实际的数据存储方式由您决定。Android 平台包含若干内置的文档提供程序，如 Downloads、Images 和 Videos。
-   **客户端应用**：一种定制化的应用，它会调用 `[ACTION_CREATE_DOCUMENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_CREATE_DOCUMENT)`、`[ACTION_OPEN_DOCUMENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_OPEN_DOCUMENT)` 和 `[ACTION_OPEN_DOCUMENT_TREE](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_OPEN_DOCUMENT_TREE)` intent 操作并接收文档提供程序返回的文件。
-   **选择器**：一种系统界面，可让用户访问所有文档提供程序内满足客户端应用搜索条件的文档。

SAF 提供以下功能：

-   让用户浏览所有文档提供程序的内容，而不仅仅是单个应用的内容。
-   让您的应用获得对文档提供程序所拥有文档的长期、持续访问权限。用户可通过此访问权限添加、修改、保存和删除提供程序中的文件。
-   支持多个用户账号和临时根目录，如只有在插入 U 盘后才会出现的“USB 存储提供程序”。

## 概览

SAF 的核心是一个内容提供程序，它是 `[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider?hl=zh-cn)` 类的一个子类。在文档提供程序内，数据结构采用传统的文件层次结构：

![](https://developer.android.com/static/images/providers/storage_datamodel.png?hl=zh-cn)

**图 1.** 文档提供程序数据模型。根目录指向单个文档，然后整个结构树以此为基点向外延伸。

请注意以下几点：

-   每个文档提供程序都会报告一个或多个_根目录_（文档树结构的起点）。每个根目录都有唯一的 `[COLUMN_ROOT_ID](https://developer.android.com/reference/android/provider/DocumentsContract.Root?hl=zh-cn#COLUMN_ROOT_ID)`，并且指向一个表示该根目录下内容的文档（目录）。根目录采用动态设计，以支持多个账号、临时 USB 存储设备或用户登录和退出等用例。
-   每个根目录下都只有一个文档。该文档指向 1 到 N 个文档，其中每个文档又可指向 1 至 N 个文档。
-   每个存储后端都会使用唯一的 `[COLUMN_DOCUMENT_ID](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#COLUMN_DOCUMENT_ID)` 来引用各个文件和目录，以便将其呈现出来。文档 ID 具有唯一性，且一经发出便不会更改，因为这些 ID 会用于实现 URI 持久授权（不受设备重启影响）。
-   文档可以是可打开的文件（具有特定的 MIME 类型），也可以是包含其他文档的目录（具有 `[MIME_TYPE_DIR](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#MIME_TYPE_DIR)` MIME 类型）。
-   每个文档可拥有不同的功能，具体在 `[COLUMN_FLAGS](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#COLUMN_FLAGS)` 中指定。例如，`[FLAG_SUPPORTS_WRITE](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#FLAG_SUPPORTS_WRITE)`、`[FLAG_SUPPORTS_DELETE](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#FLAG_SUPPORTS_DELETE)` 和 `[FLAG_SUPPORTS_THUMBNAIL](https://developer.android.com/reference/android/provider/DocumentsContract.Document?hl=zh-cn#FLAG_SUPPORTS_THUMBNAIL)`。多个目录中可包含相同的 `COLUMN_DOCUMENT_ID`。

## 控制流

文档提供程序数据模型基于传统的文件层次结构。不过，只要能通过 `[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider?hl=zh-cn)` API 访问数据，您实际上就可以采用自己喜欢的任何方式来存储数据。例如，您可以使用基于标记的云存储空间来存储数据。

图 2 展示的是照片应用可能会如何利用 SAF 来访问存储的数据：

![](https://developer.android.com/static/images/providers/storage_dataflow.png?hl=zh-cn)

**图 2.** 存储访问框架流程。

请注意以下几点：

-   在 SAF 中，提供程序和客户端并不直接交互。客户端会请求与文件进行交互（即读取、修改、创建或删除文件）的权限。
-   当应用（在本示例中为照片应用）触发 `[ACTION_OPEN_DOCUMENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_OPEN_DOCUMENT)` 或 `[ACTION_CREATE_DOCUMENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_CREATE_DOCUMENT)` intent 后，交互便会开始。intent 可包含过滤器，用于进一步细化条件，例如“为我提供所有 MIME 类型为‘图片’”的可打开文件”。
-   当 intent 触发后，系统选择器会联络每个已注册的提供程序，并向用户显示匹配内容的根目录。
-   选择器会为用户提供标准的文档访问界面，即使底层的文档提供程序可能相互之间差异很大，一致性也不受影响。例如，图 2 展示了一个 Google 云端硬盘提供程序、一个 USB 提供程序和一个云提供程序。

在图 3 中，用户正在从搜索图片时打开的选择器中选择“下载内容”文件夹。该选择器还会显示可供客户端应用使用的所有根目录。

![系统选择器中的文件夹选择。](https://developer.android.com/static/images/providers/storage_picker.svg?hl=zh-cn)

**图 3.** 显示已选择“下载”文件夹作为搜索位置的选择器。

在用户选择 Downloads 文件夹后，系统会显示图片。图 4 显示了此过程的结果。用户现在即能以提供程序和客户端应用支持的方式与图片进行交互。

![](https://developer.android.com/static/images/providers/storage_photos.svg?hl=zh-cn)

**图 4.** 存储在“下载”文件夹中的图片（在系统选择器中查看）。

## 编写客户端应用

在 Android 4.3 及更低版本中，如果您想让应用从其他应用中检索文件，则该应用必须调用 `[ACTION_PICK](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_PICK)` 或 `[ACTION_GET_CONTENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_GET_CONTENT)` 等 intent。然后，用户选择一个要从中选取文件的应用。所选应用必须提供用户界面，以便用户浏览和选择可用文件。

在 Android 4.4（API 级别 19）及更高版本中，您还可选择使用 `[ACTION_OPEN_DOCUMENT](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_OPEN_DOCUMENT)` intent，此 intent 会显示由系统控制的选择器界面，以便用户浏览其他应用提供的所有文件。借助此界面，用户便可从任何受支持的应用中选择文件。

在 Android 5.0（API 级别 21）及更高版本中，您还可以使用 `[ACTION_OPEN_DOCUMENT_TREE](https://developer.android.com/reference/android/content/Intent?hl=zh-cn#ACTION_OPEN_DOCUMENT_TREE)` intent，借助此 intent，用户可以选择供客户端应用访问的目录。

**注意：** `ACTION_OPEN_DOCUMENT` 不能替代 `ACTION_GET_CONTENT`。您使用的选项取决于应用的需求：

-   如果您想让应用读取或导入数据，请使用 `ACTION_GET_CONTENT`。使用此方法时，应用会导入数据（如图片文件）的副本。
-   如果您想让应用获得对文档提供程序所拥有文档的长期、持续访问权限，请使用 `ACTION_OPEN_DOCUMENT`。例如，照片编辑应用可让用户编辑存储在文档提供程序中的图片。

如需详细了解如何使用系统选择器界面支持浏览文件和目录，请参阅有关[访问文档和其他文件](https://developer.android.com/training/data-storage/shared/documents-files?hl=zh-cn)的指南。

## 其他资源

如需详细了解文档提供程序，请参考以下资源：

### 示例

-   [StorageProvider](https://github.com/android/storage-samples/tree/main/StorageProvider)

### 视频

-   [DevBytes: Android 4.4 Storage Access Framework: Provider](http://www.youtube.com/watch?v=zxHVeXbK1P4&hl=zh-cn)（DevBytes: Android 4.4 存储访问框架：提供程序）
-   [Virtual Files in the Storage Access Framework](https://www.youtube.com/watch?v=4h7yCZt231Y&hl=zh-cn)（存储访问框架中的虚拟文件）
