# 主题与样式 Themes and styles

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/develop/ui/views/theming/themes?hl=zh-cn
> 页面标题：样式和主题背景

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
尝试使用 Compose 方式

Jetpack Compose 是推荐用于 Android 的界面工具包。了解如何在 Compose 中使用主题。

[Compose 中的设计系统 →](https://developer.android.com/jetpack/compose/designsystems?hl=zh-cn)

 ![](https://developer.android.com/static/images/android-compose-ui-logo.png?hl=zh-cn)

借助 Android 上的样式和主题背景，您可将应用设计的细节与界面的结构和行为分开，类似于网页设计中的样式表。

样式是一个属性集合，用于指定单个 `[View](https://developer.android.com/reference/android/view/View?hl=zh-cn)` 的外观。样式可以指定字体颜色、字号、背景颜色等属性。

主题是应用于整个应用、activity 或视图层次结构，而非仅仅应用于单个视图的属性集合。当您应用主题背景时，应用或 activity 中的每个视图都会应用其支持的每个主题背景属性。主题还可以将样式应用于非视图元素，例如状态栏和窗口背景。

样式和主题在 `res/values/` 中的[样式资源文件](https://developer.android.com/guide/topics/resources/style-resource?hl=zh-cn)中声明，该文件通常命名为 `styles.xml`。

![](https://developer.android.com/static/training/material/images/material-themes_2x.png?hl=zh-cn)

**图 1.** 应用于同一 activity 的两个主题：`Theme.AppCompat`（左）和 `Theme.AppCompat.Light`（右）。

## 主题与样式

主题背景和样式有许多相似之处，但其用途不同。主题背景和样式具有相同的基本结构，即用于将属性映射到资源的键值对。

_样式_用于为特定类型的视图指定属性。例如，一个样式可以指定一个按钮的属性。您在样式中指定的每个属性都是您可以在布局文件中设置的属性。通过将所有属性提取到一个样式中，可以跨多个 widget 轻松使用和维护这些属性。

_主题背景_定义具名资源的集合，可由样式、布局、微件等引用。主题背景为 Android 资源分配语义名称，例如 `colorPrimary`。

样式和主题背景应当配合使用。例如，您可以用一个样式指定按钮的一部分应为 `colorPrimary` 颜色，另一个部分应为 `colorSecondary` 颜色，而这些颜色的实际定义则在主题背景中提供。当设备进入夜间模式时，您的应用可以从“浅色”主题背景切换为“深色”主题背景，从而更改所有这些资源名称的值。您无需更改样式，因为样式使用的是语义名称而非具体的颜色定义。

如需详细了解如何搭配使用主题和样式，请参阅博文 [Android 样式设置：主题与样式](https://medium.com/androiddevelopers/android-styling-themes-vs-styles-ebe05f917578)。

## 创建并应用样式

如需创建新样式，请打开项目的 `res/values/styles.xml` 文件。对于您想创建的每种样式，请按以下步骤操作：

1.  使用唯一标识样式的名称添加 `<style>` 元素。
2.  为您要定义的每个样式属性添加一个 `<item>` 元素。每一项中的 `name` 会指定您原本会在布局中作为 XML 属性来使用的属性。`<item>` 元素中的值即为相应属性的值。

例如，假设您定义了以下样式：

<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="GreenText" parent="TextAppearance.AppCompat">
        <item name="android:textColor">#00FF00</item>
    </style>
</resources>

您可以将该样式应用到视图，如下所示：

<TextView
    style="@style/GreenText"
    ... />

只要视图接受，样式中指定的每个属性都会应用于该视图。对于不接受的属性，视图会将其忽略。

**注意**：只有添加了 `style` 属性的元素才会收到这些样式属性。子视图未应用样式。如果希望子视图继承样式，则应该改为应用具有 `android:theme` 属性的样式。

不过，您通常不会将样式应用于单个视图，而是会[将样式作为主题应用](#Theme)于整个应用、activity 或视图集合，如本指南的另一部分中所述。

## 扩展和自定义样式

创建自己的样式时，应始终扩展框架或支持库中的现有样式，以保持与平台界面样式的兼容性。如需扩展样式，请使用 `parent` 属性指定要扩展的样式。然后，您可以替换继承的样式属性并添加新属性。

例如，您可以继承 Android 平台的默认文本外观，并按如下所示对其进行修改：

<style name="GreenText" parent="@android:style/TextAppearance">
    <item name="android:textColor">#00FF00</item>
</style>

不过，您应始终继承 Android 支持库中的核心应用样式。支持库中的样式会针对各版本中可用的界面属性进行优化，从而提供兼容性。支持库样式的名称通常与平台样式相似，但包含 `AppCompat`。

如需从库或您自己的项目继承样式，请声明父样式名称，但不要包含上例中所示的 `@android:style/` 部分。例如，以下示例继承了支持库中的文本外观样式：

<style name="GreenText" parent="TextAppearance.AppCompat">
    <item name="android:textColor">#00FF00</item>
</style>

您也可以使用点符号来扩展样式名称，而不是使用 `parent` 属性，从而继承样式（平台中的样式除外）。也就是说，为您的样式名称添加您想继承的样式名称作为前缀，用句点分隔。这种方式通常只能用来扩展您自己的样式，而不能用来扩展其他库的样式。例如，以下样式从上例中的 `GreenText` 继承了所有样式，然后增加了文本大小：

<style name="GreenText.Large">
    <item name="android:textSize">22dp</item>
</style>

您可以继续像这样通过链接更多名称来继承样式，不限次数。

**注意**：如果使用点分表示法来扩展样式，并且还包含 `parent` 属性，那么父样式将替换通过点分表示法继承的任何样式。

如需了解可使用 `<item>` 标记声明的属性，请参阅各个类引用中的“XML 属性”表。所有视图都支持[基础 `View` 类中的 XML 属性](https://developer.android.com/reference/android/view/View?hl=zh-cn#lattrs)，而且许多视图还可以添加自己的特殊属性。例如，[`TextView` XML 属性](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#lattrs)包括 [`android:inputType`](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#attr_android:inputType) 属性，您可以将其应用于接收输入内容的文本视图，例如 `[EditText](https://developer.android.com/reference/android/widget/EditText?hl=zh-cn)` widget。

## 将样式作为主题背景来应用

您可以像创建样式一样创建主题背景。不同之处在于应用主题背景的方式：您不是对视图应用具有 `style` 属性的样式，而是对 `AndroidManifest.xml` 文件中的 `<application>` 标签或 `<activity>` 标签应用具有 `android:theme` 属性的主题背景。

例如，下面的代码演示了如何将 Android 支持库的 Material Design“深色”主题背景应用到整个应用：

<manifest ... \>
    <application android:theme="@style/Theme.AppCompat" ... \>
    </application>
</manifest>

下面的代码演示了如何将“浅色”主题应用到一个 activity：

<manifest ... \>
    <application ... \>
        <activity android:theme="@style/Theme.AppCompat.Light" ... \>
        </activity>
    </application>
</manifest>

应用或 activity 中的每个视图都会应用指定主题背景中定义的其支持的样式。如果视图仅支持在样式中声明的某些属性，则它仅会应用这些属性，而忽略其不支持的属性。

从 Android 5.0（API 级别 21）和 Android 支持库 v22.1 开始，您还可以在布局文件中为视图指定 `android:theme` 属性。这会修改该视图及任何子视图的主题背景，适用于更改界面特定部分中的主题背景调色板。

前面的示例展示了如何应用主题背景，例如 Android 支持库提供的 `Theme.AppCompat`。不过，您通常需要自定义主题背景来适合应用的品牌。要实现这一目的，最好的方式是对支持库中的这些样式进行扩展，并替换一些属性，如下一部分所述。

## 样式层次结构

Android 提供了多种在整个 Android 应用中设置属性的方法。例如，您可以直接在布局中设置属性，将样式应用到视图，将主题背景应用到布局，甚至以编程方式设置属性。

在选择如何为应用设置样式时，需考虑 Android 的样式层次结构。一般来说，您应当尽量使用主题背景和样式，以保持一致性。如果您在多个位置指定了相同的属性，下面的列表将决定最终应用哪些属性。该列表按照优先级从高到低的顺序排序。

1.  通过文本 span 将字符或段落级样式应用到 `TextView` 派生的类。
2.  以编程方式应用属性。
3.  将单独的属性直接应用到视图。
4.  将样式应用到视图。
5.  默认样式。
6.  将主题背景应用到视图集合、activity 或整个应用。
7.  应用某些特定于视图的样式，例如为 `TextView` 设置 `[TextAppearance](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#TextAppearance)`。

![](https://developer.android.com/static/guide/topics/ui/images/text-multiple-styles.png?hl=zh-cn)

**图 2.** `span` 中的样式会覆盖 `textAppearance` 中的样式。

**注意**：如果您尝试设置应用样式但没有看到预期结果，有可能是因为其他样式覆盖了您的更改。例如，如果您将某个主题背景应用到您的应用，同时又将某个样式应用到单个 `View`，那么样式属性将会覆盖与该 `View` 匹配的任何主题背景属性。不过请注意，未被样式覆盖的所有主题背景属性仍会被使用。

### TextAppearance

样式的一个局限性在于，您只能对 `View` 应用一种样式。但在 `TextView` 中，您也可以指定功能与样式相似的 `[TextAppearance](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#TextAppearance)` 属性，如以下示例所示：

<TextView
    ...
    android:textAppearance="@android:style/TextAppearance.Material.Headline"
    android:text="This text is styled via textAppearance!" />

`TextAppearance` 可用于定义特定于文本的样式，同时让 `View` 的样式可用于其他用途。不过请注意，如果您直接在 `View` 或样式中定义任何文本属性，这些值会覆盖 `TextAppearance` 值。

`TextAppearance` 支持 `TextView` 提供的一部分样式属性。如需查看完整的属性列表，请参阅 `[TextAppearance](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#TextAppearance)`。

未包含的一些常见 `TextView` 属性为 [`lineHeight[Multiplier|Extra]`](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#attr_android:lineHeight)、[`lines`](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#attr_android:lines)、[`breakStrategy`](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#attr_android:breakStrategy) 和 [`hyphenationFrequency`](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn#attr_android:hyphenationFrequency)。`TextAppearance` 作用于字符级别而非段落级别，因此不支持影响整个布局的属性。

## 自定义默认主题背景

当您使用 Android Studio 创建项目时，根据项目的 `styles.xml` 文件中的定义，它默认会将 Material Design 主题应用于您的应用。该 `AppTheme` 样式扩展了支持库中的主题背景，替换了[应用栏](https://developer.android.com/training/appbar?hl=zh-cn)和[浮动操作按钮](https://developer.android.com/guide/topics/ui/floating-action-button?hl=zh-cn)（如果使用）等关键界面元素所用的颜色属性。因此，您可以通过更新提供的颜色，快速自定义应用的颜色设计。

例如，您的 `styles.xml` 文件应类似于下方所示：

<style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
    <!-- Customize your theme here. \-->
    <item name="colorPrimary">@color/colorPrimary</item>
    <item name="colorPrimaryDark">@color/colorPrimaryDark</item>
    <item name="colorAccent">@color/colorAccent</item>
</style>

样式值实际上是对项目的 `res/values/colors.xml` 文件中定义的其他[颜色资源](https://developer.android.com/guide/topics/resources/more-resources?hl=zh-cn#Color)的引用。如需更改颜色，您应该修改该文件。请参阅 [Material Design 颜色概览](https://m3.material.io/styles/color/overview)，了解如何通过动态配色和其他自定义颜色来改善用户体验。

了解颜色后，请更新 `res/values/colors.xml` 中的值：

<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--   Color for the app bar and other primary UI elements. \-->
    <color name="colorPrimary">#3F51B5</color>

    <!--   A darker variant of the primary color, used for
           the status bar (on Android 5.0+) and contextual app bars. \-->
    <color name="colorPrimaryDark">#303F9F</color>

    <!--   a secondary color for controls like checkboxes and text fields. \-->
    <color name="colorAccent">#FF4081</color>
</resources>

然后，您可以替换所需的任何其他样式。例如，您可以更改 activity 背景颜色，如下所示：

<style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
    ...
    <item name="android:windowBackground">@color/activityBackground</item>
</style>

如需查看可以在主题中使用的属性的列表，请参阅位于 `[R.styleable.Theme](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#Theme)` 的属性表。为布局中的视图添加样式时，您也可以查看视图类应用中的“XML 属性”表格，以查找属性。例如，所有视图都支持[基础 `View` 类中的 XML 属性](https://developer.android.com/reference/android/view/View?hl=zh-cn#lattrs)。

大多数属性都会应用于特定类型的视图，也有些会应用于所有视图。不过，`[R.styleable.Theme](https://developer.android.com/reference/android/R.styleable?hl=zh-cn#Theme)` 列出的某些主题背景属性会应用于 activity 窗口，而不会应用于布局中的视图。例如，`windowBackground` 可更改窗口背景，而 `windowEnterTransition` 可定义要在 activity 启动时使用的过渡动画。如需了解详情，请参阅[使用动画启动 activity](https://developer.android.com/training/transitions/start-activity?hl=zh-cn)。

Android 支持库还提供了其他属性，可供您用来自定义从 `Theme.AppCompat` 扩展的主题背景，例如上例中所示的 `colorPrimary` 属性。最好在[库的 `attrs.xml` 文件](https://chromium.googlesource.com/android_tools/+/HEAD/sdk/extras/android/support/v7/appcompat/res/values/attrs.xml)中查看这些属性。

**注意**：支持库中的属性名称不使用 `android:` 前缀。 该前缀仅用于 Android 框架中的属性。

您还可能想扩展支持库中提供的不同主题，而不是上一个示例中显示的主题。如需查看可用的主题背景，最好查看[库的 `themes.xml` 文件](https://chromium.googlesource.com/android_tools/+/HEAD/sdk/extras/android/support/v7/appcompat/res/values/themes.xml)。

### 添加特定于版本的样式

如果新版 Android 添加了您想使用的主题属性，您可以将其添加到您的主题中，同时仍与旧版本兼容。您只需要另一个 `styles.xml` 文件，该文件保存在包含[资源版本限定符](https://developer.android.com/guide/topics/resources/providing-resources?hl=zh-cn#AlternativeResources)的 `values` 目录中：

res/values/styles.xml        # themes for all versions
res/values-v21/styles.xml    # themes for API level 21+ only

由于 `values/styles.xml` 文件中的样式适用于所有版本，因此 `values-v21/styles.xml` 中的主题背景可以继承这些样式。这意味着，您可以先从“基础”主题背景开始，然后在特定于版本的样式中扩展该主题背景，以避免重复样式。

例如，如需为 Android 5.0（API 级别 21）及更高版本声明窗口转换，您需要使用新属性。因此，您在 `res/values/styles.xml` 中的基础主题背景可能如下所示：

<resources>
    <!-- Base set of styles that apply to all versions. \-->
    <style name="BaseAppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
        <item name="colorPrimary">@color/primaryColor</item>
        <item name="colorPrimaryDark">@color/primaryTextColor</item>
        <item name="colorAccent">@color/secondaryColor</item>
    </style>

    <!-- Declare the theme name that's actually applied in the manifest file. \-->
    <style name="AppTheme" parent="BaseAppTheme" />
</resources>

然后，在 `res/values-v21/styles.xml` 中添加特定于版本的样式，如下所示：

<resources>
    <!-- extend the base theme to add styles available only with API level 21+ \-->
    <style name="AppTheme" parent="BaseAppTheme">
        <item name="android:windowActivityTransitions">true</item>
        <item name="android:windowEnterTransition">@android:transition/slide\_right</item>
        <item name="android:windowExitTransition">@android:transition/slide\_left</item>
    </style>
</resources>

现在，您可以在清单文件中应用 `AppTheme`，系统会选择适用于每个系统版本的样式。

如需详细了解如何针对不同设备使用备用资源，请参阅[提供备用资源](https://developer.android.com/guide/topics/resources/providing-resources?hl=zh-cn#AlternativeResources)。

## 自定义微件样式

框架和支持库中的每个 widget 都有一个默认样式。例如，当您使用支持库中的主题背景为应用设置样式时，`[Button](https://developer.android.com/reference/android/widget/Button?hl=zh-cn)` 的实例会使用 `Widget.AppCompat.Button` 样式来设置样式。如果您希望将不同的 widget 样式应用于某个按钮，可以使用布局文件中的 `style` 属性来执行此操作。例如，以下代码会应用库的无边框按钮样式：

<Button
    style="@style/Widget.AppCompat.Button.Borderless"
    ... />

如果您想将此样式应用于所有按钮，您可以在主题的 `[buttonStyle](https://developer.android.com/reference/android/R.attr?hl=zh-cn#buttonStyle)` 中声明该样式，如下所示：

<style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
    <item name="buttonStyle">@style/Widget.AppCompat.Button.Borderless</item>
    ...
</style>

您还可以扩展微件样式，就像[扩展任何其他样式](#Customize)一样，然后在布局或主题背景中应用自定义微件样式。

## 其他资源

如需详细了解主题背景和样式，请参阅下面列出的其他资源：

### 博文

-   [Android 样式设置：主题与样式](https://medium.com/androiddevelopers/android-styling-themes-vs-styles-ebe05f917578)
-   [Android 样式设置：常见主题属性](https://medium.com/androiddevelopers/android-styling-common-theme-attributes-8f7c50c9eaba)
-   [Android 样式设置：首选主题属性](https://medium.com/androiddevelopers/android-styling-prefer-theme-attributes-412caa748774)
