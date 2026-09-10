# Material 3 设计系统（Compose 参考）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/develop/ui/compose/designsystems/material3?hl=zh-cn
> 页面标题：Compose 中的 Material Design 3

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
Jetpack Compose 提供了 Material You 和 [Material 3 Expressive](https://m3.material.io/) 的实现，后者是 Material Design 的下一代产品。M3 Expressive 是 Material Design 3 的扩展版本，包含在主题、组件、动画、排版等方面的研究支持更新，旨在帮助您打造出用户喜爱的极具吸引力的产品。它还支持动态配色等 Material You 个性化功能。M3 Expressive 可与 Android 16 视觉样式和系统界面相得益彰。

**注意**： “Material Design 3”“Material 3”和“M3”这三个术语可以互换。

下面，我们将以 [Reply 示例应用](https://github.com/android/compose-samples/tree/main/Reply)为例，演示 [Material Design 3](https://m3.material.io/) 的实现。Reply 示例完全基于 Material Design 3。

![使用 Material Design 3 的 Reply 示例应用](https://developer.android.com/static/develop/ui/compose/images/m3-sampleapp.png?hl=zh-cn)

**图 1**. 使用 Material Design 3 的 Reply 示例应用

## 依赖项

如需开始在 Compose 应用中使用 Material 3，请将 [Compose Material 3](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary?hl=zh-cn) 依赖项添加到 `build.gradle` 文件中：

```
implementation "androidx.compose.material3:material3:$material3_version"
```

添加依赖项后，您就可以开始向应用添加 Material Design 系统，包括颜色、排版和形状。

### 实验性 API

某些 M3 API 被视为实验性 API。在这种情况下，您需要使用 [`ExperimentalMaterial3Api`](https://developer.android.com/reference/kotlin/androidx/compose/material3/ExperimentalMaterial3Api?hl=zh-cn) 注解在函数或文件级别指定 OptIn：

// import androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AppComposable() {
    // M3 composables
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L72-L77)

## Material 主题设置

M3 主题包含以下子系统：[配色方案](https://m3.material.io/styles/color/overview)、[排版](https://m3.material.io/styles/typography/overview)和[形状](https://m3.material.io/styles/shape/overview)。当您自定义这些值时，您所做的更改会自动反映在您用来构建应用的 M3 组件中。

![Material Design 的子系统：颜色、排版和形状](https://developer.android.com/static/develop/ui/compose/images/m3-theming.png?hl=zh-cn)

**图 2**. Material Design 的子系统：颜色、排版和形状

Jetpack Compose 使用 M3 `MaterialTheme` 可组合项实现这些概念：

MaterialTheme(
    colorScheme \= /\* ...
    typography \= /\* ...
    shapes \= /\* ...
) {
    // M3 app content
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L84-L90)

如需为应用内容设置主题，请定义特定于应用的配色方案、排版和形状。

### 配色方案

配色方案的基础是五种关键颜色。每种颜色都对应一个包含 13 种色调的调色板，这些色调由 Material 3 组件使用。例如，以下是 [Reply](https://github.com/android/compose-samples/tree/main/Reply) 的浅色主题的配色方案：

![Reply 示例应用浅色主题](https://developer.android.com/static/develop/ui/compose/images/m3-light.png?hl=zh-cn)

**图 3**。Reply 示例应用浅色主题

详细了解[配色方案和颜色角色](https://m3.material.io/styles/color/the-color-system/key-colors-tones)。

#### 生成配色方案

虽然您可以手动创建自定义 `ColorScheme`，但使用品牌中的源颜色通常更容易生成配色方案。您可以使用 [Material 主题构建器](https://material.io/material-theme-builder)工具执行此操作，并且可以选择导出 Compose 主题代码。系统会生成以下文件：

-   `Color.kt` 包含主题的各种颜色，以及为浅色和深色主题定义的所有角色。

val md\_theme\_light\_primary \= Color(0xFF476810)
val md\_theme\_light\_onPrimary \= Color(0xFFFFFFFF)
val md\_theme\_light\_primaryContainer \= Color(0xFFC7F089)
// ..
// ..

val md\_theme\_dark\_primary \= Color(0xFFACD370)
val md\_theme\_dark\_onPrimary \= Color(0xFF213600)
val md\_theme\_dark\_primaryContainer \= Color(0xFF324F00)
// ..
// ..

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L96-L106)

-   `Theme.kt` 包含浅色和深色配色方案以及应用主题的设置。

private val LightColorScheme \= lightColorScheme(
    primary \= md\_theme\_light\_primary,
    onPrimary \= md\_theme\_light\_onPrimary,
    primaryContainer \= md\_theme\_light\_primaryContainer,
    // ..
)
private val DarkColorScheme \= darkColorScheme(
    primary \= md\_theme\_dark\_primary,
    onPrimary \= md\_theme\_dark\_onPrimary,
    primaryContainer \= md\_theme\_dark\_primaryContainer,
    // ..
)

@Composable
fun ReplyTheme(
    darkTheme: Boolean \= isSystemInDarkTheme(),
    content: @Composable () \-\> Unit
) {
    val colorScheme \=
        if (!darkTheme) {
            LightColorScheme
        } else {
            DarkColorScheme
        }
    MaterialTheme(
        colorScheme \= colorScheme,
        content \= content
    )
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L110-L138)

如需支持浅色主题和深色主题，请使用 `isSystemInDarkTheme()`。根据系统设置，定义要使用的配色方案：浅色或深色。

#### 动态配色方案

[动态颜色](https://m3.material.io/styles/color/dynamic-color/overview)是 Material You 的关键部分，使用此功能时，算法会从用户的壁纸中派生自定义颜色，以将其应用到其应用和系统界面。您可以使用此调色板作为起始点，来生成浅色和深色配色方案。

![Reply 示例应用基于壁纸的动态主题设置（左）和默认应用主题设置（右）
](https://developer.android.com/static/develop/ui/compose/images/m3-dynamic.png?hl=zh-cn)

**图 4**. Reply 示例应用动态主题设置（左）和默认应用主题设置（右）

动态颜色适用于 Android 12 及更高版本。如果动态颜色可用，您可以设置动态 `ColorScheme`。如果不可用，您应回退采用自定义的浅色或深色 `ColorScheme`。

`ColorScheme` 提供了构建器函数来创建动态[浅色](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary?hl=zh-cn#dynamiclightcolorscheme)或[深色](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary?hl=zh-cn#dynamicdarkcolorscheme)配色方案：

// Dynamic color is available on Android 12+
val dynamicColor \= Build.VERSION.SDK\_INT \>\= Build.VERSION\_CODES.S
val colors \= when {
    dynamicColor && darkTheme \-\> dynamicDarkColorScheme(LocalContext.current)
    dynamicColor && !darkTheme \-\> dynamicLightColorScheme(LocalContext.current)
    darkTheme \-\> DarkColorScheme
    else \-\> LightColorScheme
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L144-L151)

#### 颜色的使用

您可以通过 `MaterialTheme.colorScheme` 在应用中访问 Material 主题颜色：

Text(
    text \= "Hello theming",
    color \= MaterialTheme.colorScheme.primary
)

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L158-L161)

每种颜色角色均可在各种位置使用，具体取决于组件的状态、显眼程度和强调效果。

-   主色是基础颜色，用于主要组件，例如显眼的按钮、活动状态和高出表面的着色。
-   辅助关键色用于界面中不太显眼的组件（例如条状过滤标签），并扩大了颜色表达的机会。
-   第三个关键颜色用于派生对比强调色的角色，这些颜色可用于平衡主色和辅色，或引起用户对某个元素的更多关注。

Reply 示例应用设计在主容器上使用 on-primary-container 颜色来突出显示所选项目。

![主容器和具有 on-primary-container 颜色的文本字段。](https://developer.android.com/static/develop/ui/compose/images/m3-container.png?hl=zh-cn)

**图 5**. 具有 on-primary-container 颜色的主容器和文本字段。

Card(
    colors \= CardDefaults.cardColors(
        containerColor \=
        if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant
    )
) {
    Text(
        text \= "Dinner club",
        style \= MaterialTheme.typography.bodyLarge,
        color \=
        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
    )
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L171-L187)

在此处，您可以在“回复”抽屉式导航栏中看到次要容器颜色和第三色如何通过对比来突出显示和强调。

![悬浮操作按钮的三级容器和三级容器上颜色组合。](https://developer.android.com/static/develop/ui/compose/images/m3-navdrawer.png?hl=zh-cn)

**图 6**. 悬浮操作按钮的三级容器和三级容器上的内容组合。

### 排版

Material Design 3 定义了一个[字体比例](https://m3.material.io/styles/typography/overview)，包括改写自 Material Design 2 的文本样式。命名和分组已简化为：显示、大标题、标题、正文和标签，每个都有大号、中号和小号。

![Material Design 3 的默认字体排版比例](https://developer.android.com/static/develop/ui/compose/images/m3-typography.png?hl=zh-cn)

**图 7**。Material Design 3 的默认排版比例

<table><tbody><tr><td><strong>M3</strong></td><td><strong>默认字体大小/行高</strong></td></tr><tr><td><code dir="ltr" translate="no">displayLarge</code></td><td><code dir="ltr" translate="no">Roboto 57/64</code></td></tr><tr><td><code dir="ltr" translate="no">displayMedium</code></td><td><code dir="ltr" translate="no">Roboto 45/52</code></td></tr><tr><td><code dir="ltr" translate="no">displaySmall</code></td><td><code dir="ltr" translate="no">Roboto 36/44</code></td></tr><tr><td><code dir="ltr" translate="no">headlineLarge</code></td><td><code dir="ltr" translate="no">Roboto 32/40</code></td></tr><tr><td><code dir="ltr" translate="no">headlineMedium</code></td><td><code dir="ltr" translate="no">Roboto 28/36</code></td></tr><tr><td><code dir="ltr" translate="no">headlineSmall</code></td><td><code dir="ltr" translate="no">Roboto 24/32</code></td></tr><tr><td><code dir="ltr" translate="no">titleLarge</code></td><td><code dir="ltr" translate="no">New- Roboto Medium 22/28</code></td></tr><tr><td><code dir="ltr" translate="no">titleMedium</code></td><td><code dir="ltr" translate="no">Roboto Medium 16/24</code></td></tr><tr><td><code dir="ltr" translate="no">titleSmall</code></td><td><code dir="ltr" translate="no">Roboto Medium 14/20</code></td></tr><tr><td><code dir="ltr" translate="no">bodyLarge</code></td><td><code dir="ltr" translate="no">Roboto 16/24</code></td></tr><tr><td><code dir="ltr" translate="no">bodyMedium</code></td><td><code dir="ltr" translate="no">Roboto 14/20</code></td></tr><tr><td><code dir="ltr" translate="no">bodySmall</code></td><td><code dir="ltr" translate="no">Roboto 12/16</code></td></tr><tr><td><code dir="ltr" translate="no">labelLarge</code></td><td><code dir="ltr" translate="no">Roboto Medium 14/20</code></td></tr><tr><td><code dir="ltr" translate="no">labelMedium</code></td><td><code dir="ltr" translate="no">Roboto Medium 12/16</code></td></tr><tr><td><code dir="ltr" translate="no">labelSmall</code></td><td><code dir="ltr" translate="no">New Roboto Medium, 11/16</code></td></tr></tbody></table>

**注意**：与 M2 `Typography` 类不同，M3 `Typography` 类目前不包含 `defaultFontFamily` 参数。您将需要在每个单独的 `TextStyles` 中使用 `fontFamily` 参数。

#### 定义排版

Compose 提供了 M3 [`Typography`](https://developer.android.com/reference/kotlin/androidx/compose/material3/Typography?hl=zh-cn) 类，以及现有的 [`TextStyle`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle?hl=zh-cn) 和[字体相关](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/font/package-summary?hl=zh-cn)类，用以对 Material 3 字体比例进行建模。`Typography` 构造函数可以提供每种样式的默认值，因此您可以省略不希望自定义的任何参数：

val replyTypography \= Typography(
    titleLarge \= TextStyle(
        fontWeight \= FontWeight.SemiBold,
        fontSize \= 22.sp,
        lineHeight \= 28.sp,
        letterSpacing \= 0.sp
    ),
    titleMedium \= TextStyle(
        fontWeight \= FontWeight.SemiBold,
        fontSize \= 16.sp,
        lineHeight \= 24.sp,
        letterSpacing \= 0.15.sp
    ),
    // ..
)
// ..

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L194-L209)

![正文大号、正文中号和标签中号，用于不同的排版用途。](https://developer.android.com/static/develop/ui/compose/images/m3-body.png?hl=zh-cn)

**图 8**. 正文大号、正文中号和标签中号，用于不同的排版用途。

您的产品可能不需要用到 Material Design 字体比例的全部 15 种默认样式。在此示例中，我们选择了五种大小，其余的则略掉。

您可以通过更改 [`TextStyle`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle?hl=zh-cn) 和 [与字体相关的](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/font/package-summary?hl=zh-cn)属性（例如 `fontFamily` 和 `letterSpacing`）的默认值来自定义排版。

bodyLarge \= TextStyle(
    fontWeight \= FontWeight.Normal,
    fontFamily \= FontFamily.SansSerif,
    fontStyle \= FontStyle.Italic,
    fontSize \= 16.sp,
    lineHeight \= 24.sp,
    letterSpacing \= 0.15.sp,
    baselineShift \= BaselineShift.Subscript
),

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L214-L222)

定义 `Typography` 后，将其传递给 M3 `MaterialTheme`：

MaterialTheme(
    typography \= replyTypography,
) {
    // M3 app Content
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L229-L233)

#### 使用文本样式

您可以使用 `MaterialTheme.typography` 检索提供给 M3 `MaterialTheme` 可组合项的排版：

Text(
    text \= "Hello M3 theming",
    style \= MaterialTheme.typography.titleLarge
)
Text(
    text \= "you are learning typography",
    style \= MaterialTheme.typography.bodyMedium
)

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L240-L247)

您可以详细了解有关[应用排版](https://m3.material.io/styles/typography/applying-type)的 Material 准则。

### 形状

Material Surface 可以用不同的形状显示。形状能够引导用户注意力、区别组件、传达状态以及呈现品牌风格。

形状比例定义了容器角的样式，提供了一系列圆角程度，从方形到完全圆形。

#### 定义形状

Compose 提供了带有扩展参数的 M3 [`Shapes`](https://developer.android.com/reference/kotlin/androidx/compose/material3/Shapes?hl=zh-cn) 类，以支持新的 M3 形状。M3 形状比例更像[字体比例](https://m3.material.io/styles/typography/)，能够在整个界面中呈现丰富多样的形状。

形状有不同的大小：

-   较小
-   小
-   中号
-   大
-   超大

默认情况下，每个形状都有一个默认值，但您可以替换这些默认值：

val replyShapes \= Shapes(
    extraSmall \= RoundedCornerShape(4.dp),
    small \= RoundedCornerShape(8.dp),
    medium \= RoundedCornerShape(12.dp),
    large \= RoundedCornerShape(16.dp),
    extraLarge \= RoundedCornerShape(24.dp)
)

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L254-L260)

定义 `Shapes` 后，您可以将其传递给 M3 `MaterialTheme`：

MaterialTheme(
    shapes \= replyShapes,
) {
    // M3 app Content
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L266-L270)

#### 使用形状

您可以自定义 `MaterialTheme` 中所有组件的形状缩放比例，也可以按组件进行自定义。

应用具有默认值的中型和大型形状：

Card(shape \= MaterialTheme.shapes.medium) { /\* card content \*/ }
FloatingActionButton(
    shape \= MaterialTheme.shapes.large,
    onClick \= {
    }
) {
    /\* fab content \*/
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L277-L284)

![在“回复”示例应用中，卡片的形状为中等，悬浮操作按钮的形状为大。](https://developer.android.com/static/develop/ui/compose/images/m3-shape.png?hl=zh-cn)

**图 9**. Reply 示例应用中卡片的中等形状和悬浮操作按钮的大形状

在 Compose 中，您还可以使用另外两种形状（`RectangleShape` 和 `CircleShape`）。矩形没有边框半径，圆形则会显示完整的圆角边缘：

Card(shape \= RectangleShape) { /\* card content \*/ }
Card(shape \= CircleShape) { /\* card content \*/ }

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L290-L291)

以下示例展示了一些应用了默认形状值的组件：

![所有 Material 3 组件的默认形状值。](https://developer.android.com/static/develop/ui/compose/images/m3-shape2.png?hl=zh-cn)

**图 10**. 所有 Material 3 组件的默认形状值。

您可以详细了解有关[应用形状](https://m3.material.io/styles/shape/overview)的 Material 准则。

### 强调方式

M3 中的强调效果会使用各种颜色变体及其颜色组合。在 M3 中，您可以通过以下两种方式为界面添加强调效果：

-   同时使用 Surface、surface-variant 和背景以及扩展 M3 颜色系统中的 on-surface、on-surface-variants 颜色。例如，Surface 可以与 on-surface-variant 一起使用，surface-variant 可与 on-surface 一起使用，以提供不同级别的强调效果。

![使用中性颜色组合来强调。](https://developer.android.com/static/develop/ui/compose/images/m3-emphasis.png?hl=zh-cn)

**图 11**。使用中性色组合来突出显示。

-   为文本使用不同粗细的字体。上文中提到，您可以为字体比例提供自定义粗细，以提供不同的强调效果。

bodyLarge \= TextStyle(
    fontWeight \= FontWeight.Bold
),
bodyMedium \= TextStyle(
    fontWeight \= FontWeight.Normal
)

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L299-L304)

**注意**： 对于 M3 中已停用的状态，仍然可以使用具有 Alpha 值的“on-x”（其中 x 可以是 primary、secondary、surface 等）颜色。

## 海拔

Material 3 主要使用色调颜色叠加叠加层来表示高度。这是一种新的方式，用于区分容器和表面，增加的色调高度除了使用阴影外，还使用更突出的色调。

![色调高度搭配阴影高度](https://developer.android.com/static/develop/ui/compose/images/m3-elevation.png?hl=zh-cn)

**图 12**. 色调高度搭配阴影高度 E

深色主题中的高度叠加层在 Material 3 中也已更改为色调颜色叠加层。叠加层颜色来自主要颜色槽。

![Material Design 3 中的阴影高度与色调高度](https://developer.android.com/static/develop/ui/compose/images/m3-surface.png?hl=zh-cn)

**图 13**. Material Design 3 中的阴影高度与色调高度

M3 [Surface](https://developer.android.com/reference/kotlin/androidx/compose/material/Surface.composable?hl=zh-cn#Surface\(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.foundation.BorderStroke,androidx.compose.ui.unit.Dp,kotlin.Function0\)) 是大多数 M3 组件的后备可组合项，同时支持色调和阴影高度：

Surface(
    modifier \= Modifier,
    tonalElevation \= /\*...
    shadowElevation \= /\*...
) {
    Column(content \= content)
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L311-L317)

## Material 组件

Material Design 附带一组丰富的 [Material 组件](https://m3.material.io/components/all-buttons)（例如按钮、微片、卡片、导航栏），这些组件已遵循 Material 主题设置，可帮助您打造精美的 Material Design 应用。您可以立即开始使用具有默认属性的组件。

Button(onClick \= { /\*..\*/ }) {
    Text(text \= "My Button")
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L325-L327)

M3 提供了同一组件的多种版本，可根据强调程度和注意力程度用于不同的角色。

![按钮强调程度从 FAB、主按钮依次递减到文字按钮](https://developer.android.com/static/develop/ui/compose/images/m3-emphasis2.png?hl=zh-cn)

**图 14**。从 FAB、主按钮到文字按钮的按钮强调程度

-   用于强调程度最高的操作的扩展悬浮操作按钮：

ExtendedFloatingActionButton(
    onClick \= { /\*..\*/ },
    modifier \= Modifier
) {
    Icon(
        imageVector \= Icons.Default.Edit,
        contentDescription \= stringResource(id \= R.string.edit),
    )
    Text(
        text \= stringResource(id \= R.string.add\_entry),
    )
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L334-L345)

-   用于高强调操作的填充按钮：

Button(onClick \= { /\*..\*/ }) {
    Text(text \= stringResource(id \= R.string.view\_entry))
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L351-L353)

-   用于低强调操作的文字按钮：

TextButton(onClick \= { /\*..\*/ }) {
    Text(text \= stringResource(id \= R.string.replated\_articles))
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L359-L361)

您可以详细了解 Material [按钮和其他组件](https://m3.material.io/components/all-buttons)。Material 3 提供了各种组件套件，例如按钮、应用栏、导航组件，这些组件专门针对不同的使用情形和屏幕尺寸而设计。

### 导航组件

Material 还提供了多种导航组件，可帮助您根据不同的屏幕尺寸和状态来实现导航。

如果您想定位到 5 个或更少的目的地，则 `NavigationBar` 适用于紧凑型设备：

NavigationBar(modifier \= Modifier.fillMaxWidth()) {
    Destinations.entries.forEach { replyDestination \-\>
        NavigationBarItem(
            selected \= selectedDestination \== replyDestination,
            onClick \= { },
            icon \= { }
        )
    }
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L376-L384)

`NavigationRail` 用于处于横屏模式的小型到中型平板电脑或手机。它可为用户提供人体工学设计，并改善这些设备的用户体验。

NavigationRail(
    modifier \= Modifier.fillMaxHeight(),
) {
    Destinations.entries.forEach { replyDestination \-\>
        NavigationRailItem(
            selected \= selectedDestination \== replyDestination,
            onClick \= { },
            icon \= { }
        )
    }
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L393-L403)

![底部导航栏（左）和侧边导航栏（右）的 Reply 展示](https://developer.android.com/static/develop/ui/compose/images/m3-showcasebottom.png?hl=zh-cn)

**图 15**。`BottomNavigationBar`（左）和 `NavigationRail`（右）的回复展示

使用默认主题同时回复，以便为所有设备尺寸提供沉浸式用户体验。

`NavigationDrawer` 适用于中型到大型平板电脑，这类设备有足够的空间来显示详细信息。您可以将 `PermanentNavigationDrawer` 或 `ModalNavigationDrawer` 与 `NavigationRail` 结合使用。

PermanentNavigationDrawer(modifier \= Modifier.fillMaxHeight(), drawerContent \= {
    Destinations.entries.forEach { replyDestination \-\>
        NavigationRailItem(
            selected \= selectedDestination \== replyDestination,
            onClick \= { },
            icon \= { },
            label \= { }
        )
    }
}) {
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L412-L422)

![永久性抽屉式导航栏的回复展示](https://developer.android.com/static/develop/ui/compose/images/m3-showcasedrawer.png?hl=zh-cn)

**图 16**。Reply 永久性抽屉式导航栏展示

导航选项可提升用户体验、人体工学设计和可及性。如需详细了解 Material 导航组件，请参阅 [Compose 自适应 Codelab](https://codelabs.developers.google.com/jetpack-compose-adaptability?hl=zh-cn)。

### 自定义组件的主题

M3 鼓励个性化和灵活性。所有组件都应用了默认颜色，但公开了灵活的 API，以便在需要时自定义其颜色。

大多数组件（如卡片和按钮）都提供一个默认对象，该对象会公开颜色和高度接口，您可以修改这些接口来定制组件：

val customCardColors \= CardDefaults.cardColors(
    contentColor \= MaterialTheme.colorScheme.primary,
    containerColor \= MaterialTheme.colorScheme.primaryContainer,
    disabledContentColor \= MaterialTheme.colorScheme.surface,
    disabledContainerColor \= MaterialTheme.colorScheme.onSurface,
)
val customCardElevation \= CardDefaults.cardElevation(
    defaultElevation \= 8.dp,
    pressedElevation \= 2.dp,
    focusedElevation \= 4.dp
)
Card(
    colors \= customCardColors,
    elevation \= customCardElevation
) {
    // m3 card content
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L429-L445)

您可以详细了解如何[自定义 Material 3](https://m3.material.io/foundations/customization)。

## 系统界面

Material You 的某些方面来自 Android 12 及更高版本上的全新视觉风格和系统界面。发生变化的两个主要方面是涟漪效果和滚动回弹效果。您无需执行任何额外操作即可实施这些更改。

### 涟漪

现在，按下时，涟漪会使用微小的火花照射表面。[Compose Material 涟漪](https://developer.android.com/reference/kotlin/androidx/compose/material/ripple/package-summary?hl=zh-cn)在 Android 上使用底层平台 RippleDrawable，因此，Android 12 及更高版本上提供的火花涟漪适用于所有 Material 组件。

![M2 与 M3 中的 Ripple](https://developer.android.com/static/develop/ui/compose/images/m3-ripple.gif?hl=zh-cn)

**图 17**。M2 与 M3 之间的波幅

### 滚动

滚动回弹现在会在滚动容器的边缘使用[拉伸效果](https://developer.android.com/about/versions/12/behavior-changes-all?hl=zh-cn#overscroll)。在滚动容器可组合项（例如 [`LazyColumn`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary?hl=zh-cn#lazycolumn)、[`LazyRow`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary?hl=zh-cn#lazyrow) 和 [`LazyVerticalGrid`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary?hl=zh-cn#lazyverticalgrid)）中，拉伸滚动回弹默认处于开启状态，无论 API 级别为何，在 [Compose Foundation](https://developer.android.com/jetpack/androidx/releases/compose-foundation?hl=zh-cn) 1.1.0 及更高版本中都是如此。

![在容器边缘使用拉伸效果的滚动回弹](https://developer.android.com/static/develop/ui/compose/images/m3-overscroll.gif?hl=zh-cn)

**图 18**。在容器边缘使用拉伸效果的滚动回弹

## 无障碍

Material 组件中内置的无障碍标准旨在为包容性产品设计奠定基础。了解产品的无障碍设计有助于提高所有用户的易用性，包括有以下情况的用户：弱视、失明、听力障碍、认知障碍、运动障碍或情境式障碍（如手臂骨折）。

### 颜色无障碍功能

动态配色旨在满足色彩对比度方面的无障碍标准。 色调调色板方法对于确保任何配色方案默认均易于使用至关重要。

Material 的颜色系统提供标准的色调值和测量方法，可用于实现方便轻松查看的颜色对比度。

![Reply 示例应用：主色、辅色和第三色调色板（从上到下）](https://developer.android.com/static/develop/ui/compose/images/m3-colorpallets.png?hl=zh-cn)

**图 19**。Reply 示例应用：主色、辅色和第三色的色调调色板（从上到下）

所有 Material 组件和动态主题都已使用一组[色调调色板](https://m3.material.io/styles/color/the-color-system/key-colors-tones#a828e350-1551-45e5-8430-eb643e6a7713)中的上述颜色角色，这些调色板旨在满足无障碍功能要求。不过，如果您要自定义组件，请务必使用合适的颜色角色，避免出现不匹配的情况。

在主色上面使用 on-primary，在主容器上使用 on-primary-container，对于其他强调色和中性色也做同样的处理，以向用户提供能够感知的颜色对比度。

在主色上使用三级容器会为用户提供对比度较差的按钮：

// ✅ Button with sufficient contrast ratio
Button(
    onClick \= { },
    colors \= ButtonDefaults.buttonColors(
        containerColor \= MaterialTheme.colorScheme.primary,
        contentColor \= MaterialTheme.colorScheme.onPrimary
    )
) {
}

// ❌ Button with poor contrast ratio
Button(
    onClick \= { },
    colors \= ButtonDefaults.buttonColors(
        containerColor \= MaterialTheme.colorScheme.tertiaryContainer,
        contentColor \= MaterialTheme.colorScheme.primaryContainer
    )
) {
}

[Material3Snippets.kt](https://github.com/android/snippets/blob/14ba9933f6d32671db9e0621bf1a099aa29565c4/compose/snippets/src/main/java/com/example/compose/snippets/designsystems/Material3Snippets.kt#L454-L472)

![对比度足够（左）与对比度不足（右）](https://developer.android.com/static/develop/ui/compose/images/m3-contrast.png?hl=zh-cn)

**图 20**。对比度足够（左）与对比度不足（右）

### 排版无障碍功能

M3 字体比例更新了静态字体斜坡和值，以提供一个简化的动态尺寸类别框架，该框架可在不同设备上进行缩放。

例如，在 M3 中，Display Small 可以根据设备上下文（例如手机或平板电脑）分配不同的值。

## 大屏设备

Material 提供了有关自适应布局和可折叠设备的指南，可让您的应用更易于访问，并改善用户握持大型设备的人体工程学体验。

Material 提供了各种[导航](https://m3.material.io/components/navigation-bar/overview)，可帮助您为大型设备提供更好的用户体验。

您可以详细了解 Android [大屏应用质量指南](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality?hl=zh-cn)，并查看我们的 [Reply 示例](https://github.com/android/compose-samples/tree/main/Reply)，了解自适应设计和无障碍设计。

## 了解详情

如需详细了解 Compose 中的 Material 主题设置，请参阅以下资源：

### 示例应用

-   [回复 M3 示例应用](https://github.com/android/compose-samples/tree/main/Reply)

### 文档

-   [在 Compose 中从 Material 2 迁移到 Material 3](https://developer.android.com/develop/ui/compose/designsystems/material2-material3?hl=zh-cn)
-   [Material Design 指南](https://m3.material.io/)

### API 参考文档和源代码

-   [Compose Material 3 API 参考文档](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary?hl=zh-cn)
-   [源代码中的 Compose Material 3 示例](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/?hl=zh-cn)

### 视频

-   [使用 Jetpack Compose 实现 Material You](https://www.youtube.com/watch?v=jrfuHyMlehc&hl=zh-cn)

## 为您推荐

-   注意：当 JavaScript 处于关闭状态时，系统会显示链接文字
-   [在 Compose 中从 Material 2 迁移至 Material 3](https://developer.android.com/develop/ui/compose/designsystems/material2-material3?hl=zh-cn)
-   [Compose 中的 Material Design 2](https://developer.android.com/develop/ui/compose/designsystems/material?hl=zh-cn)
-   [Compose 中的自定义设计系统](https://developer.android.com/develop/ui/compose/designsystems/custom?hl=zh-cn)
