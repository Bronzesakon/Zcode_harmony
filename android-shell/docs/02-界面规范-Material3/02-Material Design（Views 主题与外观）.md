# Material Design（Views 主题与外观）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/develop/ui/views/theming/look-and-feel?hl=zh-cn
> 页面标题：Android 的 Material Design

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
尝试使用 Compose 方式

Jetpack Compose 是推荐用于 Android 的界面工具包。了解如何在 Compose 中使用主题。

[Material Design 3 →](https://developer.android.com/jetpack/compose/designsystems/material3?hl=zh-cn)

 ![](https://developer.android.com/static/images/android-compose-ui-logo.png?hl=zh-cn)

Material Design 是用于指导用户在各种平台和设备上进行视觉、动作和互动设计的全面指南。如需在您的 Android 应用中使用 Material Design，请遵循 [Material Design 规范](https://m3.material.io/)中定义的准则。如果您的应用使用 Jetpack Compose，则可以使用 [Compose Material 3](https://developer.android.com/jetpack/androidx/releases/compose-material3?hl=zh-cn) 库。如果您的应用使用视图，则可以使用 [Android Material Components](https://github.com/material-components/material-components-android) 库。

Android 提供了以下功能来帮助您构建 Material Design 应用：

-   一个 Material Design 应用主题背景，用于设置所有界面微件的样式
-   用于复杂视图（例如列表和卡片）的 widget
-   用于自定义阴影和动画的 API

## Material 主题背景和微件

如需充分利用各项 Material 功能（例如，为标准界面微件设置样式），以及简化应用的样式定义，请将基于 Material 的主题背景应用到您的应用中。

![](https://developer.android.com/static/design/material/images/MaterialDark.png?hl=zh-cn)

**图 1.** 深色 Material 主题背景。

![](https://developer.android.com/static/design/material/images/MaterialLight.png?hl=zh-cn)

**图 2.** 浅色 Material 主题。

  

如果您使用 Android Studio 创建 Android 项目，系统会默认应用 Material 主题。 如需了解如何更新项目的主题，请参阅[样式和主题](https://developer.android.com/develop/ui/views/theming/themes?hl=zh-cn)。

如需为用户提供熟悉的体验，请使用 Material 的最常见用户体验模式：

-   通过[悬浮操作按钮](https://developer.android.com/guide/topics/ui/floating-action-button?hl=zh-cn) (FAB) 提升界面的主要操作。
-   使用[应用栏](https://developer.android.com/training/appbar?hl=zh-cn)显示您的品牌、导航、搜索和其他操作。
-   使用[抽屉式导航栏](https://developer.android.com/training/implementing-navigation/nav-drawer?hl=zh-cn)显示和隐藏应用的导航。
-   在应用布局和导航中使用众多其他 Material 组件之一，例如收起工具栏、标签页、底部导航栏等。如需了解所有这些信息，请参阅 [Material Components for Android 目录](https://m3.material.io/components)。

尽可能使用预定义的 Material 图标。例如，对于抽屉式导航栏的导航“菜单”按钮，请使用标准的“汉堡”图标。如需查看可用图标的列表，请参阅 [Material Design 图标](https://m3.material.io/styles/icons/overview)。您还可以使用 Android Studio 的 [Vector Asset Studio](https://developer.android.com/studio/write/vector-asset-studio?hl=zh-cn#importing) 从 Material 图标库中导入 SVG 图标。

## 高度阴影和卡片

除 _X_ 和 _Y_ 属性外，Android 中的视图还具有 _Z_ 属性。此属性表示视图的高度，此高度决定了以下内容：

-   阴影的大小：视图的 _Z_ 值越高，投射的阴影越大。
-   绘制顺序：_Z_ 值较高的视图会显示在其他视图的顶部。

![](https://developer.android.com/static/images/ui/material-design/cast-shadows_2x.png?hl=zh-cn)

**图 3.** 表示海拔高度的 _Z_ 值。

您可以为卡片式布局应用高度，这有助于您在提供 Material 样式的卡片中显示重要的信息。您可以使用 `[CardView](https://developer.android.com/reference/androidx/cardview/widget/CardView?hl=zh-cn)` 微件创建具有默认高度的卡片。如需了解详情，请参阅[创建卡片式布局](https://developer.android.com/guide/topics/ui/layout/cardview?hl=zh-cn)。

如需了解如何向其他视图添加高度，请参阅[创建阴影和剪裁视图](https://developer.android.com/training/material/shadows-clipping?hl=zh-cn)。

## 动画

**图 4.** 轻触反馈动画。

借助动画 API，您可以为界面控件中的触控反馈、视图状态更改和 activity 转换创建自定义动画。

这些 API 的功能包括：

-   使用**轻触反馈**动画响应您的视图中的触摸事件。
-   使用**圆形揭露**动画隐藏和显示视图。
-   使用自定义 **Activity 转换**动画在 Activity 之间切换。
-   使用**曲线运动**创建更自然的动画。
-   使用**视图状态更改**动画为一个或多个视图属性的更改添加动画。
-   视图状态更改期间在**状态列表可绘制对象**中显示动画。

轻触反馈动画内置于多个标准视图中，例如按钮。借助动画 API，您可以自定义这些动画并将其添加到自定义视图中。

如需了解详情，请参阅[动画简介](https://developer.android.com/training/animation/overview?hl=zh-cn)。

## 可绘制对象

[

![](https://developer.android.com/static/images/spot-icons/jetpack-compose.svg?hl=zh-cn)

试试 Compose 方式

在 Compose 中使用可绘制对象

arrow\_forward](https://developer.android.com/jetpack/compose/graphics/images/compare?hl=zh-cn)

以下用于可绘制对象的功能可帮助您实现 Material Design 应用：

-   **矢量可绘制对象**可伸缩，不会失去定义，是单色应用内图标的理想之选。详细了解[矢量可绘制对象](https://developer.android.com/guide/topics/graphics/vector-drawable-resources?hl=zh-cn)。
-   通过**对可绘制对象进行着色**，您可以将位图定义为透明遮罩，并在运行时用一种颜色对其进行着色。了解如何[向可绘制对象添加色调](https://developer.android.com/guide/topics/graphics/2d-graphics?hl=zh-cn#DrawableTint)。
-   **颜色提取**用于自动从位图图片中提取突出颜色。了解如何[使用 Palette API 选择颜色](https://developer.android.com/training/material/palette-colors?hl=zh-cn)。
