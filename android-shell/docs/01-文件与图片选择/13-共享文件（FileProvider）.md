# 共享文件（FileProvider）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/training/secure-file-sharing/setup-sharing?hl=zh-cn
> 页面标题：设置文件分享

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
为了安全地将应用中的文件提供给其他应用，您需要将应用配置为 文件的安全句柄，格式为内容 URI。Android `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)`组件为 文件。本课将向您介绍如何向 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 的实现，以及如何 指定要提供给其他应用的文件。

**注意**：`[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 类是 [AndroidX Core 库](https://developer.android.com/jetpack/androidx/releases/core?hl=zh-cn)。相关信息 如何在应用中添加此库，请参见 [声明依赖项](https://developer.android.com/jetpack/androidx/releases/core?hl=zh-cn#declaring_dependencies)。

## 指定 FileProvider

若要为应用定义 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)`，您需要在 。此条目指定了生成内容 URI 以及生成内容 URI 时要使用的授权， 一个 XML 文件的名称，该文件指定了应用程序可以共享的目录。

以下代码段展示了如何将 `[<provider>](https://developer.android.com/guide/topics/manifest/provider-element?hl=zh-cn)` 元素，用于指定 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 类、相应授权方以及 XML 文件名：

<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapp">
    <application
        ...>
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="com.example.myapp.fileprovider"
            android:grantUriPermissions="true"
            android:exported="false">
            <meta-data
                android:name="android.support.FILE\_PROVIDER\_PATHS"
                android:resource="@xml/filepaths" />
        </provider>
        ...
    </application>
</manifest>

在此示例中，`[android:authorities](https://developer.android.com/guide/topics/manifest/provider-element?hl=zh-cn#auth)` 属性指定 URI 授权 您要用于由 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)`。 示例中的授权为 `com.example.myapp.fileprovider`。个人专属 应用，请指定由应用的 带有字符串“fileprovider”的 `[android:package](https://developer.android.com/guide/topics/manifest/manifest-element?hl=zh-cn#package)` 值。了解详情 授权值，请查看主题 [内容 URI](https://developer.android.com/guide/topics/providers/content-provider-basics?hl=zh-cn#ContentURIs) 及相关文档 `[android:authorities](https://developer.android.com/guide/topics/manifest/provider-element?hl=zh-cn#auth)` 属性。

`[<meta-data>](https://developer.android.com/guide/topics/manifest/meta-data-element?hl=zh-cn)` 子元素 `[<provider>](https://developer.android.com/guide/topics/manifest/provider-element?hl=zh-cn)` 指向一个 XML 文件，该文件指定您要 份额。`android:resource` 属性是该文件的路径和名称，不包含 `.xml` 扩展名。此文件的内容将在下一部分进行介绍。

## 指定可共享的目录

将 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 添加到应用清单后， 您需要指定包含要共享的文件的目录。要指定 目录中，首先在 `res/xml/` 中创建 `filepaths.xml` 文件 子目录中在此文件中，通过为 每个目录。以下代码段展示了 `res/xml/filepaths.xml`。该代码段还演示了如何共享子目录 （位于内部存储区域中 `files/` 目录下）：

<paths>
    <files-path path="images/" name="myimages" />
</paths>

在此示例中，`<files-path>` 标记共享了 `files/` 目录中。`path` 属性 共享 `files/` 的 `images/` 子目录。`name` 属性会告知 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 添加路径段 将 `myimages` 设置为 `files/images/` 子目录中文件的内容 URI。

`<paths>` 元素可以有多个子元素，每个子元素指定一个不同的 要共享的目录除了 `<files-path>` 元素之外，您还可以 使用 `<external-path>` 元素共享外部存储空间中的目录； `<cache-path>` 元素，用于共享内部缓存中的目录 目录。如需详细了解指定共享目录的子元素，请参阅 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 参考文档。

**注意**：您只能通过 XML 文件指定要 份额；您不能以编程方式添加目录

现在，您已获得 `[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 的完整规范。 该函数为应用的 `files/` 目录中的文件生成内容 URI， 或用于 `files/` 的子目录中的文件。当您的应用 文件的内容 URI，其中包含使用 `[<provider>](https://developer.android.com/guide/topics/manifest/provider-element?hl=zh-cn)` 个元素 (`com.example.myapp.fileprovider`)， 路径 `myimages/` 和文件名。

例如，如果您根据`[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 代码段，您需要请求该文件的 `default_image.jpg`，`[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider?hl=zh-cn)` 会返回 以下 URI：

content://com.example.myapp.fileprovider/myimages/default\_image.jpg

如需了解其他相关信息，请参阅：

-   [存储选项](https://developer.android.com/guide/topics/data/data-storage?hl=zh-cn)
-   [保存文件](https://developer.android.com/training/basics/data-storage/files?hl=zh-cn)
